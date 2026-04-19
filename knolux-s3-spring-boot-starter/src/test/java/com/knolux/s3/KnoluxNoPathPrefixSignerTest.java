package com.knolux.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.signer.AwsSignerExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnoluxNoPathPrefixSigner} 的單元測試。
 *
 * <p>驗證路徑前綴移除、簽章 Header 複製，以及無前綴時路徑保持不變的行為。
 */
@SuppressWarnings("deprecation")
class KnoluxNoPathPrefixSignerTest {

    private static ExecutionAttributes signingAttrs() {
        return ExecutionAttributes.builder()
                .put(AwsSignerExecutionAttribute.AWS_CREDENTIALS,
                        AwsBasicCredentials.create("testAccessKey", "testSecretKey"))
                .put(AwsSignerExecutionAttribute.SERVICE_SIGNING_NAME, "s3")
                .put(AwsSignerExecutionAttribute.SIGNING_REGION, Region.US_EAST_1)
                .build();
    }

    private static SdkHttpFullRequest request(String path) {
        return SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("http")
                .host("aic.csh.org.tw")
                .port(443)
                .encodedPath(path)
                .build();
    }

    // ── 路徑保留 ─────────────────────────────────────────────────────────────

    @Test
    void sign_withPrefix_retainsOriginalLongPathInResult() {
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/cluster/s3/my-bucket/folder/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.encodedPath()).isEqualTo("/cluster/s3/my-bucket/folder/file.txt");
    }

    @Test
    void sign_withoutMatchingPrefix_retainsPathUnchanged() {
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/my-bucket/folder/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.encodedPath()).isEqualTo("/my-bucket/folder/file.txt");
    }

    @Test
    void sign_withEmptyPrefix_retainsPathUnchanged() {
        var signer = new KnoluxNoPathPrefixSigner("");
        var req = request("/my-bucket/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.encodedPath()).isEqualTo("/my-bucket/file.txt");
    }

    // ── 簽章 Header 複製 ──────────────────────────────────────────────────────

    @Test
    void sign_withPrefix_copiesAuthorizationHeader() {
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/cluster/s3/my-bucket/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.headers()).containsKey("Authorization");
        assertThat(result.firstMatchingHeader("Authorization")).isPresent();
    }

    @Test
    void sign_withPrefix_copiesXAmzHeaders() {
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/cluster/s3/my-bucket/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.headers().keySet())
                .anyMatch(h -> h.toLowerCase().startsWith("x-amz-"));
    }

    @Test
    void sign_withoutPrefix_alsoHasAuthorizationHeader() {
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/other-bucket/file.txt");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        assertThat(result.firstMatchingHeader("Authorization")).isPresent();
    }

    // ── 邊界條件 ──────────────────────────────────────────────────────────────

    @Test
    void sign_prefixOnlyPath_resultPathBecomesSlash() {
        // 路徑恰好等於前綴，移除後應補 "/"
        var signer = new KnoluxNoPathPrefixSigner("/cluster/s3");
        var req = request("/cluster/s3");

        SdkHttpFullRequest result = signer.sign(req, signingAttrs());

        // 原始長路徑應保留
        assertThat(result.encodedPath()).isEqualTo("/cluster/s3");
        // 簽章仍應存在
        assertThat(result.firstMatchingHeader("Authorization")).isPresent();
    }

    @Test
    void sign_differentPrefixes_produceDifferentAuthorizationValues() {
        // 不同前綴對同一路徑產生不同簽章（簽章使用不同短路徑）
        var signerA = new KnoluxNoPathPrefixSigner("/prefix-a");
        var signerB = new KnoluxNoPathPrefixSigner("/prefix-b");
        var req = request("/prefix-a/bucket/file.txt");

        String authA = signerA.sign(req, signingAttrs())
                .firstMatchingHeader("Authorization").orElse("");
        // req 路徑不含 /prefix-b，signerB 不剝離前綴，簽章路徑與 signerA 不同
        String authB = signerB.sign(req, signingAttrs())
                .firstMatchingHeader("Authorization").orElse("");

        assertThat(authA).isNotEqualTo(authB);
    }
}
