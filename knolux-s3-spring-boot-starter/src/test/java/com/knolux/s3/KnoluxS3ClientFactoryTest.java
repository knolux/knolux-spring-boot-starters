package com.knolux.s3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KnoluxS3ClientFactory} 的單元測試。
 *
 * <p>驗證 null 憑證的前置驗證、S3AsyncClient 快取行為，以及 {@link #close()} 生命週期。
 * 測試使用假端點（{@code http://fake-s3.test:9000}），AWS SDK 不會在 build 時建立連線。
 */
class KnoluxS3ClientFactoryTest {

    private KnoluxS3ClientFactory factory;

    private static KnoluxS3ConnectionDetails details(String accessKey, String secretKey) {
        return new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", accessKey, secretKey,
                true, false, "", false
        );
    }

    // ── 前置驗證：null 憑證 ──────────────────────────────────────────────────

    private static KnoluxS3ConnectionDetails validDetails(String endpoint) {
        return new KnoluxS3ConnectionDetails(
                endpoint, "us-east-1", "fake-access-key", "fake-secret-key",
                true, false, "", false
        );
    }

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.close();
            factory = null;
        }
    }

    @Test
    void getClient_withNullAccessKey_shouldThrowIllegalState() {
        factory = new KnoluxS3ClientFactory(details(null, "secret"));

        assertThatThrownBy(() -> factory.getClient())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accessKey")
                .hasMessageContaining("mergeDefaults");
    }

    // ── 快取行為 ─────────────────────────────────────────────────────────────

    @Test
    void getClient_withNullSecretKey_shouldThrowIllegalState() {
        factory = new KnoluxS3ClientFactory(details("key", null));

        assertThatThrownBy(() -> factory.getClient())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("secretKey");
    }

    @Test
    void getClient_withBothNullCredentials_shouldThrowIllegalState() {
        factory = new KnoluxS3ClientFactory(details(null, null));

        assertThatThrownBy(() -> factory.getClient())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getClient_withNull_shouldUseDefaultDetails() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));

        S3AsyncClient client = factory.getClient(null);
        assertThat(client).isNotNull();
    }

    @Test
    void getClient_withNoArgument_shouldReturnSameInstanceAsNullArg() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));

        S3AsyncClient a = factory.getClient();
        S3AsyncClient b = factory.getClient(null);
        assertThat(a).isSameAs(b);
    }

    @Test
    void getClient_calledTwiceWithSameDetails_shouldReturnSameInstance() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));
        var details = validDetails("http://fake-s3.test:9000");

        S3AsyncClient first = factory.getClient(details);
        S3AsyncClient second = factory.getClient(details);
        assertThat(first).isSameAs(second);
    }

    // ── 生命週期 ─────────────────────────────────────────────────────────────

    @Test
    void getClient_withDifferentEndpoints_shouldReturnDifferentInstances() {
        factory = new KnoluxS3ClientFactory(validDetails("http://host-a.test:9000"));

        S3AsyncClient clientA = factory.getClient(validDetails("http://host-a.test:9000"));
        S3AsyncClient clientB = factory.getClient(validDetails("http://host-b.test:9000"));
        assertThat(clientA).isNotSameAs(clientB);
    }

    @Test
    void getClient_withDifferentRegions_shouldReturnDifferentInstances() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));

        var detailsEast = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", true, false, "", false);
        var detailsWest = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-west-2", "k", "s", true, false, "", false);

        assertThat(factory.getClient(detailsEast)).isNotSameAs(factory.getClient(detailsWest));
    }

    @Test
    void close_onEmptyFactory_shouldNotThrow() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));
        factory.close();
        factory = null; // 避免 @AfterEach 重複呼叫
    }

    // ── forcePathStyle ────────────────────────────────────────────────────────

    @Test
    void close_afterBuildingClients_shouldNotThrow() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));
        factory.getClient(); // 建立 client 進入 cache
        factory.close();
        factory = null;
    }

    @Test
    void close_canBeCalledMultipleTimes_shouldNotThrow() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));
        factory.close();
        factory.close(); // 第二次不應拋出
        factory = null;
    }

    @Test
    void close_clearsCache_subsequentGetClientBuildsNewInstance() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));
        S3AsyncClient first = factory.getClient();
        factory.close();
        // close() 已清空 cache → 再次取得應為新實例（若仍相同代表 cache 未釋放）
        S3AsyncClient second = factory.getClient();
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void getClient_whenEndpointMalformed_propagatesExceptionAndStaysClean() {
        // endpoint 含空白 → buildClient 內 URI.create 拋例外，觸發 catch 的洩漏防護
        // （httpClient 先入 cache、失敗時移除並關閉）。驗證例外傳出且後續 close() 乾淨。
        var bad = new KnoluxS3ConnectionDetails(
                "http://bad host:9000", "us-east-1", "k", "s", true, false, "", false);
        factory = new KnoluxS3ClientFactory(bad);

        assertThatThrownBy(() -> factory.getClient())
                .isInstanceOf(IllegalArgumentException.class);

        factory.close(); // 不應拋出（httpClient 已於失敗時回收）
        factory = null;
    }

    @Test
    void getClient_withForcePathStyleFalse_shouldBuildSuccessfully() {
        var details = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", false, false, "", false
        );
        factory = new KnoluxS3ClientFactory(details);

        assertThat(factory.getClient()).isNotNull();
    }

    @Test
    void getClient_withNullEndpoint_shouldBuildSuccessfully() {
        // null endpoint = 使用 AWS 預設端點（AWS S3 模式）
        var details = new KnoluxS3ConnectionDetails(
                null, "us-east-1", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI", false, false, "", false
        );
        factory = new KnoluxS3ClientFactory(details);

        assertThat(factory.getClient()).isNotNull();
    }

    // ── 工具方法 ─────────────────────────────────────────────────────────────

    @Test
    void getClient_withRemovePathPrefix_shouldBuildSuccessfully() {
        // Nginx 代理場景：endpoint + pathPrefix 組合為完整 URL
        var details = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s",
                true, true, "/cluster/s3", false
        );
        factory = new KnoluxS3ClientFactory(details);

        assertThat(factory.getClient()).isNotNull();
    }

    @Test
    void getClient_withPathPrefixMissingLeadingSlash_shouldNormalizeAndBuild() {
        // pathPrefix 缺前導斜線 + endpoint 無尾斜線：未正規化會組出
        // "http://fake-s3.test:9000cluster/s3"（port 解析失敗 → URI.create 拋例外）。
        // 正規化後應組成 "http://fake-s3.test:9000/cluster/s3" 並成功建立。
        var details = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s",
                true, true, "cluster/s3", false   // 注意：無前導斜線
        );
        factory = new KnoluxS3ClientFactory(details);

        assertThat(factory.getClient()).isNotNull();
    }

    @Test
    void getClient_differentPathPrefixes_shouldReturnDifferentInstances() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));

        var detailsA = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", true, true, "/prefix-a", false);
        var detailsB = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", true, true, "/prefix-b", false);

        assertThat(factory.getClient(detailsA)).isNotSameAs(factory.getClient(detailsB));
    }
}
