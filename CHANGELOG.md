# Changelog

本專案所有值得注意的變更都會記錄於此檔案。

格式參考 [Keep a Changelog](https://keepachangelog.com/zh-TW/1.1.0/)，版本號遵循 [Semantic Versioning](https://semver.org/lang/zh-TW/)。

本 repository 為多模組 monorepo，各模組獨立版控，tag 格式為 `<module>/v<version>`：

- `knolux-redis-spring-boot-starter`
- `knolux-s3-spring-boot-starter`

---

## [Unreleased]

---

## [2026-08-02] — redis 1.4.1 · s3 1.3.1

**兩個模組的 artifact 內容與前一版完全相同**：自 `redis/v1.4.0`、`s3/v1.3.0` 以來
`.java` 變更為空，外溢給下游的 resolved 依賴集合也逐字未變（redis 49 筆、s3 62 筆）。
已在使用前一版者**無需升級**。

本版純粹反映建置基礎設施的變更，並藉此讓發版流程實際跑過一次新增的發版前閘門。

### Added

- **傳遞依賴破壞性變更閘門**：以 `api` scope 曝露的依賴一旦發生 major 跳動或依賴移除，
  在合併前而非發布後被攔下。redis 1.4.0 那次 Lettuce 6 → 7 的跨 major 變動就是此機制要防的情形
  - `./gradlew checkDependencyCompatibility` —— PR 時比對外溢依賴，major 跳動與依賴移除會讓 CI 失敗，
    minor / patch 僅列入報告（避免每週的 Dependabot PR 全數紅燈）
  - `./gradlew checkDependencyBaseline` —— 發布前逐字驗證基準線，任何落差皆失敗
  - `./gradlew updateDependencyBaseline` —— 重新產生基準線檔，發版前必跑
  - `./gradlew dependencyChangeReport --since=<tag>` —— 產生可直接貼進 CHANGELOG 的升級揭露
- `gradle/dependency-baseline/<module>.txt`：各模組外溢依賴集合的簽入基準線。
  依賴變動因此成為 PR diff 中可審閱的事件，而非埋在 CI log 裡
- `gradle/dependency-approvals.toml`：破壞性變更的核准紀錄，限定模組／座標／版本區間並附 `reason`，
  刻意不做成全域開關

閘門刻意**不掛在 `check` 之下**，`./gradlew build` 的行為完全不受影響。

---

## [2026-08-01] — redis 1.4.0 · s3 1.3.0

### ⚠️ 升級前必讀：`knolux-redis-spring-boot-starter` 的傳遞依賴破壞性變更

`spring-boot-starter-data-redis` 在 redis 模組為 **`api` scope**，傳遞依賴會直接曝露給下游。
Spring Boot 4.1.0 帶來一項可能影響直接使用底層型別之程式碼的變動：

| 依賴 | redis 1.3.0 | redis 1.4.0 | 影響 |
|---|---|---|---|
| `io.lettuce:lettuce-core` | 6.8.2.RELEASE | **7.5.2.RELEASE** | **跨 major 版本** |

直接使用 Lettuce 型別（`io.lettuce.core.ReadFrom`、`RedisClient`、`ClientOptions`，
或自訂 `LettuceClientConfigurationBuilderCustomizer`）者，升級前請先確認 Lettuce 7 的變更說明。
僅透過 `knolux.redis.*` 設定使用者不受影響。

> **2026-08-02 更正**：本段原另列一項「`io.netty:*` 由 4.1.125 與 4.2.12 並存統一為 4.2.x（4.1.x 已移除）」，
> 該項**不成立**，已移除。以 `dependencyChangeReport` 重建 redis 1.3.0 的解析結果比對後確認：
> 1.3.0 的 `runtimeClasspath` 上 Netty 全數已是 `4.2.12.Final`，並無 4.1.x，故 1.4.0 沒有 Netty 移除事件
> （實際變動為 `4.2.12.Final` → `4.2.15.Final`，屬 patch，已列於下方非破壞性清單）。
> 原判讀誤把 `./gradlew dependencies` 輸出中括號內的 *requested* 版本（`lettuce-core:6.8.2.RELEASE`
> 確實 requested `netty:4.1.125.Final`，但被 Spring Boot BOM 選為 `4.2.12.Final`）當成了解析結果。
> 若曾據此評估升級風險，Netty 部分無需處理；Lettuce 的 major 跳動則不受影響，仍然成立。

`knolux-s3-spring-boot-starter` 的傳遞依賴無跨 major 變動。

### Changed

- **升級 Spring Boot 4.0.6 → 4.1.0**（Gradle plugin 與 `spring-boot-dependencies` BOM）。
  **兩個模組的最低需求同步提升為 Spring Boot 4.1.0+**，仍在 Spring Boot 4.0.x 的使用者請留在前一版
- 升級 AWS SDK v2 BOM `2.46.3` → `2.49.3`（影響 `knolux-s3-spring-boot-starter`）
- 升級 Gradle wrapper `9.4.1` → `9.6.1`（僅影響本 repo 建置）
- CI / Publish / Javadoc workflow 的 `actions/checkout` 由 `v6` 升至 `v7`（僅影響 CI）
- 更新根目錄與兩個模組 README 中的 Spring Boot 版本與安裝版號標示

其餘傳遞依賴變動（非破壞性）：

- redis — `spring-data-redis` 4.0.5 → 4.1.0、新增 `spring-messaging`、
  `snakeyaml` 2.5 → 2.6、`logback` 1.5.32 → 1.5.34、Netty 4.2.12 → 4.2.15
- s3 — AWS SDK 2.46.3 → 2.49.3、`httpcore5` 5.3.6 → 5.4.2、`httpclient5` 5.5.2 → 5.6.1、
  Netty 4.2.12 → 4.2.15
- 兩者 — Spring Framework 7.0.7 → 7.0.8、Micrometer 1.16.5 → 1.17.0

### Added

- 新增 `CHANGELOG.md`，回填自 tag 的完整發布歷史
- 導入 Spec-Driven Development：新增 `.specify/` 與專案憲章
  `.specify/memory/constitution.md` v1.0.0（六條核心原則、技術約束、開發流程與品質閘門）

### Notes

兩個模組**自有原始碼零變更**（`v1.3.0..main` 的 `.java` diff 為空），無 API 新增或移除，
故採 MINOR 升版。

惟 redis 模組經 `api` scope 傳遞的 Lettuce 跨了 major 版本，以嚴格 SemVer 解讀亦可主張 MAJOR。
版號已發布無法更動，故於此明確揭露。此事由發布後的完整依賴比對才發現，
後續應於 CI 加入 API 相容性檢查（japicmp 或同類工具）以自動攔截。

---

## [2026-06-04] — redis 1.3.0 · s3 1.2.0

### Fixed

- Redis URL scheme 解析強化：`parseDb` / `parseReadFrom` 改為 fail-fast，不再對非預期輸入靜默採用預設值
- S3 動態模式安全邊界強化：`KnoluxS3OperationSpec.mergeDefaults()` 確保 payload 無法覆寫部署級別設定
- 修補兩處低嚴重度健壯性缺口並補齊邊界案例測試

### Changed

- 升級 AWS SDK v2 BOM 至 `2.46.3`

### Added

- `CacheKeys` 單元測試（null 處理、決定性、輸出格式）

---

## [2026-04-28] — redis 1.2.2 · s3 1.1.2

### Fixed

- 修正所有底線命名的 `readFrom` 策略（如 `ANY_REPLICA`）誤判為未知策略而產生 false-positive WARN 的問題

### Changed

- 簡化 `KnoluxS3ClientFactory` 的 `pathPrefix` 處理邏輯並改善變數命名
- 升級 AWS SDK v2 BOM `2.42.41` → `2.43.0`

---

## [2026-04-26] — redis 1.2.1 · s3 1.1.1

### Changed

- 統一程式碼格式與文件註解風格
- 升級 Spring Boot `4.0.5` → `4.0.6`
- 升級 AWS SDK v2 BOM `2.31.40` → `2.42.41`
- 升級 Testcontainers `1.20.6` → `1.21.4`、`com.redis:testcontainers-redis` `2.2.2` → `2.2.4`
- 升級 GitHub Actions：`actions/checkout` v4→v6、`actions/setup-java` v4→v5、`actions/upload-artifact` v4→v7、`gradle/actions` v4→v6

---

## [2026-04-26] — redis 1.2.0 · s3 1.1.0

### Added

- **S3：Virtual Thread 支援** — `spring.threads.virtual.enabled=true` 時註冊 `knoluxS3Executor`（`newVirtualThreadPerTaskExecutor`），供 `CompletableFuture` 完成回呼使用；未啟用時退回 `ForkJoinPool.commonPool()`
- **Redis：** `readFrom` 改為委派 `Lettuce.valueOf`，支援 Lettuce 全部讀取策略
- Redis 遇到未知 `readFrom` 策略時輸出 WARN log
- 兩個模組新增 `package-info.java`
- 導入 Dependabot，並最佳化 Gradle 快取與平行建置策略

### Changed

- **S3：** 抽出 `S3ClientProvider` 介面以符合 DIP，`KnoluxS3Template` 改依賴抽象
- **S3：** 抽出 `S3HttpClientFactory` 分離 HTTP client 建立職責（SRP）
- **Redis：** 抽出 `LettuceConnectionFactoryBuilder` 策略介面（SRP / OCP）
- README 全面改以繁體中文撰寫，補充 SOLID 架構與 Virtual Thread 說明

### Fixed

- **S3：** Spring 容器關閉時正確 close Virtual Thread executor，避免資源洩漏
- **Redis：** `KnoluxRedisHealthIndicator` 改由 AutoConfiguration 以 `@Bean` 註冊（移除 `@Component`）
- **Redis：** 修正 `KnoluxRedisAutoConfiguration` 的 package 位置，builder 改為 public

---

## [2026-04-20] — s3 1.0.1

### Changed

- S3 模組的 AWS SDK 依賴由 `implementation` 改為 `api`，使下游可直接取用 SDK 型別

---

## [2026-04-20] — redis 1.1.1 · s3 1.0.0

### Added

- **`knolux-s3-spring-boot-starter` 首次發布** — 封裝 AWS SDK v2 非同步 S3 client，支援 SeaweedFS / MinIO 與標準 AWS S3；提供靜態、動態、進階三層 API
- S3 與 Redis 的整合測試與單元測試

---

## [2026-04-19] — redis 1.1.0

### Changed

- CI 與 Publish workflow 改用 Java 25
- 修正 `publish.yml` 的輸出重導向語法

---

## [2026-04-17] — redis 1.0.2

### Changed

- 專案版本改為 `0.0.0-SNAPSHOT`，實際版本由 tag 於發布時以 `-Pversion=` 注入
- 所有 GitHub Actions workflow 改用 Node.js 24

---

## [2026-04-17] — redis 1.0.1

### Changed

- Javadoc 部署改為推送至 `gh-pages` 分支，並依模組分子目錄存放

---

## [2026-04-16] — redis 1.0.0

### Added

- **`knolux-redis-spring-boot-starter` 首次發布** — Redis auto-configuration，支援 standalone / sentinel / cluster 連線模式
- `KnoluxRedisHealthIndicator`（Actuator 為 `compileOnly`，未引入時不啟用）
- 繁體中文 Javadoc 與 GitHub Pages 發布 workflow
- GitHub Actions CI 與 GitHub Packages 發布 workflow

[Unreleased]: https://github.com/knolux/knolux-spring-boot-starters/compare/knolux-redis-spring-boot-starter/v1.4.0...HEAD
