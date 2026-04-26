# knolux-redis-spring-boot-starter

Spring Boot Starter，透過單一 `REDIS_URL` 環境變數支援 **Sentinel（高可用）** 與 **Standalone** 兩種連線模式。

技術棧：**Java 25 LTS** · **Spring Boot 4.0.5** · **Spring Framework 7** · **Lettuce** · **Virtual Thread Compatible**

## 功能特色

- 依 `REDIS_URL` scheme 自動偵測連線模式（`redis://` 或 `redis-sentinel://`）
- Sentinel 模式 — 自動 Master 探索與故障切換
- Standalone 模式 — 直接連線（適合本地開發）
- 可設定讀取策略（`REPLICA_PREFERRED`、`MASTER`、`REPLICA`、`ANY`）
- 透過 Spring Boot Actuator 提供健康檢查指標（Bean 名稱 `knoluxRedis`）
- 開發與正式環境之間不需修改任何程式碼
- **策略模式架構** — 透過 `LettuceConnectionFactoryBuilder` 介面擴充新模式（如 Cluster）無須修改現有程式碼

---

## 環境需求

- **Java 25 LTS**（Temurin 建議）
- **Spring Boot 4.0.5+**

---

## 快速開始

### Gradle（Kotlin DSL）

```kotlin
dependencies {
    implementation("com.knolux:knolux-redis-spring-boot-starter:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-redis-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 設定方式

設定 `REDIS_URL` 環境變數，Starter 會依 URL scheme 自動選擇連線模式。

### Standalone 模式（`redis://`）

適用於本地開發，或透過負載平衡器（如 HAProxy）連線的情境。

```
REDIS_URL=redis://:your-password@redis.example.com:6379
```

### Sentinel 模式（`redis-sentinel://`）

適用於 Kubernetes 叢集中的高可用部署。

```
REDIS_URL=redis-sentinel://:your-password@redis.redis-cache.svc.cluster.local:26379/mymaster
```

### `application.yml`

```yaml
knolux:
  redis:
    url: ${REDIS_URL}
    timeout-ms: 1000ms              # 選填，預設 1000ms
    read-from: REPLICA_PREFERRED    # 選填，預設 REPLICA_PREFERRED
```

### 設定參數一覽

| 參數                        | 型別         | 預設值                 | 說明           |
|---------------------------|------------|---------------------|--------------|
| `knolux.redis.url`        | `String`   | （必填）                | Redis 連線 URL |
| `knolux.redis.timeout-ms` | `Duration` | `1000ms`            | 指令逾時時間       |
| `knolux.redis.read-from`  | `String`   | `REPLICA_PREFERRED` | 讀取策略（見下表）    |

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
        ▼
[ LettuceConnectionFactoryBuilder ] ← 公開策略介面
        │
        ├─ StandaloneConnectionFactoryBuilder  (redis://)
        └─ SentinelConnectionFactoryBuilder    (redis-sentinel://)
```

新增連線模式（例如 Redis Cluster）時，只需：

1. 在 `com.knolux.redis.connection` 套件新增 `ClusterConnectionFactoryBuilder` 實作介面
2. 將實例加入 `KnoluxRedisAutoConfiguration` 的 `BUILDERS` 清單

不必修改 Auto-Configuration 的核心 Bean 邏輯（符合 OCP 開閉原則）。

### Bean 依賴關係

```
KnoluxRedisProperties (knolux.redis.*)
    └─► KnoluxRedisAutoConfiguration
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
}
```

---

## 連線 URL 格式參考

| 環境            | URL 格式                                           |
|---------------|--------------------------------------------------|
| 本地開發          | `redis://:password@redis.example.com:6379`       |
| K8s（Sentinel） | `redis-sentinel://:password@host:26379/mymaster` |
| 無密碼           | `redis://localhost:6379`                         |
| 指定資料庫編號       | `redis://:password@localhost:6379/3`             |

---

## 執行測試

```bash
# 從專案根目錄執行
./gradlew :knolux-redis-spring-boot-starter:test
```

| 測試類別                                        | 涵蓋範圍                       |
|---------------------------------------------|----------------------------|
| `KnoluxRedisPropertiesTest`                 | 設定參數綁定                     |
| `KnoluxRedisAutoConfigurationTest`          | 自動配置邏輯、HealthIndicator 注册  |
| `KnoluxRedisHealthIndicatorTest`            | 健康指標 UP / DOWN             |
| `StandaloneConnectionFactoryBuilderTest`    | Standalone 策略單元測試          |
| `SentinelConnectionFactoryBuilderTest`      | Sentinel 策略單元測試            |
| `KnoluxRedisStandaloneIntegrationTest`      | Standalone 模式端對端測試（Docker） |
| `KnoluxRedisSentinelIntegrationTest`        | Sentinel 模式端對端測試（Docker）   |
| `KnoluxRedisHealthIndicatorIntegrationTest` | 健康指標搭配真實 Redis 測試          |

整合測試 (`*IntegrationTest`) 需要 Docker；未啟動時自動跳過。

---

## 授權條款

MIT
