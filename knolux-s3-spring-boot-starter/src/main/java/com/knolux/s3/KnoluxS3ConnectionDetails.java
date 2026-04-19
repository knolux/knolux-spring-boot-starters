package com.knolux.s3;

/**
 * S3 連線參數的不可變值物件，作為 {@link KnoluxS3ClientFactory} 建立與快取 client 的鍵。
 *
 * <p>可從靜態 {@link KnoluxS3Properties} 建立，也可由 {@link KnoluxS3OperationSpec#toConnectionDetails()}
 * 在執行期間從請求 payload 動態組裝。
 *
 * <h2>快取鍵安全性</h2>
 * <p>{@link #toCacheKey()} 中的 {@code accessKey} 以 SHA-256 摘要取代明文，
 * {@code secretKey} 則完全不納入，防止憑證以明文出現在 heap 中的 Map key 字串、
 * 日誌或 heap dump。
 *
 * @see KnoluxS3ClientFactory
 * @see KnoluxS3OperationSpec
 */
public record KnoluxS3ConnectionDetails(
        String endpoint,
        String region,
        String accessKey,
        String secretKey,
        boolean forcePathStyle,
        boolean removePathPrefix,
        String pathPrefix,
        boolean trustSelfSigned
) {

    /**
     * 從靜態 {@link KnoluxS3Properties} 建立連線參數（靜態模式工廠方法）。
     *
     * @param props 已綁定的設定屬性
     * @return 對應的連線參數
     */
    public static KnoluxS3ConnectionDetails of(KnoluxS3Properties props) {
        return new KnoluxS3ConnectionDetails(
                props.getEndpoint(),
                props.getRegion(),
                props.getAccessKey(),
                props.getSecretKey(),
                props.isForcePathStyle(),
                props.isRemovePathPrefix(),
                props.getPathPrefix(),
                props.isTrustSelfSigned()
        );
    }

    /**
     * 產生 {@link KnoluxS3ClientFactory} 使用的 client 快取鍵。
     *
     * <ul>
     *   <li>{@code secretKey} 完全不納入，防止洩漏。</li>
     *   <li>{@code accessKey} 以 SHA-256 摘要（hex）代替明文，避免憑證以字串形式存入 Map key。</li>
     *   <li>所有可為 {@code null} 的 {@link String} 欄位以空字串代入，
     *       防止不同欄位為 null 時產生相同 key。</li>
     * </ul>
     *
     * @return 長度固定、不含敏感明文的快取鍵字串
     */
    public String toCacheKey() {
        return "%s|%s|%s|%b|%b|%s|%b".formatted(
                endpoint != null ? endpoint : "",
                region != null ? region : "",
                CacheKeys.sha256Hex(accessKey),
                forcePathStyle, removePathPrefix,
                pathPrefix != null ? pathPrefix : "",
                trustSelfSigned
        );
    }

}
