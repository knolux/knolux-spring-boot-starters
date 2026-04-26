package com.knolux.s3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 針對真實 SeaweedFS 端點的手動驗收測試。
 *
 * <p>此類別標記為 {@code @Disabled}，不會在 CI 自動執行。
 * 使用前，透過環境變數傳入憑證（不寫入程式碼）：
 * <pre>
 *   export S3_ACCESS_KEY=your_access_key
 *   export S3_SECRET_KEY=your_secret_key
 * </pre>
 * 或在 IDE Run Configuration 的 Environment Variables 欄位設定。
 * 接著對單一 {@code @Test} 方法按右鍵「Run」即可。
 */
@Disabled("手動驗收測試 — 需要連線至真實 SeaweedFS，請逐一執行")
class KnoluxS3RealEndpointTest {

    // ── 憑證從環境變數讀取，不寫入 code ───────────────────────────────────────
    private static final String ACCESS_KEY = System.getenv("S3_ACCESS_KEY");
    private static final String SECRET_KEY = System.getenv("S3_SECRET_KEY");
    private static final String BUCKET = "test";
    private static final String CONTENT = "Hello from KnoluxS3Template!";
    // ──────────────────────────────────────────────────────────────────────────

    private KnoluxS3ClientFactory factory;
    private KnoluxS3Template template;

    private static void assumeHostReachable(String hostname) {
        try {
            java.net.InetAddress.getByName(hostname);
        } catch (java.net.UnknownHostException e) {
            Assumptions.abort("DNS 無法解析 " + hostname + "，此測試需在 K8s 叢集內執行");
        }
    }

    // ── 情境一：外部網路 → Nginx 反向代理（正式 CA 憑證，路徑前綴 /cluster/s3）─

    @AfterEach
    void closeFactory() {
        if (factory != null) {
            factory.close();
            factory = null;
        }
    }

    // ── 情境二：公司內網 → 自簽署 HTTPS ─────────────────────────────────────

    @Test
    void external_nginx_proxy_upload_download_roundTrip() {
        // 對應 application.yml:
        //   knolux.s3.endpoint: https://aic.csh.org.tw
        //   knolux.s3.remove-path-prefix: true
        //   knolux.s3.path-prefix: /cluster/s3
        //   knolux.s3.force-path-style: true
        //   knolux.s3.trust-self-signed: false
        var props = new KnoluxS3Properties();
        props.setEndpoint("https://aic.csh.org.tw");
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setForcePathStyle(true);
        props.setRemovePathPrefix(true);
        props.setPathPrefix("/cluster/s3");
        props.setTrustSelfSigned(false);

        init(props);
        roundTrip("external-nginx");
    }

    // ── 情境三：K8s 集群內部 → 明文 HTTP ────────────────────────────────────

    @Test
    void internal_self_signed_https_upload_download_roundTrip() {
        // 對應 application.yml:
        //   knolux.s3.endpoint: https://s3.aic.org.tw
        //   knolux.s3.trust-self-signed: true
        //   knolux.s3.force-path-style: true
        var props = new KnoluxS3Properties();
        props.setEndpoint("https://s3.aic.org.tw");
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setForcePathStyle(true);
        props.setTrustSelfSigned(true);

        init(props);
        roundTrip("internal-self-signed");
    }

    // ── 動態模式（OperationSpec）— 以外部 Nginx 為例 ─────────────────────────

    @Test
    void k8s_internal_http_upload_download_roundTrip() {
        // 對應 application.yml:
        //   knolux.s3.endpoint: http://seaweedfs-s3.seaweedfs.svc.cluster.local:8333
        //   knolux.s3.force-path-style: true
        //   knolux.s3.trust-self-signed: false
        // 此測試僅限 K8s Pod 內部執行；在叢集外 DNS 無法解析時自動 skip。
        assumeHostReachable("seaweedfs-s3.seaweedfs.svc.cluster.local");

        var props = new KnoluxS3Properties();
        props.setEndpoint("http://seaweedfs-s3.seaweedfs.svc.cluster.local:8333");
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setForcePathStyle(true);
        props.setTrustSelfSigned(false);

        init(props);
        roundTrip("k8s-internal");
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    @Test
    void external_nginx_proxy_dynamicMode_operationSpec_roundTrip() {
        var props = new KnoluxS3Properties();
        props.setEndpoint("https://aic.csh.org.tw");
        props.setAccessKey(ACCESS_KEY);
        props.setSecretKey(SECRET_KEY);
        props.setForcePathStyle(true);
        props.setRemovePathPrefix(true);
        props.setPathPrefix("/cluster/s3");
        props.setTrustSelfSigned(false);

        init(props);

        // 模擬 MQ/REST payload 提供 endpoint + 憑證
        KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
                .endpoint("https://aic.csh.org.tw")   // payload 攜帶，null 則 fallback Properties
                .accessKey(ACCESS_KEY)
                .secretKey(SECRET_KEY)
                .bucket(BUCKET)
                .key("knolux-s3-test/dynamic-spec.txt")
                .build()
                .mergeDefaults(props);  // 鎖定部署級別設定（forcePathStyle, pathPrefix 等）

        template.upload(spec, AsyncRequestBody.fromString(CONTENT)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(spec, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asUtf8String()).isEqualTo(CONTENT);

        template.delete(spec).join();
    }

    private void init(KnoluxS3Properties props) {
        factory = new KnoluxS3ClientFactory(KnoluxS3ConnectionDetails.of(props));
        template = new KnoluxS3Template(factory);
    }

    private void roundTrip(String testPrefix) {
        String key = "knolux-s3-test/" + testPrefix + ".txt";

        template.upload(BUCKET, key, AsyncRequestBody.fromString(CONTENT)).join();

        ResponseBytes<GetObjectResponse> result =
                template.download(BUCKET, key, AsyncResponseTransformer.toBytes()).join();
        assertThat(result.asUtf8String()).isEqualTo(CONTENT);

        template.delete(BUCKET, key).join();
    }
}
