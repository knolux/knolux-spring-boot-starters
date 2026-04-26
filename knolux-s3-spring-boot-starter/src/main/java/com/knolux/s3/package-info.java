/**
 * Knolux S3 Spring Boot Starter 核心套件。
 *
 * <h2>模組概覽</h2>
 * <p>提供 AWS S3 相容端點（AWS S3 / SeaweedFS / MinIO）的非同步操作封裝，
 * 支援以下部署情境：
 * <ul>
 *   <li><strong>K8s 內部直連（HTTP）</strong> — 直接對 SeaweedFS 發送請求</li>
 *   <li><strong>自簽 HTTPS</strong> — {@code trustSelfSigned=true} 略過憑證驗證
 *       （非正式環境）</li>
 *   <li><strong>Nginx 反向代理</strong> — {@code removePathPrefix=true}；
 *       {@link com.knolux.s3.KnoluxNoPathPrefixSigner} 在簽章時移除路徑前綴</li>
 *   <li><strong>標準 AWS S3</strong> — {@code endpoint} 留空、
 *       {@code forcePathStyle=false}（virtual-hosted style）</li>
 * </ul>
 *
 * <h2>核心類別</h2>
 * <ul>
 *   <li>{@link com.knolux.s3.KnoluxS3AutoConfiguration} — Spring Boot 自動設定進入點</li>
 *   <li>{@link com.knolux.s3.KnoluxS3Template} — 三層 API（靜態 / 動態 / 進階）操作門面</li>
 *   <li>{@link com.knolux.s3.S3ClientProvider} — client 提供者抽象介面（DIP 合規）</li>
 *   <li>{@link com.knolux.s3.KnoluxS3ClientFactory} — {@code S3ClientProvider} 預設實作，
 *       含連線快取機制</li>
 *   <li>{@link com.knolux.s3.KnoluxS3ConnectionDetails} — 連線參數不可變值物件
 *       （Java record）</li>
 *   <li>{@link com.knolux.s3.KnoluxS3OperationSpec} — 動態操作規格（Builder 模式）</li>
 *   <li>{@link com.knolux.s3.KnoluxNoPathPrefixSigner} — Nginx 代理場景的自訂 AWS 簽章器</li>
 * </ul>
 *
 * <h2>Bean 依賴關係</h2>
 * <pre>
 * KnoluxS3Properties (knolux.s3.*)
 *     └─► KnoluxS3AutoConfiguration
 *             ├─► KnoluxS3ClientFactory implements S3ClientProvider
 *             │       (destroyMethod=close, 釋放 Netty EventLoopGroup)
 *             ├─► knoluxS3Executor (Executor)
 *             │       spring.threads.virtual.enabled=true → VirtualThreadPerTaskExecutor
 *             │       否則 ForkJoinPool.commonPool()
 *             └─► KnoluxS3Template (S3ClientProvider, Executor)
 * </pre>
 *
 * <h2>快速開始</h2>
 * <pre>{@code
 * # application.yml — SeaweedFS 直連
 * knolux:
 *   s3:
 *     endpoint: http://seaweedfs:8333
 *     region: ap-northeast-1
 *     bucket: my-bucket
 *     access-key: ${S3_ACCESS_KEY}
 *     secret-key: ${S3_SECRET_KEY}
 *     force-path-style: true
 *
 * # Virtual Thread 啟用
 * spring:
 *   threads:
 *     virtual:
 *       enabled: true
 * }</pre>
 *
 * <h2>三層 API 範例</h2>
 * <pre>{@code
 * // 靜態模式 — 使用 properties 預設連線
 * s3Template.upload("my-bucket", "path/file.jpg", body);
 *
 * // 動態模式 — 從 payload 組裝
 * KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
 *     .endpoint(payload.getEndpoint())
 *     .bucket(payload.getBucket())
 *     .key(payload.getKey())
 *     .accessKey(payload.getSecretId())
 *     .secretKey(payload.getSecretKey())
 *     .build()
 *     .mergeDefaults(s3Properties);
 * s3Template.download(spec, AsyncResponseTransformer.toBytes());
 *
 * // 進階模式 — 明確指定連線
 * s3Template.upload("bucket", "key", body, customConnectionDetails);
 * }</pre>
 *
 * <h2>Virtual Thread 支援</h2>
 * <p>當 {@code spring.threads.virtual.enabled=true} 時，
 * {@link com.knolux.s3.KnoluxS3Template} 的 {@code CompletableFuture} 完成回呼
 * 會於 Java 25 Virtual Thread 上執行，可在大量並行 S3 操作下顯著提升執行緒利用率。
 */
package com.knolux.s3;
