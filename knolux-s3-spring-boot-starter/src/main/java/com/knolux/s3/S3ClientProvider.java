package com.knolux.s3;

import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * {@link S3AsyncClient} 提供者抽象介面，供 {@link KnoluxS3Template} 依賴。
 *
 * <p>預設實作為 {@link KnoluxS3ClientFactory}（具有連線快取機制）。
 * 使用者可實作此介面以替換 client 建立策略，例如：
 * <ul>
 *   <li>整合自訂連線池</li>
 *   <li>測試用 mock</li>
 *   <li>多區域路由策略</li>
 * </ul>
 *
 * <p>實作須擴充 {@link AutoCloseable}，確保 Netty EventLoopGroup 在 Spring 容器
 * 關閉時被正確釋放（{@code @Bean(destroyMethod = "close")} 已由
 * {@link KnoluxS3AutoConfiguration} 處理）。
 *
 * @see KnoluxS3ClientFactory
 * @see KnoluxS3Template
 */
public interface S3ClientProvider extends AutoCloseable {

    /**
     * 取得與指定連線參數對應的 {@link S3AsyncClient}。
     *
     * @param details 連線參數；{@code null} 代表使用預設連線（靜態模式）
     * @return 對應的 {@link S3AsyncClient}（可能來自快取，不可自行 close）
     */
    S3AsyncClient getClient(KnoluxS3ConnectionDetails details);

    /**
     * 使用預設連線取得 {@link S3AsyncClient}（靜態模式捷徑）。
     *
     * @return 預設連線的 {@link S3AsyncClient}
     */
    default S3AsyncClient getClient() {
        return getClient(null);
    }

    /**
     * 釋放所有 client 資源。可安全多次呼叫（冪等）。
     */
    @Override
    void close();
}
