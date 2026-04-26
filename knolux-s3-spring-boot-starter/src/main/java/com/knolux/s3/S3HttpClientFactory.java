package com.knolux.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.utils.AttributeMap;

import java.time.Duration;

/**
 * Netty 非同步 HTTP client 工廠（package-private）。
 *
 * <p>將 HTTP client 建立邏輯從 {@link KnoluxS3ClientFactory} 中分離，
 * 使 HTTP client 配置可獨立測試、替換、調整，符合單一職責原則（SRP）。
 *
 * <h2>支援模式</h2>
 * <ul>
 *   <li>標準模式 — 啟用完整 TLS 憑證鏈與 hostname 驗證</li>
 *   <li>{@code trustSelfSigned=true} 模式 — 停用 TLS 驗證，僅限非正式環境</li>
 * </ul>
 *
 * <h2>HTTP 連線參數</h2>
 * <ul>
 *   <li>{@code maxConcurrency=20} — Netty event loop 最大同時連線數</li>
 *   <li>{@code connectionTimeout=5s} — TCP 連線建立逾時</li>
 *   <li>{@code readTimeout=30s} — 單一回應讀取逾時（較長以容納大檔下載）</li>
 * </ul>
 *
 * @see KnoluxS3ClientFactory
 */
@Slf4j
final class S3HttpClientFactory {

    private S3HttpClientFactory() {
    }

    /**
     * 依連線參數建立 Netty 非同步 HTTP client。
     *
     * @param details 連線參數，{@code trustSelfSigned} 決定 TLS 驗證行為
     * @return 設定完成的 {@link SdkAsyncHttpClient}，呼叫方負責關閉
     */
    static SdkAsyncHttpClient build(KnoluxS3ConnectionDetails details) {
        var builder = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(20)
                .connectionTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30));

        if (details.trustSelfSigned()) {
            log.warn("[安全警告] trustSelfSigned=true：TLS 憑證驗證已停用（含 hostname 驗證），" +
                    "僅限非正式環境使用！endpoint={}", details.endpoint());
            return builder.buildWithDefaults(
                    AttributeMap.builder()
                            .put(SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES, Boolean.TRUE)
                            .build()
            );
        }
        return builder.build();
    }
}
