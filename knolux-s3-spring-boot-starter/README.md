# knolux-s3-spring-boot-starter

Spring Boot Starter，封裝 AWS SDK v2 非同步 S3 client，支援 SeaweedFS / MinIO 及標準 AWS S3。
提供靜態、動態、進階三層 API，適用 K8s 直連、自簽憑證 HTTPS、Nginx 反向代理、標準 AWS S3 等場景。

技術棧：**Java 25 LTS** · **Spring Boot 4.0.5** · **Spring Framework 7** · **AWS SDK v2 Async** · **Virtual Thread**

---

## 環境需求

- **Java 25 LTS**（Temurin 建議）
- **Spring Boot 4.0.5+**

---

## 快速開始

### Gradle

```kotlin
implementation("com.knolux:knolux-s3-spring-boot-starter:1.0.0")
```

### Maven

```xml
<dependency>
  <groupId>com.knolux</groupId>
  <artifactId>knolux-s3-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## 部署情境設定

### 情境一：K8s 叢集內部直連（HTTP）

```yaml
knolux:
  s3:
    endpoint: http://seaweedfs-s3.seaweedfs.svc.cluster.local:8333
    region: ap-northeast-1
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    force-path-style: true
```

### 情境二：公司內網自簽憑證 HTTPS

```yaml
knolux:
  s3:
    endpoint: https://s3.aic.org.tw
    region: ap-northeast-1
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    trust-self-signed: true      # 略過憑證鏈驗證（非正式環境）
    force-path-style: true
```

> **安全警告**：`trust-self-signed: true` 會停用所有 TLS 憑證與 hostname 驗證，
> 僅限開發或公司內部不可控憑證環境使用。正式對外服務請部署受信任的憑證。

### 情境三：外部 Nginx 反向代理（含路徑前綴）

Nginx 以路徑前綴（例如 `/cluster/s3`）路由至 SeaweedFS，SeaweedFS 驗章時不含前綴。
Starter 透過 `KnoluxNoPathPrefixSigner` 自動調整 AWS 簽章路徑，使簽章與 SeaweedFS 看到的路徑一致。

```yaml
knolux:
  s3:
    endpoint: https://aic.csh.org.tw
    region: ap-northeast-1
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    force-path-style: true
    remove-path-prefix: true
    path-prefix: /cluster/s3
```

詳細簽章流程請見 [S3 模組架構圖 — Nginx 代理時序圖](../docs/diagrams/s3-module.md)。

### 情境四：標準 AWS S3

```yaml
knolux:
  s3:
    region: us-east-1
    access-key: ${AWS_ACCESS_KEY_ID}
    secret-key: ${AWS_SECRET_ACCESS_KEY}
    force-path-style: false    # AWS S3 使用 virtual-hosted style
    # endpoint 留空，SDK 使用 AWS 官方端點
```

---

## Virtual Thread 整合

啟用 Java 25 Virtual Thread 後，`KnoluxS3Template` 的 `CompletableFuture` 完成回呼會於 VT 上執行，可在大量並行 S3
操作下顯著提升執行緒利用率：

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

實作機制：Auto-Configuration 偵測此屬性後注册 `knoluxS3Executor` Bean（`Executors.newVirtualThreadPerTaskExecutor()`），由
`KnoluxS3Template` 透過 `whenCompleteAsync(..., executor)` 使用。容器關閉時 executor 會自動 close 釋放資源。

未啟用時退回 `ForkJoinPool.commonPool()`。

---

## 使用方式

### 靜態模式（application.yaml 固定設定）

```java
@Service
public class FileService {

    @Autowired private KnoluxS3Template s3Template;

    public void upload(String bucket, String key, byte[] data) {
        s3Template.upload(bucket, key, AsyncRequestBody.fromBytes(data)).join();
    }

    public byte[] download(String bucket, String key) {
        return s3Template.download(bucket, key, AsyncResponseTransformer.toBytes())
                .join()
                .asByteArray();
    }

    public void delete(String bucket, String key) {
        s3Template.delete(bucket, key).join();
    }
}
```

### 動態模式（執行期 payload 提供連線參數）

適用 REST API 接收 S3 操作請求、或 MQ Payload 含有 S3 連線資訊的場景。

```java
@Service
public class DynamicS3Service {

    @Autowired private KnoluxS3Template s3Template;
    @Autowired private KnoluxS3Properties s3Properties;  // 提供 fallback 預設值

    public byte[] downloadFromPayload(S3DownloadRequest req) {
        KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
                .endpoint(req.getEndpoint())     // null → fallback 至 application.yaml
                .accessKey(req.getSecretId())    // SeaweedFS 稱為 secretId
                .secretKey(req.getSecretKey())
                .bucket(req.getBucket())
                .key(req.getObjectKey())
                .build()
                .mergeDefaults(s3Properties);    // 必填：填補 null + 鎖定部署設定

        return s3Template.download(spec, AsyncResponseTransformer.toBytes())
                .join()
                .asByteArray();
    }
}
```

> **注意**：`mergeDefaults()` 必須呼叫。`forcePathStyle`、`removePathPrefix`、`pathPrefix`、
> `trustSelfSigned` 等部署級別設定一律以 `application.yaml` 為準，
> payload 無法覆寫，防止呼叫端繞過部署配置。

### 進階模式（明確指定連線參數）

```java
KnoluxS3ConnectionDetails conn = new KnoluxS3ConnectionDetails(
        "http://seaweedfs:8333", "ap-northeast-1",
        accessKey, secretKey,
        true, false, "", false
);
s3Template.

upload("bucket","path/to/file.txt",AsyncRequestBody.fromString("content"),conn);
```

---

## 設定參數一覽

| 參數                               | 預設值              | 說明                                                  |
|----------------------------------|------------------|-----------------------------------------------------|
| `knolux.s3.endpoint`             | `null`           | S3 端點 URL；留空使用 AWS 官方端點                             |
| `knolux.s3.region`               | `ap-northeast-1` | AWS Region                                          |
| `knolux.s3.bucket`               | `null`           | 預設 Bucket                                           |
| `knolux.s3.access-key`           | `null`           | S3 Access Key（SeaweedFS 稱 Secret ID）                |
| `knolux.s3.secret-key`           | `null`           | S3 Secret Key                                       |
| `knolux.s3.force-path-style`     | `true`           | `true` 使用 Path Style（相容 SeaweedFS / MinIO）          |
| `knolux.s3.remove-path-prefix`   | `false`          | 簽章前移除路徑前綴（Nginx 代理場景）                               |
| `knolux.s3.path-prefix`          | `""`             | 要移除的前綴，例如 `/cluster/s3`                             |
| `knolux.s3.trust-self-signed`    | `false`          | 信任自簽 TLS 憑證（停用憑證鏈驗證）                                |
| `spring.threads.virtual.enabled` | `false`          | 啟用 Virtual Thread executor 供 CompletableFuture 回呼使用 |

---

## 架構設計

### 三層分離（SOLID 合規）

```
┌──────────────────────────────────────────────────────┐
│  KnoluxS3Template  (三層 API：Static / Dynamic / Adv) │
└────────────┬─────────────────────────────────────────┘
             │ depends on (DIP)
             ▼
┌──────────────────────────────────────────────────────┐
│  S3ClientProvider  (公開介面)                         │
└────────────┬─────────────────────────────────────────┘
             │ implements
             ▼
┌──────────────────────────────────────────────────────┐
│  KnoluxS3ClientFactory  (預設實作 + client 快取)      │
└────────────┬─────────────────────────────────────────┘
             │ delegates HTTP client creation (SRP)
             ▼
┌──────────────────────────────────────────────────────┐
│  S3HttpClientFactory  (Netty client 工廠)             │
└──────────────────────────────────────────────────────┘
```

- **DIP** — `KnoluxS3Template` 依賴抽象 `S3ClientProvider`，可注入自訂實作或 mock
- **SRP** — HTTP client 建立邏輯獨立於 S3 client 組裝邏輯
- **Connection cache** — 以 `KnoluxS3ConnectionDetails.toCacheKey()`（accessKey 經 SHA-256 雜湊）作為快取鍵

### Bean 依賴關係

```
KnoluxS3Properties (knolux.s3.*)
    └─► KnoluxS3AutoConfiguration
            ├─► KnoluxS3ClientFactory implements S3ClientProvider
            │       (destroyMethod=close, 釋放 Netty EventLoopGroup)
            ├─► knoluxS3Executor (Executor)
            │       VT 啟用 → VirtualThreadPerTaskExecutor
            │       否則 → ForkJoinPool.commonPool()
            └─► KnoluxS3Template (S3ClientProvider, Executor)
```

詳細類別圖與時序圖請見 [S3 模組架構圖](../docs/diagrams/s3-module.md)。

---

## 覆寫預設 Bean

透過 `@ConditionalOnMissingBean`，可自行定義 Bean 替換預設實作：

```java
@Configuration
public class CustomS3Config {

    // 完全替換 client 提供者（例如：自訂連線池、多區域路由）
    @Bean(destroyMethod = "close")
    public S3ClientProvider knoluxS3ClientFactory() {
        return new MyCustomClientProvider(...);
    }

    // 自訂 continuation executor（例如：固定大小執行緒池）
    @Bean(name = "knoluxS3Executor")
    public Executor knoluxS3Executor() {
        return Executors.newFixedThreadPool(20);
    }
}
```

---

## 安全性建議

- 憑證（`access-key`、`secret-key`）透過環境變數或 Secrets Manager 注入，**不要硬寫在設定檔**
- `trust-self-signed: true` 僅限開發或公司封閉內網，對外服務絕對不可使用
- 動態模式的 payload 不可覆寫部署級別設定，`mergeDefaults()` 已強制鎖定此規則
- Cache key 中的 `accessKey` 以 SHA-256 hash 儲存，不以明文出現在記憶體中

---

## 執行測試

```bash
# 從專案根目錄執行
./gradlew :knolux-s3-spring-boot-starter:test
```

| 測試類別                            | 涵蓋範圍                                    |
|---------------------------------|-----------------------------------------|
| `KnoluxS3PropertiesTest`        | 設定參數綁定                                  |
| `KnoluxS3AutoConfigurationTest` | Auto-Configuration 邏輯、Bean 覆寫、VT 偵測     |
| `KnoluxS3OperationSpecTest`     | 動態模式 builder + mergeDefaults 行為         |
| `KnoluxS3ConnectionDetailsTest` | 不可變值物件 + cache key 行為                   |
| `KnoluxS3ClientFactoryTest`     | Client 快取與資源回收                          |
| `S3HttpClientFactoryTest`       | Netty HTTP client 建立（含 trustSelfSigned） |
| `KnoluxNoPathPrefixSignerTest`  | Nginx 代理路徑前綴移除簽章                        |
| `KnoluxS3IntegrationTest`       | 端對端 S3 操作（Testcontainers）               |

整合測試 (`*IntegrationTest`) 需要 Docker；未啟動時自動跳過。

---

## 授權條款

MIT
