<!--
Sync Impact Report
==================
Version change: (template, unversioned) → 1.0.0
Bump rationale: MAJOR — 首次批准，將全樣板文件轉為具體治理條文。

Modified principles:
  [PRINCIPLE_1_NAME] → I. 測試驅動開發（不可協商）
  [PRINCIPLE_2_NAME] → II. SOLID
  [PRINCIPLE_3_NAME] → III. 六角形架構與關注點分離
  [PRINCIPLE_4_NAME] → IV. Fail-Fast 與可觀測性
  [PRINCIPLE_5_NAME] → V. 可擴展性與可覆寫性
  (new)              → VI. 命名與風格一致性

Added sections:
  [SECTION_2_NAME]   → 技術與相容性約束
  [SECTION_3_NAME]   → 開發流程與品質閘門

Removed sections: 無

Follow-up TODOs: 無（所有 placeholder 皆已填實）

Consistency notes:
  - CLAUDE.md「品質準則」為本文件的執行期摘要，兩者須同步；本次內容自該節推導，維持一致。
  - .specify/templates/ 之 plan/spec/tasks 樣板於執行期讀取本文件，未修改。
-->

# knolux-spring-boot-starters Constitution

## Core Principles

### I. 測試驅動開發（不可協商）

紅燈 → 綠燈 → 重構，順序不得顛倒。

- 新增或修改行為時，MUST 先寫出會失敗的測試，再寫讓它通過的最小實作，最後重構。
- 修 bug 時 MUST 先寫出能重現該 bug 的測試；該測試在修復前必須失敗。
- 禁止「先實作、後補測試」。以既有測試涵蓋為由略過紅燈階段亦不被接受。
- Auto-configuration 行為 MUST 以 `ApplicationContextRunner` 測試，不啟動 `@SpringBootTest`；
  需要真實中介軟體時才使用 Testcontainers，並加上 Docker 可用性 `Assumptions.abort(...)` 守衛。

**理由**：本專案為函式庫，缺陷會直接外溢到所有下游應用。先寫測試是唯一能確保「規格先於實作」
且回歸可被機器攔截的做法；Docker 守衛則讓無 Docker 環境不會把建置變成偽紅燈。

### II. SOLID

新增職責時 MUST 以既有的拆分方式比照辦理，不得把邏輯堆進既有類別。

- **SRP**：連線工廠、HTTP client 工廠、簽章器、URI 解析、快取鍵雜湊各自獨立成類別
  （`S3HttpClientFactory`、`CacheKeys`、`RedisUriUtils` 等）。
- **OCP**：新增 Redis 連線模式＝新增 `LettuceConnectionFactoryBuilder` 實作並加入清單，
  MUST NOT 修改既有分支邏輯。
- **DIP**：高層元件 MUST 依賴介面（`KnoluxS3Template` → `S3ClientProvider`），不得直接依賴實作。

**理由**：Starter 的價值在於使用者能安全地替換其中任一環節。職責一旦混雜，覆寫就會變成
「複製整段程式碼再改」，`@ConditionalOnMissingBean` 的擴充點也隨之失效。

### III. 六角形架構與關注點分離

以 port-adapter 觀點維護既有邊界，四類角色不得混用：

- **Port（介面）**：`S3ClientProvider`、`LettuceConnectionFactoryBuilder`
- **Adapter（實作）**：`KnoluxS3ClientFactory`、`Standalone/SentinelConnectionFactoryBuilder`、
  `KnoluxNoPathPrefixSigner`
- **Composition root**：`@AutoConfiguration` 類別 MUST 只負責組裝與條件判斷，
  MUST NOT 放入業務邏輯或協定細節
- **設定邊界**：`*Properties`（外部設定）→ `*ConnectionDetails` / `*OperationSpec`（不可變值物件）
  → adapter。Spring 的設定型別 MUST NOT 滲入底層工廠。

**理由**：底層工廠一旦吃進 `*Properties`，就無法被單獨測試，也無法在動態模式（執行期 payload
提供連線參數）下重用。不可變值物件是這條邊界的具體載體。

### IV. Fail-Fast 與可觀測性

靜默 fallback 是本專案明確反對的模式。

- 無法確定語意的輸入 MUST 拋出例外而非退回預設值。既有落點：未知 URI scheme（含尚未支援的
  `rediss://`）、非數字的 DB 區段（`RedisUriUtils.parseDb`）。
- `supports()` 類的策略比對 MUST 嚴格比對，MUST NOT 使用 catch-all 分支。
- 例外訊息與 WARN log MUST 帶上實際值與修正提示。降級路徑（例如 `trustSelfSigned`、
  path-prefix 不符、未知 `readFrom`）MUST 留下可診斷訊號。
- 修改上述任一防呆點時，MUST 同步更新記錄該決策成因的註解。

**理由**：把 `rediss://` 靜默當成明文連線，或把打錯的 DB 索引退回 0，代價是生產環境的
資料外洩與資料寫錯位置。這類錯誤在啟動時炸掉，遠比在流量高峰才被發現便宜。

### V. 可擴展性與可覆寫性

- 新增能力 SHOULD 優先採「新增實作 + 註冊」，而非修改既有流程。
- 所有對外 Bean MUST 標注 `@ConditionalOnMissingBean`，讓使用者可完全覆寫。
- 選用依賴（如 Actuator）MUST 以 `compileOnly` 引入，並以 `@ConditionalOnClass` 守衛，
  classpath 未含該依賴時不得影響裝配。
- 持有原生資源的 Bean MUST 定義明確的生命週期（例如 `destroyMethod = "close"`），
  且資源 MUST 在可能失敗的建構步驟之前登記，確保失敗時仍可回收。

**理由**：使用者無法覆寫的 starter 等同於技術債。強制 `@ConditionalOnMissingBean` 讓
「不符需求」永遠有出路，不必 fork。

### VI. 命名與風格一致性

- 對外型別 MUST 使用 `Knolux` 前綴。
- 設定屬性 MUST 以 kebab-case 對應 camelCase 欄位。
- 套件 MUST 依關注點分層（如 `com.knolux.redis.connection`）。
- 角色命名：工廠為 `*Factory`、策略介面為 `*Builder` / `*Provider`、值物件為 `record`。
- 新命名 MUST 先確認與既有慣例一致再落地。
- Javadoc 與註解 MUST 為繁體中文；public API MUST 說明使用情境與設定範例。
- 註解 MUST 說明「為什麼」而非「做什麼」，特別是防呆／fail-fast 決策的成因。

**理由**：本 repo 無 checkstyle / spotless，一致性完全靠慣例與 code review 維持。
慣例只要寫成明文，審查就有依據，不需要逐案爭論。

## 技術與相容性約束

- **Java 25 toolchain**（Temurin）。可積極使用 Java 21+ / 25 語法：record pattern、
  unnamed pattern `_`、switch expression、text block。
- **Spring Boot 4.1.0+**。依賴版本集中於 `gradle/libs.versions.toml`；多數 library
  MUST NOT 寫死版號，由 Spring Boot BOM 決定（AWS SDK 例外，使用自己的 BOM）。
- 根 `build.gradle.kts` 統一套用 java-library / maven-publish / BOM / JUnit Platform /
  Javadoc 設定；模組層的 `build.gradle.kts` MUST 只宣告 `description` 與 `dependencies`。
- 兩個 starter MUST 保持互相獨立，MUST NOT 產生跨模組依賴。
- 各模組 `gradle.properties` 恆為 `version=0.0.0-SNAPSHOT`，MUST NOT 手動修改；
  實際版號由 publish workflow 從 git tag 解析後以 `-Pversion=` 注入。
- Lombok 僅限 `@Getter` / `@Setter` / `@Builder` / `@Slf4j` / `@RequiredArgsConstructor`，
  且 MUST 為 `compileOnly` + `annotationProcessor`。
- 每個 starter MUST 只透過
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  註冊單一 `@AutoConfiguration` 類別。

## 開發流程與品質閘門

**規格驅動開發（SpecKit）**

`/speckit-specify`（規格）→ `/speckit-plan`（技術方案）→ `/speckit-tasks`（任務拆解）
→ `/speckit-implement`（實作），再進入 TDD 循環。可選：`/speckit-clarify`、
`/speckit-analyze`、`/speckit-checklist`。

規格是產出物的來源，不是事後補的文件。實作偏離規格時 MUST 回頭修改規格，
MUST NOT 讓兩者長期不一致。

**變更前的影響分析（GitNexus）**

- 修改任何 function / class / method 前 MUST 執行
  `gitnexus_impact({target, direction: "upstream"})`，並回報 blast radius。
- 影響分析回報 HIGH 或 CRITICAL 風險時 MUST 明確告知，MUST NOT 逕行忽略。
- 重新命名符號 MUST 使用 `gitnexus_rename`，MUST NOT 用 find-and-replace。
- Commit 前 MUST 執行 `gitnexus_detect_changes()` 確認影響範圍符合預期。
- 索引過期時直接執行 `npx gitnexus analyze` 更新。

**Commit 與發布**

- Commit message MUST 遵循 Conventional Commits（`feat:` / `fix:` / `docs:` / `test:` /
  `refactor:` / `chore:` / `build:` / `style:`）。
- Commit message MUST NOT 包含 `Co-Authored-By: Claude ...` 或任何 AI 助手署名。
- 發布流程 MUST 依序進行：
  1. 分支開發 → PR → `dev`（預發布 / RC）
  2. `dev` 累積至階段點
  3. `dev` → PR → `main`（正式 / GA）
  4. 打上 module-scoped tag（`<module>/v<version>`）並發布 Release
  5. 更新 `CHANGELOG.md`
- 發版後 MUST 同步 README 中的安裝版號。

**品質閘門**

- `./gradlew test --continue` MUST 全綠才能合併（CI 使用的指令）。
- 整合測試在有 Docker 的環境 MUST 實際執行；以 skip 數量掩蓋未驗證的變更不被接受。
- 違反本文件任一原則時，MUST 在 PR 或回覆中明確說明取捨理由，MUST NOT 默默略過。

## Governance

本文件優先於其他慣例。與 `CLAUDE.md`、README 或既有程式碼衝突時，以本文件為準，
並同步修正衝突來源。

**修訂程序**：修訂 MUST 透過 `/speckit-constitution` 進行，並隨附 Sync Impact Report；
修訂 MUST 走一般 PR 流程審查後合併，MUST NOT 直接推送至 `main`。

**版本政策**：本文件版本遵循語意化版本。

- MAJOR：移除或重新定義原則，導致既有做法不再合規
- MINOR：新增原則或章節，或實質擴充既有指引
- PATCH：釐清用語、修正錯字等不改變語意的調整

**合規審查**：所有 PR 審查 MUST 檢視是否符合本文件；額外的複雜度 MUST 有明確理由。
執行期的日常開發指引見 `CLAUDE.md`，該檔案為本文件的摘要，MUST 與本文件保持一致。

**Version**: 1.0.0 | **Ratified**: 2026-08-01 | **Last Amended**: 2026-08-01
