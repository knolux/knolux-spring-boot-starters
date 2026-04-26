package com.knolux.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link S3AsyncClient} 工廠，以 {@link KnoluxS3ConnectionDetails} 的快取鍵管理 client 實例。
 *
 * <h2>快取策略</h2>
 * <p>以 {@link KnoluxS3ConnectionDetails#toCacheKey()} 為鍵，相同連線參數共用同一個
 * {@code S3AsyncClient} 實例，避免重複建立 Netty EventLoopGroup。
 * HTTP client 與 S3AsyncClient 分開快取，以便 {@link #close()} 能正確釋放所有資源。
 *
 * <h2>支援情境</h2>
 * <ul>
 *   <li><strong>K8s 內部直連（HTTP）</strong> — 直接對 SeaweedFS 發送請求</li>
 *   <li><strong>自簽 HTTPS</strong> — {@code trustSelfSigned=true} 略過憑證鏈驗證（非正式環境）</li>
 *   <li><strong>Nginx 反向代理</strong> — {@code removePathPrefix=true}；
 *       {@code pathPrefix} 附加至 endpoint URI，使 Nginx 可正確 route；
 *       {@link KnoluxNoPathPrefixSigner} 在計算簽章時移除前綴</li>
 *   <li><strong>標準 AWS S3</strong> — {@code endpoint} 留空、{@code forcePathStyle=false}</li>
 * </ul>
 *
 * <h2>生命週期</h2>
 * <p>本類別實作 {@link S3ClientProvider}（{@link AutoCloseable} 的子介面），
 * Spring 容器關閉時透過 {@code @Bean(destroyMethod = "close")} 自動呼叫 {@link #close()}，
 * 依序釋放 S3AsyncClient 再釋放 Netty HTTP client。
 * 注意：{@link #close()} 不等待進行中的非同步 I/O 完成；
 * 呼叫方應在關閉前確認所有 {@link java.util.concurrent.CompletableFuture} 已完成或取消。
 *
 * @see S3ClientProvider
 * @see KnoluxS3Template
 * @see KnoluxNoPathPrefixSigner
 */
@Slf4j
public class KnoluxS3ClientFactory implements S3ClientProvider {

    private final KnoluxS3ConnectionDetails defaultDetails;
    private final Map<String, S3AsyncClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, SdkAsyncHttpClient> httpClientCache = new ConcurrentHashMap<>();

    /**
     * @param defaultDetails 靜態模式（{@link #getClient()}）使用的預設連線參數
     */
    public KnoluxS3ClientFactory(KnoluxS3ConnectionDetails defaultDetails) {
        this.defaultDetails = defaultDetails;
    }

    /**
     * 取得與指定連線參數對應的 {@link S3AsyncClient}。
     *
     * <p>相同 {@link KnoluxS3ConnectionDetails#toCacheKey()} 的參數會回傳同一個快取實例。
     * {@code details} 為 {@code null} 時使用建構時注入的預設連線（靜態模式）。
     *
     * @param details 連線參數；{@code null} 代表使用預設連線
     * @return 對應的 {@link S3AsyncClient}（已快取，不可自行 close）
     * @throws IllegalStateException 若 {@code accessKey} 或 {@code secretKey} 為 {@code null}
     */
    public S3AsyncClient getClient(KnoluxS3ConnectionDetails details) {
        KnoluxS3ConnectionDetails target = details != null ? details : defaultDetails;
        return clientCache.computeIfAbsent(target.toCacheKey(), _ -> buildClient(target));
    }

    /**
     * 使用預設連線取得 {@link S3AsyncClient}（靜態模式捷徑）。
     *
     * @return 預設連線的 {@link S3AsyncClient}
     */
    public S3AsyncClient getClient() {
        return getClient(null);
    }

    /**
     * 建立新的 {@link S3AsyncClient}，並將對應的 HTTP client 放入 {@code httpClientCache}。
     *
     * <p>HTTP client 在 {@code S3AsyncClient.build()} <em>之前</em>放入快取，
     * 確保 {@link #close()} 無論在哪個時間點呼叫都能完整回收 HTTP client。
     * 若 {@code S3AsyncClient.build()} 拋出例外，HTTP client 會從快取移除並立即關閉。
     */
    private S3AsyncClient buildClient(KnoluxS3ConnectionDetails d) {
        if (d.accessKey() == null || d.secretKey() == null) {
            throw new IllegalStateException(
                    "accessKey / secretKey 為 null，動態模式請確認已呼叫 KnoluxS3OperationSpec.mergeDefaults()");
        }

        SdkAsyncHttpClient httpClient = S3HttpClientFactory.build(d);

        // HTTP client 先放入 cache，確保 close() 可完整回收。
        // 若 S3AsyncClient 建立失敗，從 cache 移除並立即關閉，防止洩漏。
        httpClientCache.put(d.toCacheKey(), httpClient);
        try {
            var s3Builder = S3AsyncClient.builder()
                    .httpClient(httpClient)
                    .region(Region.of(d.region()))
                    .forcePathStyle(d.forcePathStyle())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(d.accessKey(), d.secretKey())
                    ));

            // endpoint 為空時使用 AWS SDK 預設端點（標準 AWS S3 場景）。
            // Nginx 代理場景：pathPrefix 附加至 endpoint，讓 SDK 建出含前綴的完整 URL，
            // 使 Nginx 可正確 route；KnoluxNoPathPrefixSigner 在計算簽章時移除前綴。
            if (d.endpoint() != null && !d.endpoint().isBlank()) {
                String effectiveEndpoint = d.removePathPrefix() && !d.pathPrefix().isBlank()
                        ? d.endpoint() + d.pathPrefix()
                        : d.endpoint();
                s3Builder.endpointOverride(URI.create(effectiveEndpoint));
            }

            // Nginx 代理場景：注入自訂簽章器以移除前綴後再計算 AWS 簽章。
            // 注意：SdkAdvancedClientOption.SIGNER 已標記為 deprecated，
            // 但在 AWS SDK v2 的 async pipeline 中仍透過 SigningStage 同步執行，
            // 因此對 S3AsyncClient 依然有效。遷移至 HttpSigner SPI 的工作已列入待辦。
            if (d.removePathPrefix() && !d.pathPrefix().isBlank()) {
                s3Builder.overrideConfiguration(conf -> conf.putAdvancedOption(
                        SdkAdvancedClientOption.SIGNER,
                        new KnoluxNoPathPrefixSigner(d.pathPrefix())
                ));
            }

            log.debug("建立 S3AsyncClient: endpoint={}, region={}, forcePathStyle={}, removePathPrefix={}, trustSelfSigned={}",
                    d.endpoint(), d.region(), d.forcePathStyle(), d.removePathPrefix(), d.trustSelfSigned());
            return s3Builder.build();

        } catch (Exception e) {
            httpClientCache.remove(d.toCacheKey());
            httpClient.close();
            throw e;
        }
    }

    /**
     * 關閉所有快取的 {@link S3AsyncClient} 與 Netty HTTP client，釋放執行緒資源。
     *
     * <p>可安全多次呼叫（冪等）。關閉順序：S3AsyncClient 先、HTTP client 後。
     * 注意：此方法不等待進行中的 I/O 完成，未完成的 {@link java.util.concurrent.CompletableFuture}
     * 可能收到 {@link java.nio.channels.ClosedChannelException}。
     */
    @Override
    public void close() {
        log.info("關閉 KnoluxS3ClientFactory，釋放所有 S3AsyncClient 與 HTTP client");
        clientCache.values().forEach(S3AsyncClient::close);
        clientCache.clear();
        httpClientCache.values().forEach(SdkAsyncHttpClient::close);
        httpClientCache.clear();
    }
}
