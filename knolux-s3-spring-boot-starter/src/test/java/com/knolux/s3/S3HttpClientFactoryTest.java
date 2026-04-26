package com.knolux.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link S3HttpClientFactory} 的單元測試。
 *
 * <p>驗證在不同 {@code trustSelfSigned} 設定下，HTTP client 能正確建立。
 * 不發送實際 HTTP 請求，僅驗證物件建立與資源回收。
 */
class S3HttpClientFactoryTest {

    @Test
    void build_withoutTrustSelfSigned_returnsHttpClient() {
        KnoluxS3ConnectionDetails details = new KnoluxS3ConnectionDetails(
                "http://localhost:9000", "ap-northeast-1",
                "key", "secret",
                true, false, "", false  // trustSelfSigned=false
        );
        try (SdkAsyncHttpClient client = S3HttpClientFactory.build(details)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void build_withTrustSelfSigned_returnsHttpClient() {
        KnoluxS3ConnectionDetails details = new KnoluxS3ConnectionDetails(
                "https://s3.internal.local:9000", "ap-northeast-1",
                "key", "secret",
                true, false, "", true  // trustSelfSigned=true
        );
        try (SdkAsyncHttpClient client = S3HttpClientFactory.build(details)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void build_withNullEndpoint_returnsHttpClient() {
        // endpoint 為 null（標準 AWS S3 場景）
        KnoluxS3ConnectionDetails details = new KnoluxS3ConnectionDetails(
                null, "us-east-1",
                "key", "secret",
                false, false, "", false
        );
        try (SdkAsyncHttpClient client = S3HttpClientFactory.build(details)) {
            assertThat(client).isNotNull();
        }
    }
}
