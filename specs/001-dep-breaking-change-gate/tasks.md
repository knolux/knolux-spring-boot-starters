# Tasks: 傳遞依賴破壞性變更閘門

**Input**: Design documents from `/specs/001-dep-breaking-change-gate/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)

**Tests**: 全面納入。憲章「I. 測試驅動開發（不可協商）」明訂紅燈 → 綠燈 → 重構順序不得顛倒，
且本功能選擇 `buildSrc/` 而非內嵌腳本的**決定性理由**就是可單元測試（research.md R4）。
每個純邏輯元件皆為「先寫失敗測試」與「寫最小實作」兩個獨立任務，兩者 MUST NOT 合併執行。

**Organization**: 依 user story 分組，每組可獨立實作與驗證。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可平行執行（不同檔案、不依賴未完成任務）
- **[Story]**: 對應的 user story（US1 / US2 / US3 / US4）

## Path Conventions

本功能全部位於建置邏輯層，**不觸碰任何會進入發布 artifact 的程式碼**：

- 純邏輯與 adapter：`buildSrc/src/main/kotlin/com/knolux/build/depgate/`
- 任務類別（composition root）：`buildSrc/src/main/kotlin/com/knolux/build/depgate/task/`
- 測試：`buildSrc/src/test/kotlin/com/knolux/build/depgate/`
- 測試 fixture：`buildSrc/src/test/resources/fixtures/`
- 產生物與設定：`gradle/dependency-baseline/`、`gradle/dependency-approvals.toml`
- 兩個 starter 的 `build.gradle.kts` 與 `src/`：**零變更**

---

## Phase 1: Setup（buildSrc 骨架）

**Purpose**: 讓「可單元測試的建置邏輯」這件事先成立。此階段完成前，TDD 無法開始。

- [ ] T001 建立 `buildSrc/build.gradle.kts`：套用 `kotlin-dsl` plugin，加入 `implementation("org.tomlj:tomlj:<version>")`、JUnit 5 測試依賴（`testImplementation` + `testRuntimeOnly` junit-jupiter），並設定 `tasks.test { useJUnitPlatform() }`。版本直接寫在此檔——buildSrc 無法取用 `gradle/libs.versions.toml` 的 version catalog，此為 Gradle 限制而非慣例偏離，需於檔內註解說明
- [ ] T002 建立冒煙測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/BuildSrcSmokeTest.kt`（單一恆真斷言），執行 `./gradlew -p buildSrc test` 確認綠燈，證實測試基礎設施可用
- [ ] T003 實測 Gradle 9.6.1 的主建置（`./gradlew build`）是否自動執行 buildSrc 的 `test` 任務，將實測結論回填 `specs/001-dep-breaking-change-gate/research.md` 的 R4「待實作時驗證的事項」。**此結論決定 T058 是否必要**——若未自動執行而 CI 也不明確執行，閘門自身的測試將從未跑過

**Checkpoint**: `./gradlew -p buildSrc test` 可執行且綠燈 —— TDD 循環可以開始

---

## Phase 2: Foundational（純邏輯核心）

**Purpose**: 四個 user story 全部建立在這一層之上。此階段未完成前，任何 user story 都無法開始。

**⚠️ CRITICAL**: 此階段的每一項實作任務，其對應的測試任務 MUST 先執行並確認失敗。

**架構歸屬**：T004 ~ T013 為純函式（無 I/O、不知道 git 與 Gradle 的存在）；
T014 ~ T018 為 port 與 adapter（憲章 III）。兩者的邊界不得模糊。

### 版本解析（FR-008 / FR-009）

- [ ] T004 [P] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/ArtifactVersionTest.kt`：正向涵蓋本專案實際出現的全部形式 `7.5.2.RELEASE`、`4.2.15.Final`、`2.49.3`、`2.6`（minor 缺項補 0）、`1.5.34`；負向涵蓋 `latest.release`、`master-SNAPSHOT`、空字串、超出 `Int` 範圍的數字段 → 皆回傳 `Unparseable` 且 `reason` **帶出原始字串**。額外斷言：`parse` MUST NOT 回傳 null、MUST NOT 拋例外（FR-009 要根除的正是靜默放行）
- [ ] T005 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/ArtifactVersion.kt`：`data class` 含 `raw` / `major` / `minor` / `patch` / `qualifier`，以 sealed `ParseResult`（`Parsed` / `Unparseable`）回傳，依 data-model.md §1 與 research.md R5 的切分規則（以 `.` 或 `-` 切分，取前導數字段）

### 座標與集合

- [ ] T006 [P] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/DependencyCoordinateTest.kt`：斷言 `toString()` 與 `parse()` 互為反函數，涵蓋含 `.` 與 `-` 的 group / artifact
- [ ] T007 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/DependencyCoordinate.kt`（data-model.md §2）
- [ ] T008 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/DependencySetTest.kt`：斷言序列化為**決定性**輸出——完整行字典序、LF 換行、檔尾單一換行、UTF-8 無 BOM、標頭警語；剖析時忽略 `#` 註解與空行；**版本欄保留原字串不正規化**（`7.5.2.RELEASE` 不得變成 `7.5.2`）。序列化不穩定會讓基準線檔產生無意義 diff，摧毀 R2 決策的核心價值，故此為硬性斷言
- [ ] T009 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/DependencySet.kt`：含 `serialize()` 與 `parse()`，格式依 contracts/file-formats.md §1

### 差異分類（FR-006 / FR-007 / FR-007a）

- [ ] T010 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/DeltaKindTest.kt`：逐一斷言七種 `DeltaKind` 的 `blocking` 屬性（`MAJOR` / `REMOVED` / `DOWNGRADE` / `UNPARSEABLE` 為 true；`MINOR` / `PATCH` / `ADDED` 為 false），以及 `DependencyDelta` 的不變條件（`ADDED` ⇒ `from == null`；`REMOVED` ⇒ `to == null`；其餘兩者皆非 null）
- [ ] T011 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/DeltaKind.kt` 與 `DependencyDelta.kt`：`blocking` 為 `DeltaKind` 的固有屬性（`val blocking: Boolean`），MUST NOT 由呼叫端各自判斷（data-model.md §4）
- [ ] T012 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/DeltaCalculatorTest.kt`：七種類別各至少一例；`0.5.0 → 0.6.0` 判定為 `MAJOR`（FR-007a）；`6.x → 8.x` 為**單一項** `MAJOR` 且完整保留前後版本；僅 qualifier 變動（`1.0.0.RELEASE → 1.0.0.Final`）為 `PATCH`；版本後退為 `DOWNGRADE` 而非 `MINOR`；任一側無法解析為 `UNPARSEABLE` 且帶出原字串
- [ ] T013 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/DeltaCalculator.kt`：純函式，輸入兩個 `DependencySet` 輸出 `List<DependencyDelta>`

### Port 與 Adapter（憲章 III）

- [ ] T014 [P] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/FileBaselineSourceTest.kt`：檔案存在 → 回傳解析後的 `DependencySet`；檔案不存在 → 回傳「無基準線」而非拋例外（FR-005 要求跳過而非失敗）
- [ ] T015 定義 port `buildSrc/src/main/kotlin/com/knolux/build/depgate/BaselineSource.kt` 並實作 adapter `FileBaselineSource.kt`
- [ ] T016 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/GitBaselineSourceTest.kt`：以 JUnit `@TempDir` 建立臨時 git repo（`git init` + commit 一份基準線檔）驗證 `git show <ref>:<path>` 取值正確；ref 不存在、檔案在該 ref 不存在 → 皆回傳「無基準線」（research.md R6）
- [ ] T017 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/GitBaselineSource.kt`：外呼 `git show <ref>:<path>`，這是整個功能中唯一接觸 git 的位置
- [ ] T018 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/ResolvedDependencyReader.kt`：自 `configurations.getByName("runtimeClasspath").incoming.resolutionResult.allComponents` 取 `ModuleComponentIdentifier`，排除本 repo 自身的 project 依賴。**MUST NOT 剖析 `dependencies` 任務的文字輸出**——research.md R1 已實證該輸出混雜請求版本與解析版本（Netty `4.2.13.Final` 與 `4.2.15.Final` 同時出現），文字剖析會靜默產生錯誤結果

**Checkpoint**: 純邏輯層與 adapter 層完成且測試全綠 —— 四個 user story 可開始（若人力允許可平行）

---

## Phase 3: User Story 1 - 合併前攔截破壞性依賴變更 (Priority: P1) 🎯 MVP

**Goal**: PR 上出現未經核准的 major 跳動、依賴移除或版本後退時，建置失敗並逐筆指出模組、座標、`from → to`、類別。

**Independent Test**: 建立刻意讓某依賴跨 major 的分支，執行 `./gradlew checkDependencyCompatibility`，驗證建置失敗且訊息含依賴座標與前後版本（quickstart 情境 2）。

**MVP 範圍說明**：此階段完成後閘門即可擋 PR，但**尚無核准途徑**（US4）。在 US4 完成前不應合併進 `dev`——否則任何刻意的 major 升級都將無法合併。

### 判定與報告（TDD）

- [ ] T019 [US1] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/GateVerdictTest.kt`：狀態推導三分支——基準線不存在 → `SKIPPED_NO_BASELINE` 且 `skipReason` 非空；`blockedDeltas` 非空 → `BLOCKED`；其餘 → `PASSED`
- [ ] T020 [US1] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/GateVerdict.kt` 與 `GateReport.kt`（data-model.md §7 / §8）
- [ ] T021 [US1] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/ReportRendererTest.kt`：涵蓋 report-format.md 的情形 A（阻擋）、C（無變動）、D（跳過模組）、E（無法解析）。硬性斷言：說明文字為繁體中文（FR-022）、阻擋性與資訊性為**不同的區塊標題**（FR-020）、表格為標準 GFM 不依賴渲染擴充（FR-021）、版本字串原文呈現、依賴座標以反引號包覆
- [ ] T022 [US1] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/ReportRenderer.kt`：純函式，`GateReport` → Markdown 字串。「阻擋性變動」區塊的表格結構須與 `CHANGELOG.md:20-34` 既有的「⚠️ 升級前必讀」寫法對齊，使其可原文貼入

### SC-001 回歸測試（本功能存在的理由）

- [ ] T023 [US1] 擷取 SC-001 的**雙側** fixture 至 `buildSrc/src/test/resources/fixtures/`：撰寫暫時性 init script（註冊一個以 `ResolutionResult` 輸出基準線檔格式的 dump 任務），分別於 `git worktree add /tmp/v130 knolux-redis-spring-boot-starter/v1.3.0` 的工作區與當前 HEAD 執行，產出 `redis-v1.3.0-baseline.txt` 與 `redis-current-baseline.txt`，完成後 `git worktree remove /tmp/v130`。**兩側都要簽入**——若當前側改為執行期即時解析，測試會隨日後版本升級而失效，SC-001 的回歸保護將悄悄消失
- [ ] T024 [US1] 撰寫回歸測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/Regression20260801Test.kt`：以 T023 的兩份 fixture 餵入 `DeltaCalculator`，斷言**同時**產出 `io.lettuce:lettuce-core` 6.8.2.RELEASE → 7.5.2.RELEASE 的 `MAJOR` **與** `io.netty:*` 4.1.x 的 `REMOVED`。只斷言 Lettuce 一項**不算通過**——那正是當時人工比對第一遍犯的錯，本測試的全部價值就在防止同樣的漏看

### 任務層（composition root）

- [ ] T025 [US1] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/task/CheckDependencyBaselineTask.kt`：解析當前 `runtimeClasspath` 並斷言與簽入的基準線檔逐字相同，不一致時失敗並列出差異行與修正指令 `./gradlew updateDependencyBaseline`（research.md R2 的機制 M1）
- [ ] T026 [US1] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/task/UpdateDependencyBaselineTask.kt`：以當前解析結果覆寫各模組基準線檔，console 列出新增／移除／變更筆數。**明確不做**：不執行任何 git 操作、不 commit（憲章 Governance 禁止直接推送 `main`）
- [ ] T027 [US1] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/task/CheckDependencyCompatibilityTask.kt`：`--base=<git-ref>` 選項（預設 `origin/dev`，不存在時退回 `origin/main`），流程依 contracts/gradle-tasks.md。**兩項順序為硬性要求**：(1) 所有模組所有差異一次算完才決定成敗，MUST NOT 遇到第一項阻擋即中止（FR-012）；(2) 報告寫檔 MUST 在拋出 `GradleException` **之前**完成（FR-019）
- [ ] T028 [US1] 於根 `build.gradle.kts` 的 `subprojects {}` 註冊上述三個任務（group 為 `verification`）並加上根層級聚合任務。**MUST NOT** 掛在 `check` 之下——`check` 由 `build` 觸發，而閘門需要 git 歷史與遠端 ref，掛上去會讓淺層 clone 或無 git 環境的一般建置直接失敗。模組層的 `build.gradle.kts` **零變更**（憲章「技術與相容性約束」）
- [ ] T029 [US1] 新增或更新 repo 根目錄 `.gitattributes`，強制 `gradle/dependency-baseline/*.txt` 為 LF。本 repo 於 Windows 開發、CI 於 Linux 執行，換行不統一會讓基準線檔每次都整檔 diff，使 R2 決策的「PR diff 可讀」失效
- [ ] T030 [US1] 執行 `./gradlew updateDependencyBaseline` 產生 `gradle/dependency-baseline/knolux-redis-spring-boot-starter.txt` 與 `knolux-s3-spring-boot-starter.txt` 並簽入。此基準線即當前 HEAD 狀態（已發布的 redis 1.4.0 / s3 1.3.0）。簽入前人工核對筆數約為 redis 51 筆、s3 68 筆
- [ ] T031 [US1] 實作失敗時的 console 摘要輸出（report-format.md §3）於 `CheckDependencyCompatibilityTask`：console **MUST** 含完整阻擋清單，MUST NOT 只寫「請見報告檔」——CI 上點開 artifact 的成本高到讓人選擇忽略
- [ ] T032 [US1] 端對端驗證 quickstart 情境 2：於臨時分支將 Spring Boot BOM 降回 4.0.6 製造反向 major 變動，確認建置失敗、所有阻擋項一次列完、`build/reports/dependency-gate/gate-report.md` 已產出，驗證後刪除臨時分支

**Checkpoint**: 閘門可獨立擋下破壞性變更（US1 交付）。此時尚無核准途徑，先不合併

---

## Phase 4: User Story 4 - 核准有意為之的破壞性變更 (Priority: P2)

**Goal**: 維護者能以逐筆、可審計的方式核准特定破壞性變更，讓建置通過。

**Independent Test**: 對已被閘門阻擋的變更集加入對應 `[[approval]]`，驗證建置轉為通過；再把 `to` 改成不相符的版本，驗證重新失敗（quickstart 情境 3）。

**為何排在 US2 之前**：兩者同為 P2，但沒有核准途徑的閘門會讓刻意的 major 升級永遠無法合併，
屆時唯一出路是停用閘門——等同 US1 的價值歸零。故先補上出口，再做發版報告。

- [ ] T033 [P] [US4] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/ApprovalStoreTest.kt`：正常剖析多筆 `[[approval]]`（含繁體中文 `reason`）；`kind = "REMOVED"` 時 `to` 可省略；**檔案不存在 → 零筆核准且正常運作**；欄位缺漏、`kind` 填入資訊性類別（如 `PATCH`）、TOML 格式錯誤 → **fail-fast 且訊息帶出項目序號與實際內容**。MUST NOT 靜默忽略無法解析的項目——打錯字的核准會變成「沒有核准」，而使用者以為擋不住的東西已被放行
- [ ] T034 [US4] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/Approval.kt` 與 `ApprovalStore.kt`（以 `org.tomlj:tomlj` 剖析，contracts/file-formats.md §2）
- [ ] T035 [US4] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/ApprovalMatcherTest.kt`：五欄（module / coordinate / from / to / kind）**全部逐字相符**才算核准；逐一改動任一欄位即不再放行（此為 FR-015「不得是全域開關」的實質保障）；MUST NOT 支援萬用字元或版本區間；能識別「過期核准」——檔案中存在但當次未配對到任何 delta 者
- [ ] T036 [US4] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/ApprovalMatcher.kt`（純函式，data-model.md §6）
- [ ] T037 [US4] 擴充 `GateVerdictTest.kt`：斷言阻擋性但已核准的 delta 進入 `approvedDeltas` 而不進 `blockedDeltas`；一項已核准、另一項未核准時狀態仍為 `BLOCKED` 且僅列出未核准者（spec US4 情境 2）
- [ ] T038 [US4] 將 `ApprovalMatcher` 接進 `GateVerdict` 的狀態推導與 `CheckDependencyCompatibilityTask` 的流程
- [ ] T039 [US4] 擴充 `ReportRendererTest.kt`：涵蓋 report-format.md 情形 B——「已核准的破壞性變動」表格含 `reason` **原文**，並保留「已核准不代表下游不受影響」的提醒；過期核准列入資訊性區塊
- [ ] T040 [US4] 實作 `ReportRenderer` 的情形 B 渲染
- [ ] T041 [US4] 建立 `gradle/dependency-approvals.toml`：僅含註解標頭（用途說明、`kind` 可用值、版本一動即失效的提醒），零筆核准。與 `gradle/libs.versions.toml` 同目錄同格式，維持既有慣例
- [ ] T042 [US4] 撰寫失敗測試：`UpdateDependencyBaselineTask` 於基準線推進後清除已失配的核准項，並在 console 列出被清除的項目（FR-017）
- [ ] T043 [US4] 實作核准清除邏輯於 `UpdateDependencyBaselineTask`
- [ ] T044 [US4] 端對端驗證 quickstart 情境 3：延續 T032 的失敗狀態加入核准 → 通過；再把 `to` 改成不相符版本 → **必須重新失敗**。若改了版本仍放行，代表比對過鬆，須修正

**Checkpoint**: 閘門具備完整的阻擋與放行途徑，可實際投入使用

---

## Phase 5: User Story 2 - 產出可直接引用的升級揭露報告 (Priority: P2)

**Goal**: 發版者執行單一指令即取得「自上一個 release 以來」的完整依賴變更報告，可原文貼入 CHANGELOG。

**Independent Test**: 於含已知依賴變動的分支執行 `dependencyChangeReport --since=<tag>`，檢查報告含模組名、座標、前後版本、分類，且不需重新排版即可貼入 Markdown（quickstart 情境 9）。

- [ ] T045 [P] [US2] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/ReleaseTagSelectorTest.kt`：自 tag 清單挑選某模組最新的 `<module>/v*`，**依語意化版本排序**——`v1.10.0` MUST 勝過 `v1.9.0`（字典序會給出相反答案，此為 spec 明列的 edge case）；其他模組的 tag MUST 被排除；無任何 tag → 回傳「無基準線」
- [ ] T046 [US2] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/ReleaseTagSelector.kt`。此型別未列於 data-model.md，是實作 FR-004 所需的新增純邏輯元件；歸屬與 `DeltaCalculator` 同層（無 I/O，tag 清單由呼叫端傳入）
- [ ] T047 [US2] 擴充 `ReportRendererTest.kt`：change-report 變體——標頭為 `**比較基準**：<tag>（上一個發布版本）`、**無**「判定」行、**無**「如何處理」段落、阻擋性區塊標題改為「⚠️ 升級前必讀」
- [ ] T048 [US2] 實作 `ReportRenderer` 的 change-report 渲染變體
- [ ] T049 [US2] 實作 `buildSrc/src/main/kotlin/com/knolux/build/depgate/task/DependencyChangeReportTask.kt`：`--since=<git-ref>` 選項（預設由 `ReleaseTagSelector` 決定），輸出至 `build/reports/dependency-gate/change-report.md`。**永不失敗**——發版當下需要的是完整資訊，不是阻擋
- [ ] T050 [US2] 於根 `build.gradle.kts` 的 `subprojects {}` 註冊 `dependencyChangeReport` 並加入根層級聚合任務
- [ ] T051 [US2] 端對端驗證 quickstart 情境 9：執行 `./gradlew dependencyChangeReport --since=knolux-redis-spring-boot-starter/v1.3.0`，確認「⚠️ 升級前必讀」段落可**原文**貼入 CHANGELOG，且內容與 `CHANGELOG.md:20-34` 現有的人工撰寫版本實質相符

**Checkpoint**: 發版揭露不再需要人工比對依賴樹

---

## Phase 6: User Story 3 - 不阻擋日常的 minor / patch 更新 (Priority: P3)

**Goal**: Dependabot 的例行 PR 綠燈通過，變動僅列入報告。

**Independent Test**: 僅升級某依賴 patch 版本的分支綠燈通過，且報告將其列為資訊性（quickstart 情境 4）。

**說明**：此故事的分類規則已由 Phase 2 的 `DeltaKind.blocking` 落實，本階段是**證明**它成立的驗證層。
這決定閘門能長期存活還是三週後被停用——若每支 bot PR 都紅燈，維護者會關掉閘門，前面所有價值隨之歸零。

- [ ] T052 [P] [US3] 撰寫失敗測試 `buildSrc/src/test/kotlin/com/knolux/build/depgate/DependabotScenarioTest.kt`：以 `GateVerdict` 層級驗證三種 Dependabot 型變更集——僅 patch 前進、僅 minor 前進（1.x 以上）、僅新增依賴——狀態皆為 `PASSED` 且 `blockedDeltas` 為空
- [ ] T053 [US3] 擴充 `ReportRendererTest.kt`：斷言上述三類出現在「ℹ️ 資訊性變動」區塊而非阻擋性區塊，特別驗證 `ADDED`（FR-011 明訂新增不阻擋，此為最易被誤歸為阻擋性的一類）
- [ ] T054 [US3] 端對端驗證 quickstart 情境 4：於 `gradle/libs.versions.toml` 將 awssdk 調成僅差 patch 的版本，確認建置**通過**且該變動列於資訊性區塊

**Checkpoint**: 四個 user story 全部獨立成立

---

## Phase 7: CI 整合

**Purpose**: 讓閘門在 CI 上真正生效。此階段的第一項是整個功能**最容易遺漏、且失敗訊息最不直觀**的一點。

- [ ] T055 ⚠️ 於 `.github/workflows/ci.yml:24` 的 `actions/checkout@v7` 加上 `fetch-depth: 0`。**不改這一行，閘門在 CI 上必然無法運作**——預設淺層 clone（depth 1）取不到 merge-base，也取不到任何 tag，而失敗會表現為「找不到 ref」這類難以聯想到根因的訊息（research.md R6）
- [ ] T056 於 `.github/workflows/ci.yml` 新增獨立步驟執行 `./gradlew checkDependencyCompatibility --base=origin/${{ github.base_ref }}`，作為與 `test` 並列的獨立步驟，不併入既有建置步驟
- [ ] T057 於 `.github/workflows/ci.yml` 加入 `actions/upload-artifact` 上傳 `**/build/reports/dependency-gate/`，**MUST 設 `if: always()`**——閘門失敗時報告尤其重要（FR-019）
- [ ] T058 依 T003 的實測結論，若主建置未自動執行 buildSrc 測試，於 `.github/workflows/ci.yml` 明確加入 `./gradlew -p buildSrc test` 步驟。若 T003 確認會自動執行，此任務標記為不需要並註明理由
- [ ] T059 於 `.github/workflows/publish.yml` 的發布步驟**之前**加入 `./gradlew checkDependencyBaseline`。此步驟建立 R2 決策的核心不變量：**任何被發布出去的 artifact，其外溢依賴集合必定等於當時簽入的基準線檔案**

**Checkpoint**: CI 上的閘門與發布保護皆已生效

---

## Phase 8: 驗收與收尾

**Purpose**: 逐一執行 quickstart 的十個驗收情境（情境 2 / 3 / 4 / 9 已於各自的 user story 階段完成），並完成文件同步。

### 剩餘的 quickstart 驗收情境

- [ ] T060 執行 quickstart 情境 1（SC-001，最關鍵）：`./gradlew -p buildSrc test --tests '*Regression2026080*'` 綠燈，且斷言確實同時涵蓋 Lettuce `MAJOR` 與 Netty `REMOVED` 兩項
- [ ] T061 執行 quickstart 情境 5：任意調整一個依賴版本但不執行 `updateDependencyBaseline`，確認 `./gradlew checkDependencyBaseline` 失敗且訊息明確指示修正指令並列出不一致的行
- [ ] T062 執行 quickstart 情境 6：`./gradlew -p buildSrc test --tests '*ArtifactVersion*'` 綠燈，確認無法解析時回傳 `Unparseable` 且 `reason` 帶原始字串，未回傳 null 亦未拋出被上層吞掉的例外
- [ ] T063 執行 quickstart 情境 7：於 `settings.gradle.kts` 暫時 `include` 一個最小模組，確認該模組出現在報告的「已跳過的模組」區塊、理由為無基準線、建置**不失敗**，且過程中**未修改**閘門本身任何設定（SC-006 / FR-002）。驗證後還原 `settings.gradle.kts`
- [ ] T064 執行 quickstart 情境 8：本地 `./gradlew checkDependencyCompatibility --base=origin/dev` 的判定與報告內容，與同一 commit 在 CI 上的結果完全一致（SC-007 / FR-023）
- [ ] T065 執行 quickstart 情境 10：量測閘門使單次 CI 檢查增加的 wall-clock 時間，確認不超過 3 分鐘（SC-005，含 buildSrc 編譯與測試）。若超出，優先檢查是否誤觸發了完整建置
- [ ] T066 確認既有建置行為完全不變：`./gradlew build` 與 `./gradlew test --continue` 的結果與加入本功能前一致（閘門刻意不掛在 `check` 之下的驗證）

### 文件與治理同步

- [ ] T067 [P] 更新 `CLAUDE.md`：於「建置與測試」加入四個新任務的指令與 `./gradlew -p buildSrc test`；於「專案結構與版本機制」說明基準線檔與核准檔的用途與更新時機
- [ ] T068 [P] 更新 `README.md`：於發布流程說明發版前需執行 `updateDependencyBaseline`，以及如何取用 `dependencyChangeReport` 撰寫升級揭露
- [ ] T069 以 `/speckit-constitution` 為 `.specify/memory/constitution.md` 的「開發流程與品質閘門 → Commit 與發布」新增一條發版步驟（發版前執行 `updateDependencyBaseline` 並確認基準線已簽入）。憲章修訂 MUST 隨附 Sync Impact Report 且 MUST 走 PR 流程，MUST NOT 直接推送 `main`
- [ ] T070 執行 `npx gitnexus analyze` 更新索引，再以 `gitnexus_detect_changes()` 確認本功能的影響範圍符合預期（未觸及兩個 starter 的任何發布程式碼）

**Checkpoint**: 功能完整交付，可開 PR 進 `dev`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**：無前置，可立即開始
- **Phase 2 Foundational**：依賴 Phase 1 —— **阻擋所有 user story**
- **Phase 3 (US1, P1)**：依賴 Phase 2
- **Phase 4 (US4, P2)**：依賴 Phase 2；`GateVerdict` / `ReportRenderer` 的擴充（T037 ~ T040）依賴 Phase 3 的 T020 / T022
- **Phase 5 (US2, P2)**：依賴 Phase 2；`ReportRenderer` 擴充（T047 ~ T048）依賴 Phase 3 的 T022
- **Phase 6 (US3, P3)**：依賴 Phase 2 與 Phase 3 的 T020 / T022
- **Phase 7 CI**：依賴 Phase 3（至少要有可執行的任務）；T059 依賴 T025
- **Phase 8**：依賴全部

### User Story Dependencies

- **US1 (P1)**：Phase 2 完成後即可開始，不依賴其他故事。**單獨完成即交付核心價值**
- **US4 (P2)**：核准邏輯本身（T033 ~ T036）與 US1 平行；接線（T038）需 US1 的 `GateVerdict` 存在
- **US2 (P2)**：`ReleaseTagSelector`（T045 ~ T046）與 US1 完全獨立；報告變體需 US1 的 `ReportRenderer` 存在
- **US3 (P3)**：驗證性質，需 US1 完成才有可驗證的對象

### Within Each User Story

- 測試 MUST 先寫且 MUST 確認失敗，才能寫實作（憲章 I，不可協商）
- 值物件 → 純邏輯 → adapter → 任務類別
- 任務類別完成後才進行端對端驗證

### Parallel Opportunities

- **Phase 2**：T004（ArtifactVersion 測試）與 T006（DependencyCoordinate 測試）完全獨立，可同時開始。T007 完成後，T008/T009（DependencySet）與 T010/T011（DeltaKind）兩條線可平行推進。T014 ~ T017 的 adapter 線與 T012/T013 的 DeltaCalculator 線互不相干
- **Phase 3 之後**：US4 的 T033 ~ T036、US2 的 T045 ~ T046 皆為獨立純邏輯，可與 US1 的收尾任務同時進行
- **Phase 8**：T067 與 T068 為不同檔案，可平行

### Parallel Example: Phase 2 起手

```bash
# 兩條完全獨立的 TDD 線，可同時開始：
Task: "撰寫失敗測試 ArtifactVersionTest.kt"        # T004
Task: "撰寫失敗測試 DependencyCoordinateTest.kt"   # T006
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup（T001 ~ T003）
2. Phase 2 Foundational（T004 ~ T018）—— 阻擋全部後續工作
3. Phase 3 User Story 1（T019 ~ T032）
4. **STOP and VALIDATE**：quickstart 情境 2 通過，閘門確實會擋
5. **先不合併** —— 沒有 US4 的核准途徑，刻意的 major 升級將無法合併

### Incremental Delivery

1. Setup + Foundational → 基礎就緒
2. US1 → 閘門會擋（核心價值）
3. US4 → 閘門可放行（**到此才具備可合併的完整性**）
4. US2 → 發版揭露自動化
5. US3 → 證明不會誤擋日常更新
6. CI 整合 + 驗收 → 交付

### 建議的 commit 切分

依憲章 Conventional Commits，且 commit message MUST NOT 含任何 AI 助手署名：

| 範圍 | 訊息 |
|---|---|
| T001 ~ T003 | `build: 建立 buildSrc 與 Kotlin 測試基礎設施` |
| T004 ~ T018 | `build: 實作依賴閘門的版本解析與差異分類核心邏輯` |
| T019 ~ T032 | `build: 加入傳遞依賴破壞性變更閘門` |
| T033 ~ T044 | `build: 加入依賴變更的逐筆核准機制` |
| T045 ~ T051 | `build: 加入發版依賴變更報告任務` |
| T052 ~ T054 | `test: 驗證 minor/patch 更新不觸發閘門` |
| T055 ~ T059 | `ci: 於 CI 與發布流程接上依賴閘門` |
| T067 ~ T070 | `docs: 同步依賴閘門的說明文件與憲章` |

---

## Notes

- `[P]` 代表不同檔案且無未完成前置
- 每個實作任務執行前，MUST 先確認其對應測試處於失敗狀態；「以既有測試涵蓋為由略過紅燈階段」不被接受（憲章 I）
- 兩個 starter 的 `build.gradle.kts` 與 `src/` 在全部 70 項任務中**皆為零變更**。若實作過程中發現需要修改它們，代表設計偏離，應回頭檢視而非直接改
- T023 的 fixture、T029 的 `.gitattributes`、T055 的 `fetch-depth: 0` 為三項最易遺漏且後果最隱蔽的任務：分別會導致回歸保護悄悄失效、基準線檔 diff 噪音、閘門在 CI 上完全不運作
