package com.knolux.s3;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knolux S3 Spring Boot Starter 的靜態設定屬性。
 *
 * <p>所有屬性均以 {@code knolux.s3} 為前綴，作為動態請求的 fallback 預設值。
 * 每個欄位都可在執行期間被 {@link KnoluxS3OperationSpec} 的對應欄位覆寫。
 *
 * <h2>SeaweedFS 直連（K8s 內部）</h2>
 * <pre>{@code
 * knolux.s3:
 *   endpoint: http://seaweedfs.seaweedfs.svc.cluster.local:8333
 *   region: ap-northeast-1
 *   bucket: my-bucket
 *   access-key: ${S3_ACCESS_KEY}
 *   secret-key: ${S3_SECRET_KEY}
 * }</pre>
 *
 * <h2>自簽憑證 HTTPS</h2>
 * <pre>{@code
 * knolux.s3:
 *   endpoint: https://s3.aic.org.tw
 *   trust-self-signed: true
 * }</pre>
 *
 * <h2>Nginx 反向代理（含路徑前綴）</h2>
 * <pre>{@code
 * knolux.s3:
 *   endpoint: https://aic.csh.org.tw
 *   remove-path-prefix: true
 *   path-prefix: /cluster/s3
 * }</pre>
 *
 * <h2>標準 AWS S3</h2>
 * <pre>{@code
 * knolux.s3:
 *   region: us-east-1
 *   force-path-style: false   # AWS S3 用 virtual-hosted style
 *   # endpoint 留空，SDK 使用 AWS 預設端點
 * }</pre>
 *
 * @see KnoluxS3OperationSpec
 * @see KnoluxS3AutoConfiguration
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "knolux.s3")
public class KnoluxS3Properties {

    /**
     * S3 相容端點 URL。留空時 SDK 使用 AWS 預設端點（適用標準 AWS S3）。
     */
    private String endpoint;

    /**
     * AWS Region，預設 {@code ap-northeast-1}。SeaweedFS / MinIO 不驗證此值。
     */
    private String region = "ap-northeast-1";

    /**
     * 預設 Bucket 名稱，可被 {@link KnoluxS3OperationSpec#getBucket()} 覆寫。
     */
    private String bucket;

    /**
     * S3 Access Key（SeaweedFS 稱為 Secret ID）。
     */
    private String accessKey;

    /**
     * S3 Secret Key。
     */
    private String secretKey;

    /**
     * 是否強制使用 Path Style 定址，預設 {@code true}。
     *
     * <ul>
     *   <li>{@code true}（預設）— URL 格式為 {@code https://host/bucket/key}，
     *       相容 SeaweedFS、MinIO 等自建 S3</li>
     *   <li>{@code false} — URL 格式為 {@code https://bucket.host/key}（virtual-hosted style），
     *       適用標準 AWS S3</li>
     * </ul>
     */
    private boolean forcePathStyle = true;

    /**
     * 是否在計算 AWS 簽章時移除路徑前綴，預設 {@code false}。
     *
     * <p>適用 Nginx 反向代理場景：Nginx 以路徑前綴路由請求至 SeaweedFS，
     * 但 SeaweedFS 驗證的簽章路徑不含前綴。詳見 {@link KnoluxNoPathPrefixSigner}。
     */
    private boolean removePathPrefix = false;

    /**
     * 要從簽章路徑中移除的前綴，{@code remove-path-prefix=true} 時生效。
     * 例如 {@code /cluster/s3}。
     */
    private String pathPrefix = "";

    /**
     * 是否信任自簽 TLS 憑證，預設 {@code false}。
     *
     * <p>設為 {@code true} 時，Netty HTTP client 會略過憑證鏈驗證。
     * 僅建議用於開發或內部測試環境；正式環境請部署受信任的憑證。
     */
    private boolean trustSelfSigned = false;
}
