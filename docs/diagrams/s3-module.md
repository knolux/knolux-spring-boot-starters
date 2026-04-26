# S3 模組架構圖

本文件包含 `knolux-s3-spring-boot-starter` 模組的 UML 類別圖與關鍵流程時序圖。

---

## 類別圖

```mermaid
classDiagram
    class KnoluxS3AutoConfiguration {
        +knoluxS3ClientFactory(KnoluxS3Properties) KnoluxS3ClientFactory
        +knoluxS3Template(KnoluxS3ClientFactory) KnoluxS3Template
    }

    class KnoluxS3Properties {
        +String endpoint
        +String region
        +String bucket
        +String accessKey
        +String secretKey
        +boolean forcePathStyle
        +boolean removePathPrefix
        +String pathPrefix
        +boolean trustSelfSigned
    }

    class KnoluxS3ClientFactory {
        -KnoluxS3ConnectionDetails defaultDetails
        -Map~String, S3AsyncClient~ clientCache
        -Map~String, SdkAsyncHttpClient~ httpClientCache
        +getClient() S3AsyncClient
        +getClient(KnoluxS3ConnectionDetails) S3AsyncClient
        -buildClient(KnoluxS3ConnectionDetails) S3AsyncClient
        -buildHttpClient(KnoluxS3ConnectionDetails) SdkAsyncHttpClient
        +close()
    }

    class KnoluxS3Template {
        -KnoluxS3ClientFactory clientFactory
        +upload(bucket, key, body) CompletableFuture
        +upload(spec, body) CompletableFuture
        +upload(bucket, key, body, conn) CompletableFuture
        +download(spec, transformer) CompletableFuture
    }

    class KnoluxS3OperationSpec {
        +String endpoint
        +String region
        +String accessKey
        +String secretKey
        +String bucket
        +String key
        +boolean forcePathStyle
        +boolean removePathPrefix
        +String pathPrefix
        +boolean trustSelfSigned
        +mergeDefaults(KnoluxS3Properties) KnoluxS3OperationSpec
        +toConnectionDetails() KnoluxS3ConnectionDetails
        +builder() Builder
    }

    class KnoluxS3ConnectionDetails {
        <<record>>
        +String endpoint
        +String region
        +String accessKey
        +String secretKey
        +boolean forcePathStyle
        +boolean removePathPrefix
        +String pathPrefix
        +boolean trustSelfSigned
        +toCacheKey() String
        +of(KnoluxS3Properties) KnoluxS3ConnectionDetails
    }

    class CacheKeys {
        <<final>>
        +sha256Hex(String) String
    }

    class KnoluxNoPathPrefixSigner {
        <<Deprecated>>
        +sign(SdkHttpFullRequest, ExecutionAttributes) SdkHttpFullRequest
    }

    class S3AsyncClient {
        <<AWS SDK>>
        +putObject(request, body) CompletableFuture
        +getObject(request, transformer) CompletableFuture
    }

    class Aws4Signer {
        <<AWS SDK>>
        +sign(SdkHttpFullRequest, ExecutionAttributes) SdkHttpFullRequest
    }

    KnoluxS3AutoConfiguration ..> KnoluxS3ClientFactory : creates
    KnoluxS3AutoConfiguration ..> KnoluxS3Template : creates
    KnoluxS3AutoConfiguration --> KnoluxS3Properties : depends on

    KnoluxS3Template --> KnoluxS3ClientFactory : uses
    KnoluxS3ClientFactory ..> S3AsyncClient : builds
    KnoluxS3ClientFactory ..> S3AsyncClient : caches
    KnoluxS3ClientFactory --> KnoluxNoPathPrefixSigner : uses

    KnoluxS3OperationSpec ..> KnoluxS3ConnectionDetails : creates via toConnectionDetails
    KnoluxS3ConnectionDetails --> CacheKeys : uses sha256Hex
    KnoluxS3Properties ..> KnoluxS3ConnectionDetails : converts via of(props)

    KnoluxNoPathPrefixSigner --> Aws4Signer : delegates sign
```

---

## 時序圖 1 — 靜態模式 Upload

```mermaid
sequenceDiagram
    participant App as Application
    participant Tmpl as KnoluxS3Template
    participant Factory as KnoluxS3ClientFactory
    participant Client as S3AsyncClient
    participant Storage as SeaweedFS / MinIO

    App ->> Tmpl: upload(bucket, key, body)
    Tmpl ->> Factory: getClient(null)

    alt 快取命中
        Factory -->> Tmpl: cached S3AsyncClient
    else 快取未命中
        Factory ->> Factory: buildClient(defaultDetails)
        Factory -->> Tmpl: new S3AsyncClient
    end

    Tmpl ->> Client: putObject(PutObjectRequest, body)
    Client ->> Storage: HTTP PUT /{bucket}/{key}
    Storage -->> Client: 200 OK
    Client -->> Tmpl: CompletableFuture~PutObjectResponse~
    Tmpl -->> App: CompletableFuture~PutObjectResponse~
```

---

## 時序圖 2 — 動態模式 Download

```mermaid
sequenceDiagram
    participant App as Application
    participant Spec as KnoluxS3OperationSpec
    participant Tmpl as KnoluxS3Template
    participant Factory as KnoluxS3ClientFactory
    participant Client as S3AsyncClient

    App ->> Spec: builder().endpoint(...).bucket(...).key(...).build()
    App ->> Spec: mergeDefaults(s3Properties)
    Spec -->> App: merged KnoluxS3OperationSpec

    App ->> Tmpl: download(spec, transformer)
    Tmpl ->> Spec: toConnectionDetails()
    Spec -->> Tmpl: KnoluxS3ConnectionDetails

    Tmpl ->> Factory: getClient(connectionDetails)

    alt 快取命中 (by sha256 cacheKey)
        Factory -->> Tmpl: cached S3AsyncClient
    else 快取未命中
        Factory ->> Factory: buildClient(connectionDetails)
        Factory -->> Tmpl: new S3AsyncClient
    end

    Tmpl ->> Client: getObject(GetObjectRequest, transformer)
    Client -->> Tmpl: CompletableFuture~T~
    Tmpl -->> App: CompletableFuture~T~
```

---

## 時序圖 3 — Nginx 代理場景（簽章流程）

> 適用於透過 Nginx 反向代理存取 SeaweedFS，且 Nginx 設定了路徑前綴路由（如 `/cluster/s3/` → SeaweedFS `/`）的部署場景。簽章必須以短路徑計算，但 HTTP 請求需帶完整長路徑。

```mermaid
sequenceDiagram
    participant App as Application
    participant Tmpl as KnoluxS3Template
    participant Factory as KnoluxS3ClientFactory
    participant Client as S3AsyncClient
    participant Signer as KnoluxNoPathPrefixSigner
    participant Aws4 as Aws4Signer
    participant Nginx as Nginx Proxy
    participant Storage as SeaweedFS

    App ->> Tmpl: upload(bucket, key, body) [removePathPrefix=true]
    Tmpl ->> Factory: getClient(details with KnoluxNoPathPrefixSigner)
    Factory -->> Tmpl: S3AsyncClient (configured with custom signer)

    Tmpl ->> Client: putObject(request, body) [full path: /cluster/s3/bucket/key]

    Client ->> Signer: sign(request with full path)
    Note over Signer: 移除路徑前綴 /cluster/s3
    Signer ->> Aws4: sign(request with short path /bucket/key)
    Aws4 -->> Signer: signed request (short path + Authorization header)
    Note over Signer: 將 Authorization header 複製回完整路徑請求
    Signer -->> Client: request [path=/cluster/s3/bucket/key + correct signature]

    Client ->> Nginx: HTTP PUT /cluster/s3/bucket/key (with valid signature)
    Note over Nginx: 路由規則：strip /cluster/s3 prefix
    Nginx ->> Storage: HTTP PUT /bucket/key
    Storage -->> Nginx: 200 OK
    Nginx -->> Client: 200 OK
    Client -->> Tmpl: CompletableFuture~PutObjectResponse~
    Tmpl -->> App: CompletableFuture~PutObjectResponse~
```

> **設計說明**：`KnoluxNoPathPrefixSigner` 雖然使用了 AWS SDK v2 中已標記 `@Deprecated` 的 `Signer` SPI，但在此 Nginx 代理場景中，由於 `ExecutionInterceptor` 無法在簽章計算階段介入修改簽章路徑（簽章完成後修改 URL 會使簽章失效），此為目前唯一可行的技術方案。詳見代碼審查報告 S3。
