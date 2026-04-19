package com.knolux.s3;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KnoluxS3Template} 的整合測試，使用 MinIO 容器模擬 S3 端點。
 *
 * <p>驗證靜態模式（Properties 固定設定）與動態模式（{@link KnoluxS3OperationSpec}）的
 * Upload / Download / Delete 完整流程。
 */
class KnoluxS3IntegrationTest {

    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String BUCKET = "integration-test";

    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO =
            new GenericContainer<>("minio/minio")
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data", "--console-address", ":9001")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static KnoluxS3Properties props;
    static KnoluxS3ClientFactory factory;
    static KnoluxS3Template template;

    @BeforeAll
    static void setUp() {
        try {
            DockerClientFactory.instance().client();
        } catch (Exception e) {
            Assumptions.abort("Docker 不可用，跳過整合測試：" + e.getMessage());
        }
        MINIO.start();

        props = new KnoluxS3Properties();
        props.setEndpoint("http://localhost:" + MINIO.getMappedPort(9000));
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setForcePathStyle(true);

        factory = new KnoluxS3ClientFactory(KnoluxS3ConnectionDetails.of(props));
        template = new KnoluxS3Template(factory);

        factory.getClient()
               .createBucket(CreateBucketRequest.builder().bucket(BUCKET).build())
               .join();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
        MINIO.stop();
    }

    // ── 靜態模式：Upload / Download / Delete ─────────────────────────────────

    @Test
    void staticMode_upload_thenDownload_returnsOriginalContent() {
        String key = "static/hello.txt";
        String content = "Hello, MinIO!";

        template.upload(BUCKET, key, AsyncRequestBody.fromString(content)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(BUCKET, key, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asUtf8String()).isEqualTo(content);
    }

    @Test
    void staticMode_upload_binaryContent_roundTrip() {
        String key = "static/binary.bin";
        byte[] data = {0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xFE};

        template.upload(BUCKET, key, AsyncRequestBody.fromBytes(data)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(BUCKET, key, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asByteArray()).isEqualTo(data);
    }

    @Test
    void staticMode_delete_objectNoLongerAccessible() {
        String key = "static/to-delete.txt";

        template.upload(BUCKET, key, AsyncRequestBody.fromString("temporary")).join();
        template.delete(BUCKET, key).join();

        assertThatThrownBy(() ->
                template.<ResponseBytes<GetObjectResponse>>download(
                        BUCKET, key, AsyncResponseTransformer.toBytes()).join()
        )
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NoSuchKeyException.class);
    }

    @Test
    void staticMode_delete_nonExistentKey_doesNotThrow() {
        // S3 delete 是冪等的：不存在的 key 也回傳成功
        template.delete(BUCKET, "static/non-existent-key.txt").join();
    }

    // ── 動態模式（OperationSpec）：Upload / Download ──────────────────────────

    @Test
    void dynamicMode_operationSpec_upload_thenDownload_roundTrip() {
        String key = "dynamic/spec-test.txt";
        String content = "Dynamic mode works!";

        KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
                .bucket(BUCKET)
                .key(key)
                .build()
                .mergeDefaults(props);

        template.upload(spec, AsyncRequestBody.fromString(content)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(spec, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asUtf8String()).isEqualTo(content);
    }

    @Test
    void dynamicMode_operationSpec_withExplicitCredentials_roundTrip() {
        String key = "dynamic/explicit-creds.txt";
        String content = "Explicit credentials!";

        // 模擬 payload 中帶有連線資訊（同一 MinIO endpoint）
        KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
                .endpoint(props.getEndpoint())
                .accessKey(ACCESS_KEY)
                .secretKey(SECRET_KEY)
                .bucket(BUCKET)
                .key(key)
                .build()
                .mergeDefaults(props);

        template.upload(spec, AsyncRequestBody.fromString(content)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(spec, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asUtf8String()).isEqualTo(content);
    }

    @Test
    void dynamicMode_delete_objectNoLongerAccessible() {
        String key = "dynamic/to-delete.txt";

        KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
                .bucket(BUCKET)
                .key(key)
                .build()
                .mergeDefaults(props);

        template.upload(spec, AsyncRequestBody.fromString("temp")).join();
        template.delete(spec).join();

        assertThatThrownBy(() ->
                template.<ResponseBytes<GetObjectResponse>>download(
                        spec, AsyncResponseTransformer.toBytes()).join()
        )
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NoSuchKeyException.class);
    }

    // ── 進階模式（ConnectionDetails）────────────────────────────────────────

    @Test
    void advancedMode_explicitConnectionDetails_upload_thenDownload() {
        String key = "advanced/conn-details.txt";
        String content = "Advanced mode!";

        KnoluxS3ConnectionDetails conn = KnoluxS3ConnectionDetails.of(props);

        template.upload(BUCKET, key, AsyncRequestBody.fromString(content), conn).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(BUCKET, key, AsyncResponseTransformer.toBytes(), conn).join();
        assertThat(result.asUtf8String()).isEqualTo(content);
    }

    // ── 客戶端快取驗證 ────────────────────────────────────────────────────────

    @Test
    void clientFactory_sameDetails_reusesCachedClient() {
        KnoluxS3ConnectionDetails conn = KnoluxS3ConnectionDetails.of(props);

        var clientA = factory.getClient(conn);
        var clientB = factory.getClient(conn);

        assertThat(clientA).isSameAs(clientB);
    }
}
