package com.knolux.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * S3 非同步操作模板，封裝 Upload、Download、Delete。
 *
 * <p>提供三層 API 供不同場景使用：
 *
 * <h2>靜態模式（Static）</h2>
 * <p>使用 {@code application.yml} 設定的預設連線，適合固定部署環境：
 * <pre>{@code
 * s3Template.upload("my-bucket", "path/to/file.jpg", body);
 * s3Template.download("my-bucket", "path/to/file.jpg", AsyncResponseTransformer.toBytes());
 * }</pre>
 *
 * <h2>動態模式（Dynamic）</h2>
 * <p>從請求 Payload 組裝 {@link KnoluxS3OperationSpec}，適合 REST API 或 Message Queue：
 * <pre>{@code
 * KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
 *     .endpoint(message.getS3Endpoint())   // null 時 fallback 至 Properties
 *     .accessKey(message.getSecretId())
 *     .secretKey(message.getSecretKey())
 *     .bucket(message.getBucket())
 *     .key(message.getObjectKey())
 *     .build()
 *     .mergeDefaults(s3Properties);
 *
 * s3Template.download(spec, AsyncResponseTransformer.toBytes());
 * }</pre>
 *
 * <p>由 starter 自動裝配的 {@code KnoluxS3Template} 會自動套用 {@code mergeDefaults}，
 * 部署級別設定（forcePathStyle / removePathPrefix / pathPrefix / trustSelfSigned）一律以
 * {@link KnoluxS3Properties} 為準；呼叫端即使省略 {@code mergeDefaults} 也無法以 payload 覆寫。
 * （自行 {@code new KnoluxS3Template(...)} 而未傳入 Properties 時，仍須自行呼叫 {@code mergeDefaults}。）
 *
 * <h2>進階模式（Advanced）</h2>
 * <p>明確指定 {@link KnoluxS3ConnectionDetails}，適合需要精細控制連線的場景：
 * <pre>{@code
 * s3Template.upload("bucket", "key", body, customConnectionDetails);
 * }</pre>
 *
 * @see KnoluxS3OperationSpec
 * @see S3ClientProvider
 */
@Slf4j
public class KnoluxS3Template {

    private final S3ClientProvider clientProvider;
    private final Executor continuationExecutor;

    /**
     * 動態模式的 fallback 預設值來源；可為 {@code null}。
     *
     * <p>非 {@code null} 時，{@link #upload(KnoluxS3OperationSpec, AsyncRequestBody)} 等動態模式方法
     * 會自動對傳入的 {@link KnoluxS3OperationSpec} 套用 {@link KnoluxS3OperationSpec#mergeDefaults}，
     * 使部署級別設定一律以 Properties 為準（安全邊界）。由 starter 自動裝配時注入此值。
     */
    private final KnoluxS3Properties properties;

    /**
     * 完整建構子。
     *
     * @param clientProvider       S3 client 提供者
     * @param continuationExecutor 用於執行 {@link CompletableFuture#whenCompleteAsync} 回呼的 Executor
     * @param properties           動態模式自動套用 {@link KnoluxS3OperationSpec#mergeDefaults} 的預設來源；
     *                             {@code null} 表示不自動套用（呼叫端須自行 {@code mergeDefaults}）
     */
    public KnoluxS3Template(S3ClientProvider clientProvider, Executor continuationExecutor,
                            KnoluxS3Properties properties) {
        this.clientProvider = clientProvider;
        this.continuationExecutor = continuationExecutor;
        this.properties = properties;
    }

    /**
     * 建構子（不提供 Properties）。動態模式須由呼叫端自行呼叫
     * {@link KnoluxS3OperationSpec#mergeDefaults(KnoluxS3Properties)}。
     *
     * @param clientProvider       S3 client 提供者
     * @param continuationExecutor 用於執行 {@link CompletableFuture#whenCompleteAsync} 回呼的 Executor
     */
    public KnoluxS3Template(S3ClientProvider clientProvider, Executor continuationExecutor) {
        this(clientProvider, continuationExecutor, null);
    }

    /**
     * 向下兼容建構子，使用 {@link ForkJoinPool#commonPool()} 作為預設 executor，且不提供 Properties。
     *
     * @param clientProvider S3 client 提供者
     */
    public KnoluxS3Template(S3ClientProvider clientProvider) {
        this(clientProvider, ForkJoinPool.commonPool(), null);
    }

    // ── 靜態模式（Properties 預設連線）──────────────────────────────────────────

    public CompletableFuture<PutObjectResponse> upload(
            String bucket, String key, AsyncRequestBody body) {
        return upload(bucket, key, body, (KnoluxS3ConnectionDetails) null);
    }

    public <T> CompletableFuture<T> download(
            String bucket, String key,
            AsyncResponseTransformer<GetObjectResponse, T> transformer) {
        return download(bucket, key, transformer, (KnoluxS3ConnectionDetails) null);
    }

    public CompletableFuture<DeleteObjectResponse> delete(String bucket, String key) {
        return delete(bucket, key, (KnoluxS3ConnectionDetails) null);
    }

    // ── 動態模式（OperationSpec 整合 payload 連線 + bucket + key）──────────────

    public CompletableFuture<PutObjectResponse> upload(
            KnoluxS3OperationSpec spec, AsyncRequestBody body) {
        KnoluxS3OperationSpec effective = applyDefaults(spec);
        return upload(effective.getBucket(), effective.getKey(), body, effective.toConnectionDetails());
    }

    public <T> CompletableFuture<T> download(
            KnoluxS3OperationSpec spec,
            AsyncResponseTransformer<GetObjectResponse, T> transformer) {
        KnoluxS3OperationSpec effective = applyDefaults(spec);
        return download(effective.getBucket(), effective.getKey(), transformer, effective.toConnectionDetails());
    }

    public CompletableFuture<DeleteObjectResponse> delete(KnoluxS3OperationSpec spec) {
        KnoluxS3OperationSpec effective = applyDefaults(spec);
        return delete(effective.getBucket(), effective.getKey(), effective.toConnectionDetails());
    }

    /**
     * 動態模式安全邊界：若本 template 持有 {@link KnoluxS3Properties}（starter 自動裝配時注入），
     * 一律套用 {@link KnoluxS3OperationSpec#mergeDefaults}，使部署級別設定
     * （forcePathStyle / removePathPrefix / pathPrefix / trustSelfSigned）只來自 Properties，
     * 即使呼叫端忘記呼叫 {@code mergeDefaults} 也無法以 payload 覆寫（重複套用為冪等）。
     * {@code properties} 為 {@code null}（自行建構且未提供）時維持原狀，由呼叫端負責先行 {@code mergeDefaults}。
     */
    private KnoluxS3OperationSpec applyDefaults(KnoluxS3OperationSpec spec) {
        return properties != null ? spec.mergeDefaults(properties) : spec;
    }

    // ── 進階模式（明確指定連線 + 分離的 bucket / key）────────────────────────────

    public CompletableFuture<PutObjectResponse> upload(
            String bucket, String key, AsyncRequestBody body,
            KnoluxS3ConnectionDetails conn) {

        var request = PutObjectRequest.builder().bucket(bucket).key(key).build();
        return clientProvider.getClient(conn)
                .putObject(request, body)
                .whenCompleteAsync((_, err) -> {
                    if (err != null) log.error("上傳失敗: bucket={}, key={}", bucket, key, err);
                    else log.debug("上傳成功: bucket={}, key={}", bucket, key);
                }, continuationExecutor);
    }

    public <T> CompletableFuture<T> download(
            String bucket, String key,
            AsyncResponseTransformer<GetObjectResponse, T> transformer,
            KnoluxS3ConnectionDetails conn) {

        var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return clientProvider.getClient(conn)
                .getObject(request, transformer)
                .whenCompleteAsync((_, err) -> {
                    if (err != null) log.error("下載失敗: bucket={}, key={}", bucket, key, err);
                    else log.debug("下載成功: bucket={}, key={}", bucket, key);
                }, continuationExecutor);
    }

    public CompletableFuture<DeleteObjectResponse> delete(
            String bucket, String key,
            KnoluxS3ConnectionDetails conn) {

        var request = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
        return clientProvider.getClient(conn)
                .deleteObject(request)
                .whenCompleteAsync((_, err) -> {
                    if (err != null) log.error("刪除失敗: bucket={}, key={}", bucket, key, err);
                    else log.debug("刪除成功: bucket={}, key={}", bucket, key);
                }, continuationExecutor);
    }
}
