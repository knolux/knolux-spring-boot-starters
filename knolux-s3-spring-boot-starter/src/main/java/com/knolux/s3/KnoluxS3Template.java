package com.knolux.s3;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

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
     * 完整建構子。
     *
     * @param clientProvider       S3 client 提供者
     * @param continuationExecutor 用於執行 {@link CompletableFuture#whenCompleteAsync} 回呼的 Executor
     */
    public KnoluxS3Template(S3ClientProvider clientProvider, Executor continuationExecutor) {
        this.clientProvider = clientProvider;
        this.continuationExecutor = continuationExecutor;
    }

    /**
     * 向下兼容建構子，使用 {@link ForkJoinPool#commonPool()} 作為預設 executor。
     *
     * @param clientProvider S3 client 提供者
     */
    public KnoluxS3Template(S3ClientProvider clientProvider) {
        this(clientProvider, ForkJoinPool.commonPool());
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
        return upload(spec.getBucket(), spec.getKey(), body, spec.toConnectionDetails());
    }

    public <T> CompletableFuture<T> download(
            KnoluxS3OperationSpec spec,
            AsyncResponseTransformer<GetObjectResponse, T> transformer) {
        return download(spec.getBucket(), spec.getKey(), transformer, spec.toConnectionDetails());
    }

    public CompletableFuture<DeleteObjectResponse> delete(KnoluxS3OperationSpec spec) {
        return delete(spec.getBucket(), spec.getKey(), spec.toConnectionDetails());
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
