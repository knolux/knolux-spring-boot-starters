package com.knolux.s3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxS3ConnectionDetails} 的單元測試。
 *
 * <p>驗證快取鍵生成的 null 安全性、工廠方法，以及不同連線參數產生不同 key 的正確性。
 */
class KnoluxS3ConnectionDetailsTest {

    // ── toCacheKey() ─────────────────────────────────────────────────────────

    @Test
    void toCacheKey_withNullEndpoint_shouldNotProduceLiteralNullString() {
        var details = new KnoluxS3ConnectionDetails(
                null, "us-east-1", "key", "secret", true, false, "", false
        );
        assertThat(details.toCacheKey()).doesNotContain("null");
    }

    @Test
    void toCacheKey_withNullRegion_shouldNotProduceLiteralNullString() {
        var details = new KnoluxS3ConnectionDetails(
                "http://host:9000", null, "key", "secret", true, false, "", false
        );
        assertThat(details.toCacheKey()).doesNotContain("null");
    }

    @Test
    void toCacheKey_withNullPathPrefix_shouldNotProduceLiteralNullString() {
        var details = new KnoluxS3ConnectionDetails(
                "http://host:9000", "us-east-1", "key", "secret", true, false, null, false
        );
        assertThat(details.toCacheKey()).doesNotContain("null");
    }

    @Test
    void toCacheKey_withNullAccessKey_shouldNotProduceLiteralNullString() {
        var details = new KnoluxS3ConnectionDetails(
                "http://host:9000", "us-east-1", null, "secret", true, false, "", false
        );
        assertThat(details.toCacheKey()).doesNotContain("null");
    }

    @Test
    void toCacheKey_sameParams_shouldProduceSameKey() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", false);
        assertThat(a.toCacheKey()).isEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentEndpoints_shouldProduceDifferentKeys() {
        var a = new KnoluxS3ConnectionDetails("http://host-a:9000", "us-east-1", "k", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host-b:9000", "us-east-1", "k", "s", true, false, "", false);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentRegions_shouldProduceDifferentKeys() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "ap-northeast-1", "k", "s", true, false, "", false);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentForcePathStyle_shouldProduceDifferentKeys() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", false, false, "", false);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentPathPrefix_shouldProduceDifferentKeys() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, true, "/cluster/s3", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, true, "/other/path", false);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentTrustSelfSigned_shouldProduceDifferentKeys() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "k", "s", true, false, "", true);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_doesNotContainSecretKey() {
        var details = new KnoluxS3ConnectionDetails(
                "http://host:9000", "us-east-1", "myAccessKey", "TOP_SECRET_PASSWORD", true, false, "", false
        );
        assertThat(details.toCacheKey()).doesNotContain("TOP_SECRET_PASSWORD");
    }

    @Test
    void toCacheKey_doesNotContainAccessKeyInPlaintext() {
        // accessKey 應以 SHA-256 hash 存入 cache key，不以明文出現
        var details = new KnoluxS3ConnectionDetails(
                "http://host:9000", "us-east-1", "PLAINTEXT_ACCESS_KEY", "s", true, false, "", false
        );
        assertThat(details.toCacheKey()).doesNotContain("PLAINTEXT_ACCESS_KEY");
    }

    @Test
    void toCacheKey_sameAccessKey_producesSameHash() {
        // 相同 accessKey 應產生相同 hash（SHA-256 具確定性）
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "same-key", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "same-key", "s", true, false, "", false);
        assertThat(a.toCacheKey()).isEqualTo(b.toCacheKey());
    }

    @Test
    void toCacheKey_differentAccessKeys_produceDifferentHashes() {
        var a = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "key-a", "s", true, false, "", false);
        var b = new KnoluxS3ConnectionDetails("http://host:9000", "us-east-1", "key-b", "s", true, false, "", false);
        assertThat(a.toCacheKey()).isNotEqualTo(b.toCacheKey());
    }

    // ── of(KnoluxS3Properties) 工廠方法 ──────────────────────────────────────

    @Test
    void of_shouldMapAllFieldsFromProperties() {
        var props = new KnoluxS3Properties();
        props.setEndpoint("http://seaweedfs:8333");
        props.setRegion("ap-northeast-1");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        props.setForcePathStyle(true);
        props.setRemovePathPrefix(true);
        props.setPathPrefix("/cluster/s3");
        props.setTrustSelfSigned(false);

        var details = KnoluxS3ConnectionDetails.of(props);

        assertThat(details.endpoint()).isEqualTo("http://seaweedfs:8333");
        assertThat(details.region()).isEqualTo("ap-northeast-1");
        assertThat(details.accessKey()).isEqualTo("ak");
        assertThat(details.secretKey()).isEqualTo("sk");
        assertThat(details.forcePathStyle()).isTrue();
        assertThat(details.removePathPrefix()).isTrue();
        assertThat(details.pathPrefix()).isEqualTo("/cluster/s3");
        assertThat(details.trustSelfSigned()).isFalse();
    }

    @Test
    void of_withNullEndpoint_shouldProduceDetailsWithNullEndpoint() {
        var props = new KnoluxS3Properties();
        props.setAccessKey("k");
        props.setSecretKey("s");
        // endpoint not set → null (AWS S3 mode)

        var details = KnoluxS3ConnectionDetails.of(props);

        assertThat(details.endpoint()).isNull();
    }
}
