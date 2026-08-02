# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 互動與協作守則

- **一律使用繁體中文回答**（程式碼、識別字、指令保持原文）。本專案的 Javadoc、註解、README 皆為繁體中文，請維持一致。
- **搭配 GitNexus 進行開發** —— 詳細規範見本檔案最下方的 GitNexus 區塊。查詢結構、追蹤呼叫關係、評估影響時優先用 GitNexus 而非純文字搜尋。
  **索引過期時直接執行 `npx gitnexus analyze` 更新，不必先徵詢。**
- **採用 SpecKit（Spec-Driven Development）流程**：`/speckit-specify`（規格）→ `/speckit-plan`（技術方案）→ `/speckit-tasks`（任務拆解）→ `/speckit-implement`（實作），再進入 TDD 循環。
  規格是產出物的來源，不是事後補的文件；實作偏離規格時回頭改規格，而非讓兩者不一致。
  可選：`/speckit-clarify`（plan 前釐清模糊點）、`/speckit-analyze`（implement 前跨產出物一致性檢查）、`/speckit-checklist`。
  專案原則的正式來源是 `.specify/memory/constitution.md`（以 `/speckit-constitution` 維護）；下方「品質準則」與該檔案須保持一致。
- **Commit message 不得加入 `Co-Authored-By: Claude ...` 或任何 Claude 署名。** 沿用專案既有的 Conventional Commits 風格（`feat:` / `fix:` / `docs:` / `test:` / `refactor:` / `chore:` / `build:` / `style:`）。

## 品質準則

新增或修改程式碼一律遵守下列原則；違反時應在 PR / 回覆中明確說明取捨理由。

- **TDD** —— 先寫會失敗的測試，再寫讓它通過的最小實作，最後重構。修 bug 時先寫能重現該 bug 的測試。不得先實作後補測試。
- **SOLID** —— 本專案已有的具體落點：
  - SRP：連線工廠、HTTP client 工廠、簽章器、URI 解析、快取鍵雜湊各自獨立成類別（`S3HttpClientFactory`、`CacheKeys`、`RedisUriUtils`…），新增職責時比照拆分。
  - OCP：新增 Redis 連線模式＝新增 `LettuceConnectionFactoryBuilder` 實作並加入清單，不改既有分支邏輯。
  - DIP：高層元件依賴介面（`KnoluxS3Template` → `S3ClientProvider`），不直接依賴實作。
- **六角形架構 / 乾淨架構 / 關注點分離** —— 以 port-adapter 觀點維護既有邊界：
  - **Port（介面）**：`S3ClientProvider`、`LettuceConnectionFactoryBuilder`
  - **Adapter（實作）**：`KnoluxS3ClientFactory`、`Standalone/SentinelConnectionFactoryBuilder`、`KnoluxNoPathPrefixSigner`
  - **Composition root**：`@AutoConfiguration` 類別只負責組裝與條件判斷，**不得**放入業務邏輯或協定細節
  - **設定邊界**：`*Properties`（外部設定）→ `*ConnectionDetails` / `*OperationSpec`（不可變值物件）→ adapter；不要讓 Spring 的設定型別滲入底層工廠
- **可擴展性** —— 新增能力優先用「新增實作 + 註冊」的方式；所有對外 Bean 維持 `@ConditionalOnMissingBean` 讓使用者可覆寫。
- **可觀測性** —— 錯誤與降級路徑必須留下可診斷訊號：例外訊息與 WARN log 需帶上實際值與修正提示（既有程式碼刻意如此，例如未知 `readFrom`、path-prefix 不符、`trustSelfSigned` 安全警告）。靜默 fallback 是本專案明確反對的模式。
- **命名與風格一致性** —— 對外型別一律 `Knolux` 前綴；設定屬性用 kebab-case 對應 camelCase 欄位；套件依關注點分層（如 `com.knolux.redis.connection`）；工廠類別命名為 `*Factory`、值物件為 record、策略介面為 `*Builder` / `*Provider`。新命名先確認與既有慣例一致再落地。

## 建置與測試

```bash
./gradlew build                                    # 全專案編譯 + 測試
./gradlew test --continue                          # 全量測試（CI 使用的指令）
./gradlew :knolux-redis-spring-boot-starter:test   # 單一模組
./gradlew :knolux-s3-spring-boot-starter:test

# 單一測試類別 / 單一方法
./gradlew :knolux-redis-spring-boot-starter:test --tests KnoluxRedisAutoConfigurationTest
./gradlew :knolux-s3-spring-boot-starter:test --tests 'com.knolux.s3.CacheKeysTest.sha256Hex_isDeterministicAnd64HexChars'

./gradlew javadoc                                  # 產生 Javadoc（locale zh_TW）
```

- 需要 **Java 25 toolchain**（Temurin）。無 checkstyle / spotless 等 lint 任務，格式一致性靠 code review。
- `*IntegrationTest` 走 Testcontainers；未啟動 Docker 時以 `Assumptions.abort(...)` 自動跳過，**不會**讓建置失敗。
- `KnoluxS3RealEndpointTest` 標記 `@Disabled`，是連真實 SeaweedFS 的人工驗收測試，憑證從 `S3_ACCESS_KEY` / `S3_SECRET_KEY` 環境變數讀取。

### 依賴相容性閘門

```bash
./gradlew checkDependencyCompatibility             # 比對外溢依賴，偵測 major 跳動與依賴移除（CI 執行）
./gradlew checkDependencyCompatibility --base=origin/dev   # 指定比較基準；省略時依序嘗試 origin/dev、origin/main
./gradlew updateDependencyBaseline                 # 重新產生基準線檔，**發版前必跑**
./gradlew checkDependencyBaseline                  # 驗證基準線與當前解析逐字相同（publish workflow 執行）
./gradlew dependencyChangeReport --since=knolux-redis-spring-boot-starter/v1.3.0   # 產生升級揭露，永不失敗

./gradlew -p buildSrc test                         # 閘門自身的單元測試（主建置不會連帶執行）
```

- 兩個 starter 都以 `api` scope 曝露核心依賴，**傳遞依賴的 major 跳動等同對下游的破壞性變更**，即使自有原始碼一行未改。此閘門就是為了在合併前而非發布後攔下這件事。
- 閘門**刻意不掛在 `check` 之下**：`./gradlew build` 的行為完全不受影響，只有 CI 步驟與人工顯式呼叫會觸發。
- 閘門邏輯位於 `buildSrc/src/main/kotlin/com/knolux/build/depgate/`，純邏輯（版本解析、差異分類、核准比對）與 Gradle task 分離，因此可獨立單元測試——**修改閘門必須先寫失敗測試**，與主專案同一標準。
- 判定的寬嚴刻意分成兩級：`checkDependencyCompatibility`（合併前）容忍非阻擋性的基準線落差、僅警告，否則 Dependabot 的每週 PR 會全數紅燈；`checkDependencyBaseline`（發版前）任何落差皆失敗，因為發布不可回收。改動任一側前先讀 `specs/001-dep-breaking-change-gate/spec.md` 的 FR-011a 與 FR-024。

## 專案結構與版本機制

Gradle 多模組（Kotlin DSL），根 `build.gradle.kts` 對所有 subprojects 統一套用 java-library / maven-publish / Spring Boot BOM（4.1.0）/ JUnit Platform / Javadoc 設定 —— **模組層的 `build.gradle.kts` 只宣告 `description` 與 `dependencies`**。

- 依賴版本集中於 `gradle/libs.versions.toml`；多數 library 不寫版號，由 Spring Boot BOM 決定（AWS SDK 例外，用自己的 BOM）。
- 每個模組的 `gradle.properties` 恆為 `version=0.0.0-SNAPSHOT`，**不要手動改**。實際版號由 publish workflow 從 git tag 解析後以 `-Pversion=` 注入。
- 發布由 module-scoped tag 觸發：`git tag knolux-redis-spring-boot-starter/v1.0.1`。同一個 tag 同時觸發 Publish（GitHub Packages）與 Javadoc（gh-pages）兩個 workflow。
- README 中的安裝版號需在發版後手動同步。
- **打 tag 前必須先執行 `./gradlew updateDependencyBaseline` 並把變更一併提交**。publish workflow 會在發布前跑 `checkDependencyBaseline`，落差會讓發布失敗——屆時只能補基準線、刪 tag、重打，成本遠高於發版前先跑一次。

依賴閘門的兩份簽入檔案：

| 檔案 | 用途 | 何時更新 |
|---|---|---|
| `gradle/dependency-baseline/<module>.txt` | 每個模組外溢給下游的 resolved 依賴集合，是閘門比對的另一側 | 發版前必更；日常 PR 若依賴有變動也應一併更新（非阻擋性落差只會警告，但留著不真實的檔案是給下一個人挖坑） |
| `gradle/dependency-approvals.toml` | 對特定模組／座標／版本區間的破壞性變更核准，含 `reason` 供事後查閱 | 需要放行某項阻擋性變更時新增；**逐字比對**版本，改了版號就要重新核准，刻意不做成全域開關 |

兩份檔案都會出現在 PR diff 裡——這是選擇「簽入基準線」而非「執行期重建」的主要理由：依賴的變動變成可審閱的事件，而非埋在 CI log 裡。

## 架構

兩個彼此獨立的 starter，各自透過 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 註冊單一 `@AutoConfiguration` 類別。共通原則：**所有 Bean 都標注 `@ConditionalOnMissingBean`**，使用者可完全覆寫；Actuator 為 `compileOnly`，健康指標僅在 classpath 有 Actuator 時建立。

### knolux-redis-spring-boot-starter

由 `knolux.redis.url` 的 URI scheme 決定連線模式，採**策略模式**：

```
KnoluxRedisAutoConfiguration
  └─ BUILDERS: List<LettuceConnectionFactoryBuilder>
       ├─ SentinelConnectionFactoryBuilder    (redis-sentinel://)
       └─ StandaloneConnectionFactoryBuilder  (redis://)
```

- 新增連線模式（如 Cluster）＝新增一個 builder 實作並加入 `BUILDERS` 清單，不動 auto-configuration（OCP）。
- **不使用 catch-all fallback**：`supports()` 嚴格比對 scheme，未知 scheme（含尚未支援的 `rediss://`）會 fail-fast 拋出例外，避免被靜默當成明文連線。同理 `RedisUriUtils.parseDb` 對非數字 DB 區段拋例外而非退回 DB 0。
- URL 解析邏輯全部集中在 `RedisUriUtils`，可獨立單元測試。
- `readFrom` 為 `MASTER` / `UPSTREAM` 時刻意不設定 `LettuceClientConfiguration.readFrom`，讓 Lettuce 不啟動 topology refresh。

### knolux-s3-spring-boot-starter

```
KnoluxS3Template ──依賴──> S3ClientProvider (介面, DIP)
                              └─ KnoluxS3ClientFactory (預設實作，含快取)
                                   ├─ S3HttpClientFactory        (Netty client 建立)
                                   └─ KnoluxNoPathPrefixSigner   (Nginx 前綴簽章修正)
```

**三層 API**（`KnoluxS3Template`）：靜態模式（用 Properties 預設連線）／動態模式（`KnoluxS3OperationSpec` 從 payload 組裝）／進階模式（明確傳入 `KnoluxS3ConnectionDetails`）。

需要理解的幾個跨檔案設計：

1. **動態模式的安全邊界** —— `forcePathStyle` / `removePathPrefix` / `pathPrefix` / `trustSelfSigned` 屬部署級別設定，`KnoluxS3OperationSpec.mergeDefaults()` 一律以 `KnoluxS3Properties` 覆寫，呼叫端無法用 payload 繞過。自動裝配的 template 持有 Properties，會在動態模式方法中自動套用 `mergeDefaults`（冪等），即使呼叫端忘記呼叫也安全。
2. **Client 快取鍵** —— `KnoluxS3ConnectionDetails.toCacheKey()` 排除 `secretKey`、對 `accessKey` 取 SHA-256（`CacheKeys`）。實作刻意使用 record 解構模式：**日後新增欄位會編譯失敗**，強迫開發者決定該欄位是否納入 key。
3. **Nginx 路徑前綴** —— `pathPrefix` 會附加到 endpoint（讓 Nginx 能路由），而 `KnoluxNoPathPrefixSigner` 以「移除前綴後的短路徑」計算簽章，再把 `Authorization` / `x-amz-*` header 複製回長路徑請求。endpoint 串接與簽章器**必須共用同一個 `normalizePathPrefix()` 結果**，否則會 403。
4. **資源生命週期** —— `KnoluxS3ClientFactory` 同時快取 `S3AsyncClient` 與 `SdkAsyncHttpClient`；HTTP client 在 `S3AsyncClient.build()` **之前**入快取，確保 build 失敗時也能回收。Bean 以 `destroyMethod = "close"` 註冊；`close()` 不等待進行中的 I/O。
5. **Virtual Thread** —— `knoluxS3Executor` Bean 在 `spring.threads.virtual.enabled=true` 時回傳 virtual-thread-per-task executor，否則用 `ForkJoinPool.commonPool()`，供 `whenCompleteAsync` 回呼使用。
6. `SdkAdvancedClientOption.SIGNER` 已被 AWS SDK 標記 deprecated，但 async pipeline 的 `SigningStage` 仍同步執行此 SPI，因此對 `S3AsyncClient` 有效；遷移至 `HttpSigner` 為已知待辦。

## 撰寫慣例

- **Javadoc 為繁體中文且份量充足**：public API 需說明使用情境與設定範例；新增類別請比照既有密度。
- **註解說明「為什麼」而非「做什麼」**：既有程式碼中多處註解記錄了某個防呆／fail-fast 決策的成因（例如為何不用 catch-all、為何用 record 解構、為何 secretKey 不入 key），修改該處邏輯時請同步更新這些理由。
- Lombok 僅用 `@Getter` / `@Setter` / `@Builder` / `@Slf4j` / `@RequiredArgsConstructor`，且為 `compileOnly` + `annotationProcessor`。
- 積極使用 Java 21+ / 25 語法：record pattern、unnamed pattern `_`、switch expression、text block。
- 例外訊息與 WARN log 需可診斷（帶上實際值與修正提示），這是專案刻意維持的風格。
- 測試：auto-configuration 用 `ApplicationContextRunner`（不啟 `@SpringBootTest`）；需要真實連線才用 Testcontainers 並加 Docker 可用性 assumption。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **knolux-spring-boot-starters** (918 symbols, 1946 relationships, 39 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/knolux-spring-boot-starters/context` | Codebase overview, check index freshness |
| `gitnexus://repo/knolux-spring-boot-starters/clusters` | All functional areas |
| `gitnexus://repo/knolux-spring-boot-starters/processes` | All execution flows |
| `gitnexus://repo/knolux-spring-boot-starters/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
