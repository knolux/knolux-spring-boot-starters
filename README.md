# knolux-spring-boot-starters

一組 Spring Boot 自動設定 Starter 函式庫，封裝常用基礎設施元件。每個模組獨立版本，發布至 GitHub Packages。

技術棧：**Java 25 LTS** · **Spring Boot 4.0.5** · **Spring Framework 7** · **Virtual Thread**

---

## 模組總覽

| 模組 | Artifact | 說明 |
|---|---|---|
| [knolux-redis-spring-boot-starter](./knolux-redis-spring-boot-starter/README.md) | `com.knolux:knolux-redis-spring-boot-starter` | Redis Starter — 透過 URL scheme 自動切換 Standalone / Sentinel 模式（Lettuce 客戶端） |
| [knolux-s3-spring-boot-starter](./knolux-s3-spring-boot-starter/README.md) | `com.knolux:knolux-s3-spring-boot-starter` | S3 Starter — AWS SDK v2 非同步 client，支援 SeaweedFS / MinIO / Nginx 反向代理（路徑前綴移除） |

---

## 安裝

### Gradle（Kotlin DSL）

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/knolux/knolux-spring-boot-starters")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.knolux:knolux-redis-spring-boot-starter:1.0.0")
    implementation("com.knolux:knolux-s3-spring-boot-starter:1.0.0")
}
```

### Maven

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/knolux/knolux-spring-boot-starters</url>
</repository>

<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-redis-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-s3-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 快速開始

### Redis（Standalone）

```yaml
# application.yml
knolux:
  redis:
    url: redis://:password@localhost:6379
    timeout-ms: 1000ms
    read-from: MASTER          # MASTER / REPLICA / REPLICA_PREFERRED / ANY
```

```java
@Autowired 
StringRedisTemplate redis;
@Autowired 
RedisTemplate<String, Object> redisTemplate;

redis.opsForValue().set("key", "value");
```

### Redis（Sentinel）

```yaml
knolux:
  redis:
    url: redis-sentinel://:password@sentinel-host:26379/mymaster
    timeout-ms: 3000ms
    read-from: REPLICA_PREFERRED
```

### S3（SeaweedFS / MinIO / AWS S3）

```yaml
knolux:
  s3:
    endpoint: http://seaweedfs:8333
    region: ap-northeast-1
    bucket: my-bucket
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
    force-path-style: true     # SeaweedFS / MinIO 需要；標準 AWS S3 設為 false
spring:
  threads:
    virtual:
      enabled: true            # 啟用 Java 25 Virtual Thread
```

```java
@Autowired 
KnoluxS3Template s3Template;

// 靜態模式
s3Template.upload("bucket", "key", AsyncRequestBody.fromString("hi")).join();

// 動態模式（從 payload 組裝）
KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
        .endpoint(payload.getEndpoint())
        .bucket(payload.getBucket())
        .key(payload.getKey())
        .accessKey(payload.getSecretId())
        .secretKey(payload.getSecretKey())
        .build()
        .mergeDefaults(s3Properties);

byte[] content = s3Template.download(spec, AsyncResponseTransformer.toBytes())
        .join().asByteArray();
```

更詳細的設定範例與 Nginx 代理場景請參閱各模組 README。

---

## 架構特性

### SOLID 設計

- **單一職責原則（SRP）** — 連線工廠、HTTP client 工廠、簽章器分離為獨立類別
- **開閉原則（OCP）** — Redis 透過 `LettuceConnectionFactoryBuilder` 策略介面擴充新模式（Cluster 等）無須改動既有程式碼
- **依賴反轉原則（DIP）** — `KnoluxS3Template` 依賴 `S3ClientProvider` 抽象介面，可注入自訂實作或測試替身

### Virtual Thread 整合

`spring.threads.virtual.enabled=true` 時，`KnoluxS3Template` 的 `CompletableFuture` 完成回呼自動於 Java 25 Virtual Thread 上執行，提升大量並行 S3 操作的執行緒利用率。

### Bean 覆寫機制

所有自動建立的 Bean 均標注 `@ConditionalOnMissingBean`，使用者可定義同名或同型別 Bean 完全覆寫預設行為。

---

## 系統需求

- **Java 25 LTS**（Temurin 建議）
- **Spring Boot 4.0.5+**
- Docker（僅整合測試需要，未安裝時會自動跳過）

---

## 文檔

- [代碼審查報告](./docs/review/code-review.md) — SOLID 合規性與設計分析（繁體中文）
- [Redis 模組架構圖](./docs/diagrams/redis-module.md) — Mermaid 類別圖 + 時序圖
- [S3 模組架構圖](./docs/diagrams/s3-module.md) — Mermaid 類別圖 + 時序圖（含 Nginx 代理）
- [API Javadoc](https://knolux.github.io/knolux-spring-boot-starters/) — 線上 API 文件（gh-pages）

---

## 開發

```bash
# 編譯
./gradlew build

# 全量測試
./gradlew test

# 單一模組測試
./gradlew :knolux-redis-spring-boot-starter:test
./gradlew :knolux-s3-spring-boot-starter:test

# 單一測試類別
./gradlew :knolux-redis-spring-boot-starter:test --tests KnoluxRedisAutoConfigurationTest

# Javadoc 產生
./gradlew javadoc
```

整合測試（`*IntegrationTest`）需要 Docker 執行 Testcontainers；未啟動 Docker 時會透過 `Assumptions.assumeTrue(...)` 自動跳過。

---

## 發布流程

發布由 module-scoped git tag 觸發：

```bash
git tag knolux-redis-spring-boot-starter/v1.0.1
git push origin knolux-redis-spring-boot-starter/v1.0.1
```

CI 工作流程：
1. **CI**（push / PR）— 執行 `./gradlew test --continue`，PR 為唯讀快取模式
2. **Publish**（tag 推送）— 對指定模組執行測試並發布至 GitHub Packages
3. **Javadoc**（tag 推送）— 產生 Javadoc 並部署至 `gh-pages` 分支

[Dependabot](./.github/dependabot.yml) 每週一自動掃描 Gradle 依賴與 GitHub Actions 版本更新。

本地發布需在 `~/.gradle/gradle.properties` 設定：

```properties
org.gradle.project.GITHUB_ACTOR=your-username
org.gradle.project.GITHUB_TOKEN=ghp_...
```

---

## 授權

MIT License — 詳見各模組 LICENSE 檔案。
