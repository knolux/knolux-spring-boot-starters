package com.knolux.s3;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxS3Properties} 的設定屬性綁定測試。
 *
 * <p>驗證 {@code knolux.s3.*} 所有欄位能正確綁定，以及未設定時的預設值是否符合預期。
 * 使用最小化 {@link TestConfig}，不啟動 S3 連線。
 */
@SpringBootTest(
        classes = KnoluxS3PropertiesTest.TestConfig.class,
        properties = {
                "knolux.s3.endpoint=http://seaweedfs:8333",
                "knolux.s3.region=us-west-2",
                "knolux.s3.bucket=my-bucket",
                "knolux.s3.access-key=AKIATEST",
                "knolux.s3.secret-key=supersecret",
                "knolux.s3.force-path-style=false",
                "knolux.s3.remove-path-prefix=true",
                "knolux.s3.path-prefix=/cluster/s3",
                "knolux.s3.trust-self-signed=true"
        }
)
class KnoluxS3PropertiesTest {

    @Autowired
    KnoluxS3Properties properties;

    @Test
    void endpoint_shouldBeBound() {
        assertThat(properties.getEndpoint()).isEqualTo("http://seaweedfs:8333");
    }

    @Test
    void region_shouldBeBound() {
        assertThat(properties.getRegion()).isEqualTo("us-west-2");
    }

    @Test
    void bucket_shouldBeBound() {
        assertThat(properties.getBucket()).isEqualTo("my-bucket");
    }

    @Test
    void accessKey_shouldBeBound() {
        assertThat(properties.getAccessKey()).isEqualTo("AKIATEST");
    }

    @Test
    void secretKey_shouldBeBound() {
        assertThat(properties.getSecretKey()).isEqualTo("supersecret");
    }

    @Test
    void forcePathStyle_shouldBeBound() {
        assertThat(properties.isForcePathStyle()).isFalse();
    }

    @Test
    void removePathPrefix_shouldBeBound() {
        assertThat(properties.isRemovePathPrefix()).isTrue();
    }

    @Test
    void pathPrefix_shouldBeBound() {
        assertThat(properties.getPathPrefix()).isEqualTo("/cluster/s3");
    }

    @Test
    void trustSelfSigned_shouldBeBound() {
        assertThat(properties.isTrustSelfSigned()).isTrue();
    }

    @Test
    void defaultValues_shouldBeCorrect() {
        KnoluxS3Properties defaults = new KnoluxS3Properties();
        assertThat(defaults.getEndpoint()).isNull();
        assertThat(defaults.getRegion()).isEqualTo("ap-northeast-1");
        assertThat(defaults.getBucket()).isNull();
        assertThat(defaults.getAccessKey()).isNull();
        assertThat(defaults.getSecretKey()).isNull();
        assertThat(defaults.isForcePathStyle()).isTrue();
        assertThat(defaults.isRemovePathPrefix()).isFalse();
        assertThat(defaults.getPathPrefix()).isEmpty();
        assertThat(defaults.isTrustSelfSigned()).isFalse();
    }

    @EnableConfigurationProperties(KnoluxS3Properties.class)
    static class TestConfig {
    }
}
