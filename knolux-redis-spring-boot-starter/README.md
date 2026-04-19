# knolux-redis-spring-boot-starter

Spring Boot Starter，透過單一 `REDIS_URL` 環境變數支援 **Sentinel（高可用）** 與 **Standalone** 兩種連線模式。

## 功能特色

- 依 `REDIS_URL` scheme 自動偵測連線模式（`redis://` 或 `redis-sentinel://`）
- Sentinel 模式 — 自動 Master 探索與故障切換
- Standalone 模式 — 直接連線（適合本地開發）
- 可設定讀取策略（`REPLICA_PREFERRED`、`MASTER`、`REPLICA`、`ANY`）
- 透過 Spring Boot Actuator 提供健康檢查指標
- 開發與正式環境之間不需修改任何程式碼

---

## 環境需求

- Java 17+
- Spring Boot 3.x / 4.x

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

| 參數                      | 型別       | 預設值              | 說明                  |
|---------------------------|------------|---------------------|-----------------------|
| `knolux.redis.url`        | `String`   | （必填）            | Redis 連線 URL        |
| `knolux.redis.timeout-ms` | `Duration` | `1000ms`            | 指令逾時時間          |
| `knolux.redis.read-from`  | `String`   | `REPLICA_PREFERRED` | 讀取策略（見下表）    |

### 讀取策略說明

| 值                  | 說明                                         |
|---------------------|----------------------------------------------|
| `REPLICA_PREFERRED` | 優先讀取 Replica 節點；不可用時改讀 Master   |
| `MASTER`            | 永遠從 Master 讀取                           |
| `REPLICA`           | 永遠從 Replica 讀取                          |
| `ANY`               | 讀取任意可用節點                             |

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
redis.opsForValue().set("backend-prod:session:user123", token);
redis.opsForValue().set("backend-prod:cache:product456", data);
```

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

當 `spring-boot-starter-actuator` 存在時，健康端點會自動包含 Redis 狀態：

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

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 自訂連線配置
        return new LettuceConnectionFactory(...);
    }
}
```

---

## 連線 URL 格式參考

| 環境              | URL 格式                                                 |
|-------------------|----------------------------------------------------------|
| 本地開發          | `redis://:password@redis.example.com:6379`               |
| K8s（Sentinel）   | `redis-sentinel://:password@host:26379/mymaster`         |
| 無密碼            | `redis://localhost:6379`                                 |
| 指定資料庫編號    | `redis://:password@localhost:6379/3`                     |

---

## 執行測試

```bash
# 從專案根目錄執行
./gradlew :knolux-redis-spring-boot-starter:test
```

| 測試類別                                    | 涵蓋範圍                            |
|---------------------------------------------|-------------------------------------|
| `KnoluxRedisPropertiesTest`                 | 設定參數綁定                        |
| `KnoluxRedisAutoConfigurationTest`          | 自動配置邏輯、全模式驗證            |
| `KnoluxRedisHealthIndicatorTest`            | 健康指標 UP / DOWN                  |
| `KnoluxRedisStandaloneIntegrationTest`      | Standalone 模式端對端測試（Docker） |
| `KnoluxRedisSentinelIntegrationTest`        | Sentinel 模式端對端測試（Docker）   |
| `KnoluxRedisHealthIndicatorIntegrationTest` | 健康指標搭配真實 Redis 測試         |

---

## 授權條款

MIT
