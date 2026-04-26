# Knolux Spring Boot Starters — 代碼審查報告

> **審查日期：** 2026-04-26
> **審查範圍：** `knolux-redis-spring-boot-starter` · `knolux-s3-spring-boot-starter`
> **技術棧：** Java 25 LTS · Spring Boot 4.0.5 · Spring Framework 7 · Lettuce · AWS SDK v2 · Virtual Thread

---

## 執行摘要

兩個模組整體設計清晰，Javadoc 完整（繁體中文），測試覆蓋率良好（單元測試 + Testcontainers 整合測試）。本次審查主要聚焦於 *
*SOLID 原則合規性**、**Spring Boot Library 設計慣例**、與 **Java 25 / Virtual Thread 整合**。

審查共識別 6 項可改進的設計問題（Redis 3 項、S3 3 項）與 6 項 CI/CD 最佳化機會。已於本次重構中全部解決並建立完整的 SOLID
合規架構，同時保證**完整向下兼容**（既有 `new KnoluxS3Template(KnoluxS3ClientFactory)` 等使用方式繼續可用）。

---

## 一、Redis 模組（`knolux-redis-spring-boot-starter`）

### 1.1 R1 — `KnoluxRedisAutoConfiguration` SRP / OCP 違反

**嚴重性：** 中

**問題描述：** Auto-Configuration 類別同時承擔「Spring Bean 宣告」與「連線工廠建立邏輯」兩個職責。`buildStandaloneFactory()` 與
`buildSentinelFactory()` 為私有方法，新增連線模式（如 Redis Cluster）必須修改此類別，違反開閉原則（OCP）。

**改進方案：** 引入 `LettuceConnectionFactoryBuilder` 策略介面：

```java
public interface LettuceConnectionFactoryBuilder {
    boolean supports(URI uri);
    LettuceConnectionFactory build(URI uri, KnoluxRedisProperties properties);
}
```

兩個內建實作：

- `StandaloneConnectionFactoryBuilder`（`com.knolux.redis.connection`）
- `SentinelConnectionFactoryBuilder`（`com.knolux.redis.connection`）

`KnoluxRedisAutoConfiguration` 改用 `BUILDERS` 清單 + stream 分派：

```java
private static final List<LettuceConnectionFactoryBuilder> BUILDERS = List.of(
    new SentinelConnectionFactoryBuilder(),
    new StandaloneConnectionFactoryBuilder()
);

@Bean
public LettuceConnectionFactory redisConnectionFactory() {
    URI uri = URI.create(properties.getUrl());
    return BUILDERS.stream()
        .filter(b -> b.supports(uri))
        .findFirst()
        .map(b -> b.build(uri, properties))
        .orElseThrow(...);
}
```

**結果：** 新增模式僅需新增實作類別並加入 `BUILDERS`，符合 OCP；私有工廠方法移除，每個 builder 可獨立單元測試（SRP）。

---

### 1.2 R2 — `KnoluxRedisHealthIndicator` `@Component` 設計缺陷

**嚴重性：** 高

**問題描述：** Library jar 中使用 `@Component` 依賴 component scan，但 Spring Boot 預設只掃描主程式所在 package，不掃描第三方
library 的 `com.knolux.redis`。此 Bean **在標準使用情境下永遠不會被建立**，破壞 Auto-Configuration 的獨立性原則。

**改進方案：**

1. 移除 `KnoluxRedisHealthIndicator` 的 `@Component` 與 `@ConditionalOnClass` 注解
2. 在 `KnoluxRedisAutoConfiguration` 以 `@Bean` 顯式注册：

```java
@Bean(name = "knoluxRedis")
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnMissingBean(name = "knoluxRedis")
public KnoluxRedisHealthIndicator knoluxRedisHealthIndicator(StringRedisTemplate template) {
    return new KnoluxRedisHealthIndicator(template);
}
```

**結果：** Bean 確實會被建立、可被覆寫、不依賴 component scan。Bean 名稱 `knoluxRedis` 對外行為一致，向下兼容。

---

### 1.3 R3 — `RedisUriUtils.parseReadFrom()` 靜默 Fallback 與支援不完整

**嚴重性：** 中

**問題描述：**

1. 未知策略字串靜默回退至 `REPLICA_PREFERRED`，使用者無法察覺設定錯誤
2. 僅支援 4 種策略（`MASTER`、`REPLICA`、`REPLICA_PREFERRED`、`ANY`），未涵蓋 Lettuce 提供的 `LOWEST_LATENCY`、`ANY_REPLICA`、
   `subnet:`、`regex:` 等進階模式

**改進方案：**

- 直接委派至 Lettuce `ReadFrom.valueOf(String)`（自動取得所有策略支援）
- 對未知值新增 `WARN` 日誌
- 提取 `isMasterOnly()` 工具方法判斷是否為純 MASTER/UPSTREAM 模式（避免 `subnet:` / `regex:` 被誤判為 MASTER 而跳過
  topology refresh）

```java
public static ReadFrom parseReadFrom(String readFrom) {
    if (readFrom == null || readFrom.isBlank()) {
        log.warn("readFrom 未設定，使用預設值 REPLICA_PREFERRED");
        return ReadFrom.REPLICA_PREFERRED;
    }
    try {
        return ReadFrom.valueOf(readFrom.trim());
    } catch (IllegalArgumentException ex) {
        log.warn("未知的 readFrom 策略 '{}'，退回使用 REPLICA_PREFERRED ...", readFrom);
        return ReadFrom.REPLICA_PREFERRED;
    }
}
```

**結果：** 支援完整 Lettuce 策略集合（含 `subnet:192.168.0.0/16`、`regex:.*region-1.*`），未知值有明確警告，純 MASTER 模式仍享
topology refresh 跳過最佳化。

---

## 二、S3 模組（`knolux-s3-spring-boot-starter`）

### 2.1 S1 — `KnoluxS3Template` DIP 違反

**嚴重性：** 高

**問題描述：** `KnoluxS3Template` 直接依賴具體類別 `KnoluxS3ClientFactory`：

```java
@RequiredArgsConstructor
public class KnoluxS3Template {
    private final KnoluxS3ClientFactory clientFactory;  // 依賴具體實作
}
```

使用者若想替換 client 快取策略（如自訂連線池、整合 Service Mesh、測試 mock），必須繼承 `KnoluxS3ClientFactory`，違反依賴反轉原則（DIP）。

**改進方案：** 提取 `S3ClientProvider` 公開介面：

```java
public interface S3ClientProvider extends AutoCloseable {
    S3AsyncClient getClient(KnoluxS3ConnectionDetails details);
    default S3AsyncClient getClient() { return getClient(null); }
    @Override void close();
}
```

`KnoluxS3ClientFactory implements S3ClientProvider`；`KnoluxS3Template` 改依賴介面型別。Auto-Configuration 改用
`@ConditionalOnMissingBean(S3ClientProvider.class)`，讓使用者可替換完整實作而無需繼承。

**向下兼容：** 由於 `KnoluxS3ClientFactory implements S3ClientProvider`，既有 `new KnoluxS3Template(factory)`
透過自動上轉型仍可編譯（已於 3 個測試檔案驗證）。

---

### 2.2 S2 — `KnoluxS3ClientFactory.buildClient()` SRP 違反

**嚴重性：** 中

**問題描述：** `buildClient()` 方法承擔三個職責：

1. 建立 Netty HTTP client（含 `trustSelfSigned` TLS 配置）
2. 組裝 S3AsyncClient（含 endpoint、custom signer、credentials）
3. 處理建立失敗時的資源回收

方法長達 50+ 行，HTTP client 建立邏輯無法獨立測試。

**改進方案：** 提取 package-private `S3HttpClientFactory` 工具類別：

```java
final class S3HttpClientFactory {
    private S3HttpClientFactory() {}

    static SdkAsyncHttpClient build(KnoluxS3ConnectionDetails details) {
        var builder = NettyNioAsyncHttpClient.builder()
                .maxConcurrency(20)
                .connectionTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30));
        if (details.trustSelfSigned()) {
            log.warn("[安全警告] trustSelfSigned=true ...");
            return builder.buildWithDefaults(...);
        }
        return builder.build();
    }
}
```

**結果：** HTTP client 邏輯獨立可測（新增 `S3HttpClientFactoryTest`：3 個測試覆蓋 `trustSelfSigned=false/true` 與
`null endpoint`）。`KnoluxS3ClientFactory.buildClient()` 行數縮短 35%，職責更清晰。

---

### 2.3 S3 — `KnoluxNoPathPrefixSigner` 使用已棄用 Signer SPI

**嚴重性：** 低（已知限制）

**問題描述：** 實作已標記 `@Deprecated` 的 AWS SDK v2 `Signer` 介面。AWS 建議遷移至 `HttpSigner` SPI。

**現況評估：**
本場景需求為「**簽短路徑、傳長路徑**」（Nginx 代理移除前綴）：

1. 對移除前綴後的路徑計算 AWS4 簽章
2. 將簽章 Header 複製回原始長路徑請求

`HttpSigner` 與 `ExecutionInterceptor.modifyHttpRequest()` 都無法在簽章**計算後**修改路徑（簽章已固化），因此目前無乾淨的非棄用替代方案。AWS
SDK 在 async pipeline 的 `SigningStage` 仍同步執行 `Signer`，因此功能不受影響。

**結果：** 保留現狀，於程式碼註解明確記錄此限制與遷移阻礙。Production 行為穩定。

---

## 三、CI/CD 管線（`.github/workflows/`）

### 3.1 缺少 Gradle Build Cache 寫入策略

**改進：** `setup-gradle@v4` 加入 `cache-read-only: ${{ github.event_name == 'pull_request' }}`，PR 唯讀（避免污染 main
快取），main / dev push 寫入快取。

### 3.2 `publish.yml` 缺少 `GITHUB_ACTOR` env

**改進：** publish 步驟明確設定 `GITHUB_ACTOR: ${{ github.actor }}`（GitHub Packages publish 需要 username + token 雙因子）。

### 3.3 缺少 Dependabot 自動依賴更新

**改進：** 新增 `.github/dependabot.yml`，每週一掃描 Gradle 依賴與 GitHub Actions 版本。

### 3.4 Gradle 平行建置未啟用

**改進：** 全部 workflow 加入
`GRADLE_OPTS: "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=true -Dorg.gradle.workers.max=4"`。

### 3.5 測試失敗時無 JUnit XML 上傳

**改進：** `ci.yml` 新增 `actions/upload-artifact@v4` 上傳 `**/build/test-results/test/*.xml`，便於後續整合測試報告檢視。

### 3.6 `javadoc.yml` 寫入快取浪費

**改進：** 設定 `cache-read-only: true`（Javadoc 為唯讀場景，無需寫入 cache）。

---

## 四、Virtual Thread 整合

**新增：** `KnoluxS3AutoConfiguration` 注册 `knoluxS3Executor` Bean：

```java
@Bean(name = "knoluxS3Executor", destroyMethod = "close")
@ConditionalOnMissingBean(name = "knoluxS3Executor")
public Executor knoluxS3Executor(Environment env) {
    if (env.getProperty("spring.threads.virtual.enabled", Boolean.class, false)) {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
    return ForkJoinPool.commonPool();
}
```

`KnoluxS3Template` 雙建構子設計：

- `(S3ClientProvider, Executor)` — 完整版，注入 VT executor
- `(S3ClientProvider)` — 向下兼容版，預設 `ForkJoinPool.commonPool()`

所有 `whenComplete` 改為 `whenCompleteAsync(..., continuationExecutor)`，在 VT 啟用時於 Virtual Thread 上執行 callback。

---

## 五、正面評價

1. **`KnoluxS3ConnectionDetails`** — Java record 設計優秀，不可變值物件清晰
2. **`KnoluxS3OperationSpec.mergeDefaults()`** — 部署級別設定的安全鎖定設計（防止 payload 繞過部署配置）
3. **`CacheKeys.sha256Hex()`** — 將雜湊邏輯從值物件中分離，符合 SRP；快取鍵不含明文 secretKey，預防 heap dump 洩漏
4. **`KnoluxS3ClientFactory` 資源洩漏防護** — HTTP client 先放入 cache 後再 build S3 client，確保 `close()`
   即使在建構失敗時也能完整回收
5. **測試設計** — 使用 `ApplicationContextRunner` 避免啟動完整 Spring Context，單元測試速度快；整合測試使用 Testcontainers
   確保真實環境驗證
6. **Javadoc 完整性** — 全繁體中文，覆蓋所有公開 API，含設定範例
7. **Spring Boot 4 相容性** — 已使用 `org.springframework.boot.health.contributor.HealthIndicator`（4.0 新位置），非舊
   Actuator 套件

---

## 六、改進優先順序

### 高優先（已完成）

- ✅ R2 — HealthIndicator @Component 修正（影響功能正確性）
- ✅ S1 — S3ClientProvider 介面提取（影響擴充性）

### 中優先（已完成）

- ✅ R1 — Connection factory 策略模式（OCP）
- ✅ R3 — ReadFrom 完整支援與警告日誌
- ✅ S2 — S3HttpClientFactory 提取（SRP）
- ✅ Virtual Thread executor 整合
- ✅ CI/CD 全套最佳化（Dependabot、Gradle cache、parallel build）

### 低優先（保留現狀）

- ⚠ S3 — `KnoluxNoPathPrefixSigner` Deprecated SPI（無乾淨替代方案，已記錄限制）

---

## 七、向下兼容驗證

本次重構完整保證向下兼容：

| 項目                                                 | 驗證方式                                                                                                     |
|----------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| `new KnoluxS3Template(KnoluxS3ClientFactory)` 仍可編譯 | 3 個既有測試檔案（`KnoluxS3AutoConfigurationTest`、`KnoluxS3IntegrationTest`、`KnoluxS3RealEndpointTest`）持續通過      |
| `application.yml` 屬性鍵不變                            | `knolux.redis.*`、`knolux.s3.*` 完整保留                                                                      |
| 公開類別與 Bean 名稱不變                                    | `knoluxRedis` HealthIndicator Bean 名稱保持一致；`KnoluxS3ClientFactory` 仍為公開類別                                 |
| 自動設定類別位置不變                                         | `META-INF/spring/.../AutoConfiguration.imports` 內容不變                                                     |
| 既有 ReadFrom 值繼續支援                                  | `MASTER`、`REPLICA`、`REPLICA_PREFERRED`、`ANY` 全部支援，且新增 `UPSTREAM`、`LOWEST_LATENCY`、`subnet:`、`regex:` 等擴充 |

全量測試 `./gradlew test` 通過，Javadoc 產生無錯誤。
