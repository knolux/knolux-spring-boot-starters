package com.knolux.s3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link KnoluxS3OperationSpec} 的單元測試。
 *
 * <p>驗證 builder、{@link KnoluxS3OperationSpec#mergeDefaults(KnoluxS3Properties)} 的 fallback 邏輯，
 * 以及部署級別設定是否正確被 Properties 鎖定（不允許 payload 覆寫）。
 */
class KnoluxS3OperationSpecTest {

    private static KnoluxS3Properties fullProperties() {
        var props = new KnoluxS3Properties();
        props.setEndpoint("http://default-host:9000");
        props.setRegion("ap-northeast-1");
        props.setAccessKey("default-key");
        props.setSecretKey("default-secret");
        props.setBucket("default-bucket");
        props.setForcePathStyle(true);
        props.setRemovePathPrefix(false);
        props.setPathPrefix("");
        props.setTrustSelfSigned(false);
        return props;
    }

    // ── mergeDefaults：null 欄位以 Properties 填補 ────────────────────────────

    @Test
    void mergeDefaults_nullEndpoint_shouldFallbackToProperties() {
        var spec = KnoluxS3OperationSpec.builder()
                .endpoint(null)
                .accessKey("ak")
                .secretKey("sk")
                .bucket("bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getEndpoint()).isEqualTo("http://default-host:9000");
    }

    @Test
    void mergeDefaults_nullRegion_shouldFallbackToProperties() {
        var spec = KnoluxS3OperationSpec.builder()
                .endpoint("http://override:9000")
                .region(null)
                .accessKey("ak")
                .secretKey("sk")
                .bucket("bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getRegion()).isEqualTo("ap-northeast-1");
    }

    @Test
    void mergeDefaults_nullCredentials_shouldFallbackToProperties() {
        var spec = KnoluxS3OperationSpec.builder()
                .bucket("bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getAccessKey()).isEqualTo("default-key");
        assertThat(spec.getSecretKey()).isEqualTo("default-secret");
    }

    @Test
    void mergeDefaults_nullBucket_shouldFallbackToProperties() {
        var spec = KnoluxS3OperationSpec.builder()
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getBucket()).isEqualTo("default-bucket");
    }

    // ── mergeDefaults：非 null 欄位保留 payload 值 ───────────────────────────

    @Test
    void mergeDefaults_nonNullEndpoint_shouldRetainPayloadValue() {
        var spec = KnoluxS3OperationSpec.builder()
                .endpoint("http://payload-host:8333")
                .accessKey("ak")
                .secretKey("sk")
                .bucket("bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getEndpoint()).isEqualTo("http://payload-host:8333");
    }

    @Test
    void mergeDefaults_nonNullCredentials_shouldRetainPayloadValues() {
        var spec = KnoluxS3OperationSpec.builder()
                .accessKey("payload-key")
                .secretKey("payload-secret")
                .bucket("bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getAccessKey()).isEqualTo("payload-key");
        assertThat(spec.getSecretKey()).isEqualTo("payload-secret");
    }

    @Test
    void mergeDefaults_nonNullBucket_shouldRetainPayloadValue() {
        var spec = KnoluxS3OperationSpec.builder()
                .bucket("payload-bucket")
                .key("key.txt")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getBucket()).isEqualTo("payload-bucket");
    }

    @Test
    void mergeDefaults_key_shouldAlwaysRetainPayloadValue() {
        var spec = KnoluxS3OperationSpec.builder()
                .key("path/to/file.pdf")
                .build()
                .mergeDefaults(fullProperties());

        assertThat(spec.getKey()).isEqualTo("path/to/file.pdf");
    }

    // ── 部署級別設定：一律從 Properties 取得 ──────────────────────────────────

    @Test
    void mergeDefaults_deploymentSettings_alwaysComeFromProperties() {
        var nginxProps = new KnoluxS3Properties();
        nginxProps.setEndpoint("https://aic.csh.org.tw");
        nginxProps.setAccessKey("k");
        nginxProps.setSecretKey("s");
        nginxProps.setForcePathStyle(true);
        nginxProps.setRemovePathPrefix(true);
        nginxProps.setPathPrefix("/cluster/s3");
        nginxProps.setTrustSelfSigned(false);

        // payload 不提供部署級別設定
        var spec = KnoluxS3OperationSpec.builder()
                .bucket("bucket")
                .key("file.txt")
                .build()
                .mergeDefaults(nginxProps);

        assertThat(spec.isForcePathStyle()).isTrue();
        assertThat(spec.isRemovePathPrefix()).isTrue();
        assertThat(spec.getPathPrefix()).isEqualTo("/cluster/s3");
        assertThat(spec.isTrustSelfSigned()).isFalse();
    }

    @Test
    void mergeDefaults_trustSelfSignedFromProperties_overridesBuilderDefault() {
        var selfSignedProps = new KnoluxS3Properties();
        selfSignedProps.setEndpoint("https://s3.aic.org.tw");
        selfSignedProps.setAccessKey("k");
        selfSignedProps.setSecretKey("s");
        selfSignedProps.setTrustSelfSigned(true);

        var spec = KnoluxS3OperationSpec.builder()
                .bucket("bucket")
                .key("file.txt")
                // @Builder.Default 為 false，但 mergeDefaults 應覆寫
                .build()
                .mergeDefaults(selfSignedProps);

        assertThat(spec.isTrustSelfSigned()).isTrue();
    }

    // ── mergeDefaults：null 驗證 ─────────────────────────────────────────────

    @Test
    void mergeDefaults_nullDefaults_shouldThrowNullPointer() {
        var spec = KnoluxS3OperationSpec.builder().bucket("b").key("k").build();

        assertThatThrownBy(() -> spec.mergeDefaults(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void mergeDefaults_nullBucketInBothSpecAndProperties_shouldThrowIllegalState() {
        var props = new KnoluxS3Properties();  // bucket 預設為 null
        var spec = KnoluxS3OperationSpec.builder()
                .key("key.txt")
                // bucket 未設定
                .build();

        assertThatThrownBy(() -> spec.mergeDefaults(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void mergeDefaults_nullKey_shouldThrowIllegalState() {
        var spec = KnoluxS3OperationSpec.builder()
                .bucket("my-bucket")
                // key 未設定（null）
                .build();

        assertThatThrownBy(() -> spec.mergeDefaults(fullProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("key");
    }

    // ── toConnectionDetails() 轉換 ────────────────────────────────────────────

    @Test
    void toConnectionDetails_shouldMapAllFields() {
        var spec = KnoluxS3OperationSpec.builder()
                .endpoint("http://host:9000")
                .region("us-east-1")
                .accessKey("ak")
                .secretKey("sk")
                .bucket("bucket")
                .key("file.txt")
                .forcePathStyle(true)
                .removePathPrefix(true)
                .pathPrefix("/cluster/s3")
                .trustSelfSigned(false)
                .build();

        KnoluxS3ConnectionDetails details = spec.toConnectionDetails();

        assertThat(details.endpoint()).isEqualTo("http://host:9000");
        assertThat(details.region()).isEqualTo("us-east-1");
        assertThat(details.accessKey()).isEqualTo("ak");
        assertThat(details.secretKey()).isEqualTo("sk");
        assertThat(details.forcePathStyle()).isTrue();
        assertThat(details.removePathPrefix()).isTrue();
        assertThat(details.pathPrefix()).isEqualTo("/cluster/s3");
        assertThat(details.trustSelfSigned()).isFalse();
    }

    @Test
    void toConnectionDetails_doesNotIncludeBucketOrKey() {
        // bucket/key 是操作層級欄位，不應出現在 ConnectionDetails 中
        var spec = KnoluxS3OperationSpec.builder()
                .endpoint("http://host:9000")
                .accessKey("ak")
                .secretKey("sk")
                .bucket("my-bucket")
                .key("path/to/file.txt")
                .build();

        KnoluxS3ConnectionDetails details = spec.toConnectionDetails();

        // ConnectionDetails 僅含連線相關欄位（用 toCacheKey 間接驗證）
        assertThat(details.toCacheKey()).doesNotContain("my-bucket");
        assertThat(details.toCacheKey()).doesNotContain("path/to/file.txt");
    }
}
