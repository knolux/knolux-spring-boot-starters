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
    void getClient_differentPathPrefixes_shouldReturnDifferentInstances() {
        factory = new KnoluxS3ClientFactory(validDetails("http://fake-s3.test:9000"));

        var detailsA = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", true, true, "/prefix-a", false);
        var detailsB = new KnoluxS3ConnectionDetails(
                "http://fake-s3.test:9000", "us-east-1", "k", "s", true, true, "/prefix-b", false);

        assertThat(factory.getClient(detailsA)).isNotSameAs(factory.getClient(detailsB));
    }
}
