package com.knolux.s3;

import lombok.Builder;
import lombok.Getter;

import java.util.Objects;

/**
 * 動態 S3 操作規格，用於執行期間從請求 Payload 組裝完整的 S3 操作參數。
 *
 * <h2>靜態 vs 動態模式</h2>
 * <ul>
 *   <li><strong>靜態模式</strong> — 直接注入 {@link KnoluxS3Template} 使用 Properties 預設值，
 *       無需建立此物件。</li>
 *   <li><strong>動態模式</strong> — 從 REST API body 或 Message Queue Payload 取得欄位，
 *       透過 {@link #builder()} 組裝，<em>必須</em>呼叫 {@link #mergeDefaults(KnoluxS3Properties)}
 *       填補 {@code null} 欄位並鎖定部署級別設定。</li>
 * </ul>
 *
 * <h2>欄位分類</h2>
 * <ul>
 *   <li><strong>動態欄位</strong>（{@code endpoint}、{@code region}、{@code accessKey}、
 *       {@code secretKey}、{@code bucket}、{@code key}）— payload 可覆寫；
 *       {@code null} 時由 {@link #mergeDefaults} 以 Properties 填補。</li>
 *   <li><strong>部署級別設定</strong>（{@code forcePathStyle}、{@code removePathPrefix}、
 *       {@code pathPrefix}、{@code trustSelfSigned}）— 一律以 Properties 為準，
 *       防止呼叫端透過 payload 繞過部署配置。</li>
 * </ul>
 *
 * <h2>MQ Handler 使用範例</h2>
 * <pre>{@code
 * KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
 *     .endpoint(message.getS3Endpoint())   // null 時 fallback 至 Properties
 *     .region(message.getS3Region())
 *     .accessKey(message.getSecretId())    // SeaweedFS 稱為 secretId
 *     .secretKey(message.getSecretKey())
 *     .bucket(message.getBucket())
 *     .key(message.getObjectKey())
 *     .build()
 *     .mergeDefaults(s3Properties);        // 必填；填補 null 並鎖定部署設定
 *
 * s3Template.download(spec, AsyncResponseTransformer.toBytes());
 * }</pre>
 *
 * <h2>REST API 使用範例</h2>
 * <pre>{@code
 * @PostMapping("/download")
 * public ResponseEntity<byte[]> download(@RequestBody S3DownloadRequest req) {
 *     KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
 *         .endpoint(req.endpoint())
 *         .bucket(req.bucket())
 *         .key(req.key())
 *         .accessKey(req.accessKey())
 *         .secretKey(req.secretKey())
 *         .build()
 *         .mergeDefaults(s3Properties);
 *
 *     byte[] content = s3Template.download(spec, AsyncResponseTransformer.toBytes())
 *         .join()
 *         .asByteArray();
 *     return ResponseEntity.ok(content);
 * }
 * }</pre>
 *
 * @see KnoluxS3Template
 * @see KnoluxS3Properties
 */
@Getter
@Builder
public final class KnoluxS3OperationSpec {

    // ── 動態欄位（從 payload 取得，null 表示使用 Properties 預設值）────────────────

    /**
     * S3 相容端點 URL。{@code null} 時使用 Properties 設定或 AWS 預設端點。
     */
    private final String endpoint;

    /**
     * AWS Region。{@code null} 時使用 Properties 設定。
     */
    private final String region;

    /**
     * S3 Access Key（SeaweedFS 稱為 Secret ID）。
     * {@code null} 時使用 Properties 設定；{@link #mergeDefaults} 後仍為 {@code null}
     * 代表 Properties 也未設定，會在 {@link KnoluxS3ClientFactory} 建立 client 時拋出例外。
     */
    private final String accessKey;

    /**
     * S3 Secret Key。
     * {@code null} 時使用 Properties 設定；行為同 {@link #accessKey}。
     */
    private final String secretKey;

    /**
     * Bucket 名稱。{@code null} 時使用 Properties 設定。
     * {@link #mergeDefaults} 後仍為 {@code null} 時，方法會拋出 {@link IllegalStateException}。
     */
    private final String bucket;

    /**
     * S3 Object Key（物件路徑）。不可為 {@code null} 或空白。
     */
    private final String key;

    // ── 部署級別設定（由 mergeDefaults 從 Properties 覆寫，不可由 payload 控制）───

    /**
     * 是否強制 Path Style 定址，{@link Builder} 預設 {@code true}。
     */
    @Builder.Default
    private final boolean forcePathStyle = true;

    /**
     * 是否移除路徑前綴再計算簽章（Nginx 代理場景），{@link Builder} 預設 {@code false}。
     */
    @Builder.Default
    private final boolean removePathPrefix = false;

    /**
     * 要移除的路徑前綴，{@code removePathPrefix=true} 時生效。
     */
    @Builder.Default
    private final String pathPrefix = "";

    /**
     * 是否信任自簽 TLS 憑證，{@link Builder} 預設 {@code false}。
     */
    @Builder.Default
    private final boolean trustSelfSigned = false;

    // ── 工廠方法 ──────────────────────────────────────────────────────────────────

    /**
     * 以靜態 {@link KnoluxS3Properties} 填補 {@code null} 欄位，回傳完整 spec。
     *
     * <p><strong>動態欄位規則：</strong>若 payload 已提供非 {@code null} 值則保留，
     * 否則以 {@code defaults} 對應欄位填補。
     *
     * <p><strong>部署級別設定規則：</strong>{@code forcePathStyle}、{@code removePathPrefix}、
     * {@code pathPrefix}、{@code trustSelfSigned} 一律以 {@code defaults} 為準，
     * 防止呼叫端透過 payload 覆寫部署設定（潛在安全風險）。
     *
     * @param defaults 注入的 {@link KnoluxS3Properties}，不可為 {@code null}
     * @return 所有欄位均已填滿的新 spec
     * @throws NullPointerException  若 {@code defaults} 為 {@code null}
     * @throws IllegalStateException 若 merge 後 {@code bucket} 或 {@code key} 仍為 {@code null}
     */
    public KnoluxS3OperationSpec mergeDefaults(KnoluxS3Properties defaults) {
        Objects.requireNonNull(defaults, "defaults 不可為 null");

        KnoluxS3OperationSpec merged = KnoluxS3OperationSpec.builder()
                .endpoint(endpoint != null ? endpoint : defaults.getEndpoint())
                .region(region != null ? region : defaults.getRegion())
                .accessKey(accessKey != null ? accessKey : defaults.getAccessKey())
                .secretKey(secretKey != null ? secretKey : defaults.getSecretKey())
                .bucket(bucket != null ? bucket : defaults.getBucket())
                .key(key)
                // 部署級別設定：一律從 Properties 取得
                .forcePathStyle(defaults.isForcePathStyle())
                .removePathPrefix(defaults.isRemovePathPrefix())
                .pathPrefix(defaults.getPathPrefix())
                .trustSelfSigned(defaults.isTrustSelfSigned())
                .build();

        if (merged.getBucket() == null) {
            throw new IllegalStateException(
                    "bucket 為 null：請在 OperationSpec.builder().bucket(...) 或 knolux.s3.bucket 中設定");
        }
        if (merged.getKey() == null) {
            throw new IllegalStateException(
                    "key 為 null：S3 object key 不可為空，請在 OperationSpec.builder().key(...) 中設定");
        }
        return merged;
    }

    /**
     * 轉換為 {@link KnoluxS3ConnectionDetails}，供 {@link KnoluxS3ClientFactory} 建立 client 使用。
     * bucket 與 key 屬操作層級欄位，不包含在連線參數中。
     */
    KnoluxS3ConnectionDetails toConnectionDetails() {
        return new KnoluxS3ConnectionDetails(
                endpoint, region, accessKey, secretKey,
                forcePathStyle, removePathPrefix, pathPrefix, trustSelfSigned
        );
    }
}
