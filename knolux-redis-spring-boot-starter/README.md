# knolux-redis-spring-boot-starter

Spring Boot Starter，透過單一 `REDIS_URL` 環境變數支援 **Sentinel（高可用）**、**Cluster（分片）** 與 **Standalone**
三種連線模式，並支援 TLS 與 Azure Managed Redis 的 Microsoft Entra ID 受控身分驗證。

技術棧：**Java 25 LTS** · **Spring Boot 4.1.0** · **Spring Framework 7** · **Lettuce** · **Virtual Thread Compatible**

## 功能特色

- 依 `REDIS_URL` scheme 自動偵測連線模式，六種 scheme 皆免改程式碼
- Sentinel 模式 — 自動 Master 探索與故障切換
- Cluster 模式 — `MOVED` / `ASK` 重導與拓撲自動更新
- Standalone 模式 — 直接連線（適合本地開發）
- **TLS** — 由 scheme 單一決定是否加密，憑證驗證強度可調
- **Azure Managed Redis + Microsoft Entra ID**（1.5.0 起）— 以受控身分連線，設定檔不留任何密碼；
  token 在背景更新並對**既有連線**重新 AUTH
- 可設定讀取策略（`REPLICA_PREFERRED`、`MASTER`、`REPLICA`、`ANY`）
- 透過 Spring Boot Actuator 提供健康檢查指標（Bean 名稱 `knoluxRedis`）
- 開發與正式環境之間不需修改任何程式碼
- **策略模式架構** — 透過 `LettuceConnectionFactoryBuilder` 介面擴充新模式無須修改現有程式碼

---

## 環境需求

- **Java 25 LTS**（Temurin 建議）
- **Spring Boot 4.1.0+**

---

## 快速開始

### Gradle（Kotlin DSL）

```kotlin
dependencies {
    implementation("com.knolux:knolux-redis-spring-boot-starter:1.5.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-redis-spring-boot-starter</artifactId>
    <version>1.5.0</version>
</dependency>
```

---

## 設定方式

設定 `REDIS_URL` 環境變數，Starter 會依 URL scheme 自動選擇連線模式。

| scheme                | 模式         | TLS | URL 格式                                                  |
|-----------------------|------------|-----|---------------------------------------------------------|
| `redis://`            | Standalone | ✗   | `redis://[:password@]host:port[/db]`                    |
| `rediss://`           | Standalone | ✓   | `rediss://[:password@]host:port[/db]`                   |
| `redis-sentinel://`   | Sentinel   | ✗   | `redis-sentinel://[:password@]host:port/master-name`    |
| `rediss-sentinel://`  | Sentinel   | ✓   | `rediss-sentinel://[:password@]host:port/master-name`   |
| `redis-cluster://`    | Cluster    | ✗   | `redis-cluster://[:password@]seed-host:port`            |
| `rediss-cluster://`   | Cluster    | ✓   | `rediss-cluster://[:password@]seed-host:port`           |

> **未知的 scheme 會 fail-fast 拋出例外**，不會被靜默當成明文連線。是否加密**只由 scheme 決定**，
> 沒有另一個 `ssl.enabled` 旗標——避免「scheme 說要加密、旗標說不要」這種難以察覺的矛盾狀態。

### Standalone 模式（`redis://` / `rediss://`）

適用於本地開發，或透過負載平衡器（如 HAProxy）連線的情境。

```
REDIS_URL=redis://:your-password@redis.example.com:6379
```

### Sentinel 模式（`redis-sentinel://` / `rediss-sentinel://`）

適用於 Kubernetes 叢集中的高可用部署。

```
REDIS_URL=redis-sentinel://:your-password@redis.redis-cache.svc.cluster.local:26379/mymaster
```

### Cluster 模式（`redis-cluster://` / `rediss-cluster://`）

適用於分片部署。URL 主機為 seed 節點，其餘節點由 Lettuce 自拓撲探索取得。

```
REDIS_URL=redis-cluster://:your-password@redis-node-1:6379
```

```yaml
knolux:
  redis:
    url: ${REDIS_URL}
    cluster:
      max-redirects: 5              # 選填，預設 5
      topology-refresh:
        enabled: true               # 選填，預設 true
        period: 60s                 # 選填，預設 60s
        adaptive: true              # 選填，預設 true
```

> Cluster 模式**不支援指定資料庫編號**（Redis Cluster 僅有 DB 0），URL 帶 `/1` 之類的路徑會拋出例外。
> 拓撲更新預設為**啟用**，與 Lettuce 本身的預設相反：雲端託管的分片節點埠號會隨 failover／擴縮變動，
> 不更新拓撲會讓客戶端持續連向已消失的節點。

### TLS（`rediss` 系列 scheme）

```yaml
knolux:
  redis:
    url: rediss://redis.example.com:6380
    ssl:
      verify-peer: FULL             # 選填，預設 FULL；FULL | CA | NONE
      start-tls: false              # 選填，預設 false
```

| `verify-peer` | 行為                    | 適用情境                       |
|---------------|-----------------------|----------------------------|
| `FULL`（預設）    | 驗證憑證鏈**與**主機名稱        | 正式環境唯一正確的選項                |
| `CA`          | 僅驗證憑證鏈，不比對主機名稱        | 憑證 CN／SAN 與實際連線位址不符的過渡情境   |
| `NONE`        | 完全不驗證（會記錄 `WARN`）     | **僅限測試環境**，連線可被中間人攔截       |

### `application.yml`

```yaml
knolux:
  redis:
    url: ${REDIS_URL}
    timeout-ms: 1000ms              # 選填，預設 1000ms
    read-from: REPLICA_PREFERRED    # 選填，預設 REPLICA_PREFERRED
```

### 設定參數一覽

| 參數                                                  | 型別         | 預設值                 | 說明                            |
|-----------------------------------------------------|------------|---------------------|-------------------------------|
| `knolux.redis.url`                                  | `String`   | （必填）                | Redis 連線 URL                  |
| `knolux.redis.timeout-ms`                           | `Duration` | `1000ms`            | 指令逾時時間                        |
| `knolux.redis.read-from`                            | `String`   | `REPLICA_PREFERRED` | 讀取策略（見下表）                     |
| `knolux.redis.ssl.verify-peer`                      | `enum`     | `FULL`              | 憑證驗證強度，僅 `rediss` 系列生效        |
| `knolux.redis.ssl.start-tls`                        | `boolean`  | `false`             | 是否以 STARTTLS 協商升級             |
| `knolux.redis.cluster.max-redirects`                | `int`      | `5`                 | 單一指令的最大 `MOVED` / `ASK` 重導次數  |
| `knolux.redis.cluster.topology-refresh.enabled`     | `boolean`  | `true`              | 是否啟用拓撲更新                      |
| `knolux.redis.cluster.topology-refresh.period`      | `Duration` | `60s`               | 週期性拓撲更新間隔                     |
| `knolux.redis.cluster.topology-refresh.adaptive`    | `boolean`  | `true`              | 收到 `MOVED` / `ASK` 或連線異常時立即更新 |

Entra ID 相關參數見 [Azure Managed Redis + Microsoft Entra ID](#azure-managed-redis--microsoft-entra-id) 一節。

### 讀取策略說明

`read-from` 直接委派至 Lettuce [
`ReadFrom.valueOf()`](https://github.com/redis/lettuce/blob/main/src/main/java/io/lettuce/core/ReadFrom.java)，支援所有
Lettuce 內建策略（不區分大小寫）。

**單一節點選擇**

| 值                                         | 說明                                                           |
|-------------------------------------------|--------------------------------------------------------------|
| `MASTER` / `UPSTREAM`                     | 永遠從 Master / Upstream 讀取（純 Standalone 時不啟動 topology refresh） |
| `MASTER_PREFERRED` / `UPSTREAM_PREFERRED` | 優先 Master，不可用時降級至 Replica                                    |
| `REPLICA` / `SLAVE`                       | 永遠從 Replica 讀取（不可用則操作失敗）                                     |
| `REPLICA_PREFERRED`（預設）                   | 優先 Replica，不可用時降級至 Master                                    |
| `ANY`                                     | 任意可用節點（Master 或 Replica）                                     |
| `ANY_REPLICA`                             | 任意 Replica 節點                                                |

**進階策略**

| 值                            | 說明                                             |
|------------------------------|------------------------------------------------|
| `LOWEST_LATENCY` / `NEAREST` | 選擇延遲最低的節點（需動態 topology refresh）                |
| `subnet:<cidr,cidr,...>`     | 限定子網路，例如 `subnet:192.168.0.0/16,2001:db8::/52` |
| `regex:<pattern>`            | 以正規表示式比對節點 URI，例如 `regex:.*region-1.*`         |

> 未知值會記錄 `WARN` 並回退至 `REPLICA_PREFERRED`。
> 純 Standalone 模式（`redis://`）且 `read-from=MASTER` 或 `UPSTREAM` 時，Lettuce 不啟動 topology refresh，可降低背景連線開銷。

---

## Azure Managed Redis + Microsoft Entra ID

> 1.5.0 起提供。

啟用後，連線憑證改由 Microsoft Entra ID 發出的 access token 提供，**設定檔與環境變數中不再需要任何 Redis 密碼**。
token 會過期，因此底層會在背景更新，並對**既有連線**重新 AUTH——少了這一步，連線池中的連線會在 token 到期時集體被拒絕。

使用者名稱由函式庫自 JWT 的 `oid` claim 取出，**不需要也無法**在此設定 username。

### 前置作業

**1. Azure 端**

1. 在 Azure Managed Redis 執行個體上啟用 **Microsoft Entra 驗證**
2. 於 **Data Access Configuration** 指派存取原則給目標身分（受控身分或 Service Principal）——
   常用內建原則為 `Data Owner`；最小權限請自訂原則
3. 記下端點主機名稱（`<name>.<region>.redis.azure.net`）與 TLS 埠（預設 `10000`）

> 權限未生效的典型症狀是連線建立成功但指令回 `NOPERM`。此時以 `ACL WHOAMI` 取回的物件 ID
> 與 Portal 上指派的身分逐字比對，是最快的定位方式。

**2. 加入選用依賴**

本 starter 以 `compileOnly` 引入 Entra ID 函式庫（未啟用者不必背負 `msal4j` / `azure-identity` 等傳遞依賴），
需自行加入：

```kotlin
dependencies {
    implementation("redis.clients.authentication:redis-authx-entraid:0.1.1-beta2")
}
```

```xml
<dependency>
    <groupId>redis.clients.authentication</groupId>
    <artifactId>redis-authx-entraid</artifactId>
    <version>0.1.1-beta2</version>
</dependency>
```

> 版本須與 `lettuce-core` 傳遞帶入的 `redis-authx-core` 一致。缺少此依賴而啟用功能時，
> 啟動會 fail-fast 並在例外訊息中印出可直接複製的 Gradle / Maven 座標。

**3. 選對 scheme**

Azure Managed Redis 建立時選擇的 **clustering policy** 決定客戶端該用哪個 scheme：

| clustering policy | scheme               | 說明                                       |
|-------------------|----------------------|------------------------------------------|
| **OSS**（預設）       | `rediss-cluster://`  | 客戶端須為 cluster-aware，分片會把客戶端導向 85xx 動態埠   |
| **Enterprise**    | `rediss://`          | 單一端點由伺服器端代理分片，客戶端當成單一節點連線                |

> 用錯 scheme 的症狀通常是逾時或 `MOVED` 錯誤，而非驗證失敗。

### 設定範例

**AKS / App Service 上的系統指派受控身分**（Azure 內部署的建議選項）

```yaml
knolux:
  redis:
    url: rediss-cluster://mycache.eastus.redis.azure.net:10000
    timeout-ms: 3000ms
    azure:
      entra-id:
        enabled: true
        identity: SYSTEM_ASSIGNED
```

**使用者指派的受控身分**（多個服務共用同一身分）

```yaml
knolux:
  redis:
    azure:
      entra-id:
        enabled: true
        identity: USER_ASSIGNED
        user-assigned-id-type: CLIENT_ID     # CLIENT_ID | OBJECT_ID | RESOURCE_ID
        user-assigned-id: 00000000-0000-0000-0000-000000000000
```

**本機開發**（沿用 `az login` 的登入狀態，與正式環境共用同一份設定）

```yaml
knolux:
  redis:
    azure:
      entra-id:
        enabled: true
        identity: DEFAULT_CHAIN
```

**Service Principal**（地端或其他雲，無法取得受控身分時）

```yaml
knolux:
  redis:
    azure:
      entra-id:
        enabled: true
        identity: SERVICE_PRINCIPAL
        client-id: ${AZURE_CLIENT_ID}
        client-secret: ${AZURE_CLIENT_SECRET}
        authority: https://login.microsoftonline.com/${AZURE_TENANT_ID}
```

### 設定參數一覽

| 參數（前綴 `knolux.redis.azure.entra-id`） | 型別         | 預設值                                 | 說明                                             |
|-------------------------------------|------------|-------------------------------------|------------------------------------------------|
| `enabled`                           | `boolean`  | `false`                             | 是否啟用 Entra ID token 驗證                         |
| `identity`                          | `enum`     | `SYSTEM_ASSIGNED`                   | `SYSTEM_ASSIGNED` / `USER_ASSIGNED` / `DEFAULT_CHAIN` / `SERVICE_PRINCIPAL` |
| `user-assigned-id-type`             | `enum`     | `CLIENT_ID`                         | `CLIENT_ID` / `OBJECT_ID` / `RESOURCE_ID`      |
| `user-assigned-id`                  | `String`   | —                                   | `USER_ASSIGNED` 時必填                            |
| `client-id`                         | `String`   | —                                   | `SERVICE_PRINCIPAL` 時必填                        |
| `client-secret`                     | `String`   | —                                   | `SERVICE_PRINCIPAL` 時必填，建議由環境變數注入               |
| `authority`                         | `String`   | —                                   | `SERVICE_PRINCIPAL` 時必填                        |
| `scopes`                            | `Set`      | `https://redis.azure.com/.default`  | 僅主權雲（Azure China / Government）需覆寫              |
| `token-request-timeout`             | `Duration` | `2s`                                | 單次 token 請求逾時，須小於 `timeout-ms`                 |
| `expiration-refresh-ratio`          | `float`    | `0.75`                              | token 生命週期走完此比例時開始更新，有效區間 `(0, 1]`            |
| `lower-refresh-bound`               | `Duration` | `2m`                                | 至少在到期前此段時間啟動更新                                 |

> `token-request-timeout` 不小於 `timeout-ms` 時只記 `WARN` 不阻擋：Lettuce 在連線建立後才索取憑證，
> 這是可疑而非不可能的組合，硬性擋下會讓「連線逾時短但 IdP 回應慢」的環境無法啟動。

### 啟動階段的 fail-fast 檢查

啟用後，任一前置條件不成立即在啟動時拋出例外，**不會**靜默降級為無驗證或密碼驗證：

| 條件                                          | 訊息重點                                            |
|---------------------------------------------|-------------------------------------------------|
| 使用明文 scheme（`redis://` / `redis-cluster://`）| bearer token 不得走明文——任何取得該字串的人都能冒用              |
| 使用 Sentinel scheme                          | Azure 不提供 Sentinel，改用 `rediss-cluster://` 或 `rediss://` |
| URL 內含密碼                                    | 兩個憑證來源衝突，須二擇一                                   |
| classpath 缺 `redis-authx-entraid`            | 印出可直接複製的 Gradle / Maven 座標                      |
| `identity=DEFAULT_CHAIN` 但缺 `azure-identity` | 同上（此依賴僅 `DEFAULT_CHAIN` 用得到）                    |
| `identity=USER_ASSIGNED` 缺 `user-assigned-id`| 列出可用的識別碼型別                                      |
| `identity=SERVICE_PRINCIPAL` 設定不全            | **逐項指名**缺哪幾個欄位，避免反覆重啟才補齊                        |
| `expiration-refresh-ratio` 不在 `(0, 1]`      | 帶出實際值與預設值                                       |
| Cluster scheme 指定 DB ≠ 0                    | Redis Cluster 僅有 DB 0                           |

### 替換 token 來源

`EntraIdCredentialsProviderFactory` 以 `@ConditionalOnMissingBean(RedisCredentialsProviderFactory.class)` 註冊，
自行提供同型別 Bean 即可完全接管憑證來源（例如改用其他 IdP，或在測試中注入假的 `IdentityProvider`）：

```java
@Bean(destroyMethod = "close")
public RedisCredentialsProviderFactory customCredentials() {
    // TokenAuthConfig 來自 redis-authx-core，IdentityProvider 是函式介面
    return new EntraIdCredentialsProviderFactory(myTokenAuthConfig());
}
```

> 背景更新排程與派送執行緒都是**非 daemon** 執行緒，自訂 Bean 請務必保留 `destroyMethod = "close"`，
> 否則 JVM 無法正常結束。

---

## 使用方式

Starter 會自動配置 `StringRedisTemplate` 與 `RedisTemplate<String, Object>`，直接注入即可使用。

### StringRedisTemplate（字串值）

```java

@Service
public class CacheService {

    private final StringRedisTemplate redis;

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    public void delete(String key) {
        redis.delete(key);
    }
}
```

### RedisTemplate（物件值）

```java
@Service
public class SessionService {

    private final RedisTemplate<String, Object> redis;

    public SessionService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public void saveSession(String userId, Object session) {
        redis.opsForValue().set("session:" + userId, session, Duration.ofHours(1));
    }

    public Object getSession(String userId) {
        return redis.opsForValue().get("session:" + userId);
    }
}
```

### Key 前綴慣例

Redis 沒有內建的命名空間隔離，建議以前綴區分不同服務的資料：

```java
// 建議前綴格式：<服務名稱>:<實體類型>:<ID>
redis.opsForValue().

set("backend-prod:session:user123",token);
redis.

opsForValue().

set("backend-prod:cache:product456",data);
```

---

## 架構設計

### 連線工廠策略模式

```
KnoluxRedisAutoConfiguration
        │
        ├─► LettuceClientConfigurationFactory  ← 共用：TLS / timeout / readFrom / 憑證
        │
        ▼
[ LettuceConnectionFactoryBuilder ] ← 公開策略介面
        │
        ├─ SentinelConnectionFactoryBuilder    (redis-sentinel:// | rediss-sentinel://)
        ├─ ClusterConnectionFactoryBuilder     (redis-cluster://  | rediss-cluster://)
        └─ StandaloneConnectionFactoryBuilder  (redis://          | rediss://)
```

新增連線模式時，只需：

1. 在 `com.knolux.redis.connection` 套件新增實作介面的 builder
2. 將實例加入 `KnoluxRedisAutoConfiguration` 的 builder 清單

不必修改 Auto-Configuration 的核心 Bean 邏輯（符合 OCP 開閉原則）。TLS、逾時、讀取策略與憑證提供者的組裝
集中在 `LettuceClientConfigurationFactory`，三個 builder 共用同一份邏輯（符合 SRP）。

### 憑證來源

```
KnoluxRedisAutoConfiguration
        │  entra-id.enabled=true 時
        ▼
EntraIdCredentialsProviderFactory  implements RedisCredentialsProviderFactory, AutoCloseable
        │
        └─► EntraIdTokenAuthConfigFactory   ← 唯一參考 redis-authx-entraid 型別的類別
```

`EntraIdTokenAuthConfigFactory` 是選用依賴的**唯一接觸點**：未啟用此功能者不必把
`redis-authx-entraid` 放上 classpath，其餘程式碼照常載入。

### Bean 依賴關係

```
KnoluxRedisProperties (knolux.redis.*)
    └─► KnoluxRedisAutoConfiguration
            ├─► EntraIdCredentialsProviderFactory [knolux.redis.azure.entra-id.enabled=true]
            ├─► LettuceConnectionFactory (RedisConnectionFactory)
            ├─► StringRedisTemplate
            ├─► RedisTemplate<String, Object>
            └─► KnoluxRedisHealthIndicator [需要 spring-boot-starter-actuator]
```

詳細時序圖請見 [Redis 模組架構圖](../docs/diagrams/redis-module.md)。

### Virtual Thread 相容性

Lettuce 為非阻塞 reactive 客戶端，本 Starter 完全相容 Java 25 Virtual Thread。
Health Indicator 同步呼叫 `PING` 時若處於 VT 環境也能正確讓出（park）。

---

## Kubernetes 部署

### 建立 Secret

```bash
kubectl create secret generic app-redis-secret \
  --from-literal=REDIS_URL='redis-sentinel://:your-password@redis.redis-cache.svc.cluster.local:26379/mymaster' \
  -n your-namespace
```

### Deployment YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    spec:
      containers:
        - name: your-app
          image: your-image
          env:
            - name: REDIS_URL
              valueFrom:
                secretKeyRef:
                  name: app-redis-secret
                  key: REDIS_URL
```

### AKS + Azure Managed Redis（免 Secret）

改用 Entra ID 受控身分後，連線字串不含任何密碼，**不再需要 Secret**：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    metadata:
      labels:
        azure.workload.identity/use: "true"   # 啟用 workload identity 注入
    spec:
      serviceAccountName: your-workload-identity-sa
      containers:
        - name: your-app
          image: your-image
          env:
            - name: REDIS_URL
              value: rediss-cluster://mycache.eastus.redis.azure.net:10000
```

搭配的設定：

```yaml
knolux:
  redis:
    url: ${REDIS_URL}
    timeout-ms: 3000ms
    azure:
      entra-id:
        enabled: true
        identity: SYSTEM_ASSIGNED
```

> 使用 AKS Workload Identity 時，token 由注入的環境變數與投影 token 檔取得，`SYSTEM_ASSIGNED` 即可運作；
> 若同一叢集有多個服務共用一組 user-assigned 身分，改用 `USER_ASSIGNED` 並填入 `user-assigned-id`。

---

## 本地開發

在專案根目錄建立 `.env` 檔案（加入 `.gitignore`）：

```bash
# .env
REDIS_URL=redis://:your-password@redis.example.com:6379
```

將 `.env.example` 提交至版本控制：

```bash
# .env.example — 複製為 .env 並填入密碼
REDIS_URL=redis://:your-password@redis.example.com:6379
```

---

## 健康檢查

當 `spring-boot-starter-actuator` 存在於 classpath 時，健康端點會自動包含 Redis 狀態（Bean 名稱 `knoluxRedis`，於
Auto-Configuration 中以 `@Bean` 顯式注册）：

```bash
GET /actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "knoluxRedis": {
      "status": "UP",
      "details": {
        "ping": "PONG"
      }
    }
  }
}
```

---

## 覆寫自動配置

透過 `@ConditionalOnMissingBean`，自行定義 Bean 即可替換預設實作：

```java
@Configuration
public class CustomRedisConfig {

    // 覆寫整個連線工廠
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(...);
    }

    // 覆寫健康指標（Bean 名稱必須為 knoluxRedis）
    @Bean(name = "knoluxRedis")
    public HealthIndicator knoluxRedisHealthIndicator(StringRedisTemplate template) {
        return () -> Health.up().withDetail("custom", "yes").build();
    }

    // 覆寫憑證來源（見「替換 token 來源」）
    @Bean(destroyMethod = "close")
    public RedisCredentialsProviderFactory customCredentials() {
        return new EntraIdCredentialsProviderFactory(myTokenAuthConfig());
    }
}
```

---

## 連線 URL 格式參考

| 環境                            | URL 格式                                              |
|-------------------------------|-----------------------------------------------------|
| 本地開發                          | `redis://:password@redis.example.com:6379`          |
| K8s（Sentinel）                 | `redis-sentinel://:password@host:26379/mymaster`    |
| Cluster                       | `redis-cluster://:password@node1:6379`              |
| 無密碼                           | `redis://localhost:6379`                            |
| 指定資料庫編號                       | `redis://:password@localhost:6379/3`                |
| TLS                           | `rediss://redis.example.com:6380`                   |
| Azure Managed Redis（OSS 政策）   | `rediss-cluster://mycache.eastus.redis.azure.net:10000` |
| Azure Managed Redis（Enterprise 政策） | `rediss://mycache.eastus.redis.azure.net:10000` |

---

## 執行測試

```bash
# 從專案根目錄執行
./gradlew :knolux-redis-spring-boot-starter:test
```

| 測試類別                                        | 涵蓋範圍                              |
|---------------------------------------------|-----------------------------------|
| `KnoluxRedisPropertiesTest`                 | 設定參數綁定                            |
| `RedisUriUtilsTest`                         | URL 解析、TLS scheme 判定              |
| `KnoluxRedisAutoConfigurationTest`          | 自動配置邏輯、HealthIndicator 注册、Entra ID 裝配 |
| `KnoluxRedisHealthIndicatorTest`            | 健康指標 UP / DOWN                    |
| `LettuceClientConfigurationFactoryTest`     | TLS 旗標、憑證提供者、重新驗證行為               |
| `StandaloneConnectionFactoryBuilderTest`    | Standalone 策略單元測試                 |
| `SentinelConnectionFactoryBuilderTest`      | Sentinel 策略單元測試                   |
| `ClusterConnectionFactoryBuilderTest`       | Cluster 策略單元測試（節點、重導、拓撲更新）        |
| `EntraIdTokenAuthConfigFactoryTest`         | 四種身分來源的設定對映與 fail-fast 訊息         |
| `EntraIdCredentialsProviderFactoryTest`     | 啟動前置檢查、串流憑證、生命週期                  |
| `KnoluxRedisStandaloneIntegrationTest`      | Standalone 模式端對端測試（Docker）        |
| `KnoluxRedisSentinelIntegrationTest`        | Sentinel 模式端對端測試（Docker）          |
| `KnoluxRedisTokenAuthIntegrationTest`       | token 驗證與輪替後重新 AUTH（Docker）       |
| `KnoluxRedisHealthIndicatorIntegrationTest` | 健康指標搭配真實 Redis 測試                 |
| `KnoluxRedisAzureRealEndpointTest`          | 真實 Azure Managed Redis 驗收（`@Disabled`） |

整合測試 (`*IntegrationTest`) 需要 Docker；未啟動時自動跳過。

`KnoluxRedisTokenAuthIntegrationTest` **不需要 Azure 環境**：它以 `redis-authx-core` 的 `IdentityProvider` SPI
自製短效 token（內容就是 Testcontainers Redis 上 ACL 使用者的密碼），驗證的是本 starter 這一側的行為——
憑證是否真的被送出、輪替後**既有連線**是否重新 AUTH。

`KnoluxRedisAzureRealEndpointTest` 標記 `@Disabled`，是連真實 Azure Managed Redis 的人工驗收測試，
由 `AZURE_REDIS_HOST` 等環境變數驅動，詳見該類別的 Javadoc。

---

## 授權條款

MIT
