package com.knolux.s3;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KnoluxS3Template} 的離線單元測試（不需 Docker）。
 *
 * <p>以會記錄連線參數的假 {@link S3ClientProvider} 搭配 Mockito mock 的 {@link S3AsyncClient}，
 * 驗證三件事：
 * <ol>
 *   <li>request 的 bucket / key 組裝正確；</li>
 *   <li>非同步操作失敗時，例外會透過 {@link CompletableFuture} 傳回呼叫端（不被 {@code whenCompleteAsync} 的 log 吞掉）；</li>
 *   <li>動態模式安全邊界 —— 即使呼叫端忘記 {@code mergeDefaults}，只要 template 持有 Properties，
 *       部署級別設定仍一律以 Properties 為準。</li>
 * </ol>
 */
class KnoluxS3TemplateTest {

    /** 記錄最後一次取得 client 時傳入的連線參數，供安全邊界斷言。 */
    static final class CapturingProvider implements S3ClientProvider {
        final S3AsyncClient client;
        KnoluxS3ConnectionDetails captured;

        CapturingProvider(S3AsyncClient client) {
            this.client = client;
        }

        @Override
        public S3AsyncClient getClient(KnoluxS3ConnectionDetails details) {
            this.captured = details;
            return client;
        }

        @Override
        public void close() {
        }
    }

    private static KnoluxS3Properties lockedDownProperties() {
        var props = new KnoluxS3Properties();
        props.setEndpoint("http://props-host:9000");
        props.setAccessKey("props-ak");
        props.setSecretKey("props-sk");
        props.setForcePathStyle(true);
        props.setRemovePathPrefix(false);
        props.setPathPrefix("");
        props.setTrustSelfSigned(false);
        return props;
    }

    private static S3AsyncClient clientWithSuccessfulPut() {
        S3AsyncClient client = mock(S3AsyncClient.class);
        when(client.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenReturn(CompletableFuture.completedFuture(PutObjectResponse.builder().build()));
        return client;
    }

    // ── request 組裝 ─────────────────────────────────────────────────────────

    @Test
    void upload_buildsRequestWithCorrectBucketAndKey() {
        S3AsyncClient client = clientWithSuccessfulPut();
        // 同步 executor：whenCompleteAsync 回呼於 join() 前確定完成
        var template = new KnoluxS3Template(new CapturingProvider(client), Runnable::run);

        template.upload("my-bucket", "path/to/file.txt", AsyncRequestBody.fromString("x")).join();

        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(captor.capture(), any(AsyncRequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
        assertThat(captor.getValue().key()).isEqualTo("path/to/file.txt");
    }

    // ── 例外傳播（非靜默失敗）─────────────────────────────────────────────────

    @Test
    void delete_failedFuture_propagatesExceptionToCaller() {
        S3AsyncClient client = mock(S3AsyncClient.class);
        when(client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("boom")));
        var template = new KnoluxS3Template(new CapturingProvider(client), Runnable::run);

        // 錯誤僅被 log.error，不應被吞掉：join() 必須拋出
        assertThatThrownBy(() -> template.delete("b", "k").join())
                .isInstanceOf(CompletionException.class)
                .hasRootCauseMessage("boom");
    }

    // ── 動態模式安全邊界 ───────────────────────────────────────────────────────

    @Test
    void dynamicMode_withoutMergeDefaults_stillEnforcesDeploymentFlagsFromProperties() {
        S3AsyncClient client = clientWithSuccessfulPut();
        var provider = new CapturingProvider(client);
        // 由 starter 自動裝配的 template 會持有 Properties
        var template = new KnoluxS3Template(provider, Runnable::run, lockedDownProperties());

        // 惡意/疏忽的 payload：嘗試開啟 trustSelfSigned 並注入 pathPrefix，且「忘記」呼叫 mergeDefaults
        var hostileSpec = KnoluxS3OperationSpec.builder()
                .endpoint("http://props-host:9000")
                .accessKey("props-ak")
                .secretKey("props-sk")
                .bucket("b")
                .key("k")
                .trustSelfSigned(true)
                .removePathPrefix(true)
                .pathPrefix("/evil")
                .build();   // 注意：未呼叫 .mergeDefaults(...)

        template.upload(hostileSpec, AsyncRequestBody.fromString("x")).join();

        // template 自動套用 mergeDefaults：部署級別設定一律以 Properties 為準
        assertThat(provider.captured.trustSelfSigned()).isFalse();
        assertThat(provider.captured.removePathPrefix()).isFalse();
        assertThat(provider.captured.pathPrefix()).isEqualTo("");
    }

    @Test
    void dynamicMode_withoutProperties_usesSpecAsIs() {
        S3AsyncClient client = clientWithSuccessfulPut();
        var provider = new CapturingProvider(client);
        // 無 Properties 的 template：維持原行為，由呼叫端負責 mergeDefaults
        var template = new KnoluxS3Template(provider, Runnable::run);

        var spec = KnoluxS3OperationSpec.builder()
                .endpoint("http://payload-host:9000")
                .accessKey("ak")
                .secretKey("sk")
                .bucket("b")
                .key("k")
                .trustSelfSigned(true)
                .build();   // 未 merge

        template.upload(spec, AsyncRequestBody.fromString("x")).join();

        // 無 Properties 時不自動 merge：payload 值原樣使用
        assertThat(provider.captured.trustSelfSigned()).isTrue();
    }
}
