# Implementation Plan: 傳遞依賴破壞性變更閘門

**Branch**: `build/dep-breaking-change-gate` | **Date**: 2026-08-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-dep-breaking-change-gate/spec.md`

## Summary

兩個 starter 都以 `api` scope 曝露核心依賴，因此傳遞依賴跨 major 等同對下游的破壞性變更，
即使自有原始碼一行未改。本功能在 CI 加入閘門，於**合併前**攔截這類變更，
並產出可直接貼入 CHANGELOG 的揭露報告。

**技術路線**：在 `buildSrc/` 以 Kotlin 實作四個 Gradle 任務。「外溢依賴集合」取自
`runtimeClasspath` 的 `ResolutionResult`（已實證確認排除 `compileOnly` 的 Actuator 與 Lombok）。
基準線以**簽入 repo 的純文字檔**保存而非執行期重建舊 tag——快、無需重建舊環境、
依賴變更直接顯示在 PR diff、且歷史基準線由 git 免費提供。
基準線與實際發布內容的一致性，由 `publish.yml` 發布前的斷言保證。
核准以 `gradle/dependency-approvals.toml` 逐筆記錄，五欄逐字比對，不支援萬用字元。

決策依據見 [research.md](./research.md)。

## Technical Context

**Language/Version**: Kotlin（buildSrc，與既有 `.kts` 建置腳本一致）；目標 JVM 為專案既有的 Java 25 toolchain

**Primary Dependencies**: Gradle 9.6.1 API（`ResolutionResult`）；`org.tomlj:tomlj`（僅 buildSrc，剖析核准檔）

**Storage**: 版控內的純文字檔 —— `gradle/dependency-baseline/<module>.txt`（產生物）、`gradle/dependency-approvals.toml`（手寫）

**Testing**: JUnit 5（專案既有框架），於 `buildSrc/src/test/kotlin`。純邏輯全數單元測試，無需 Docker

**Target Platform**: 開發機（Windows / Linux / macOS）與 GitHub Actions `ubuntu-latest`

**Project Type**: 建置工具（build logic），不進入任何發布的 artifact

**Performance Goals**: 閘門使單次 PR 檢查總時長增加 < 3 分鐘（SC-005）。預估實際遠低於此——
`runtimeClasspath` 在正常建置中本就會解析，邊際成本接近零；主要成本是 buildSrc 首次編譯與 `fetch-depth: 0`

**Constraints**:
- 輕量工具鏈：不引入 Gradle 外掛（spec Assumptions）
- 不採用 Gradle dependency locking、不採用 japicmp（spec Assumptions，理由見 research.md R2）
- 核心邏輯必須在 Gradle 側，本地單一指令可完整重現 CI 結果（FR-023）
- CI 不得自動 commit 基準線（憲章 Governance 禁止直接推送 `main`）

**Scale/Scope**: 2 個已發布模組（redis 51 筆、s3 68 筆外溢依賴）；新增模組自動納入，無需改設定

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

依據 `.specify/memory/constitution.md` v1.0.0：

| 原則 | Phase 0 前 | Phase 1 後 | 落點 |
|---|---|---|---|
| **I. 測試驅動開發（不可協商）** | ✅ | ✅ | 選 `buildSrc` 而非內嵌腳本的**決定性理由**就是可單元測試。版本解析、差異分類、核准比對、報告渲染全為純函式。SC-001 落實為永久回歸測試（quickstart 情境 1），而非一次性人工驗證 |
| **II. SOLID** | ✅ | ✅ | SRP：`ArtifactVersion` / `DeltaCalculator` / `ApprovalMatcher` / `ReportRenderer` 各司其職，比照既有的 `CacheKeys`、`RedisUriUtils` 拆法。OCP：新增判定規則＝新增 `DeltaKind` 與對應分類分支，不動既有規則。DIP：任務依賴 `BaselineSource` 介面 |
| **III. 六角形架構與關注點分離** | ✅ | ✅ | Port：`BaselineSource`。Adapter：`GitBaselineSource`（外呼 git）、`FileBaselineSource`（測試用）。Composition root：Gradle 任務類別只組裝與判斷，不含分類邏輯。純邏輯層完全不知道 git 與 Gradle 的存在 |
| **IV. Fail-Fast 與可觀測性** | ✅ | ✅ | 版本無法解析 → 失敗並帶出**原字串**（FR-009）。基準線過期 → 失敗並給修正指令。核准檔格式錯誤 → 失敗，不靜默忽略。無基準線 → 跳過但**必須**記入報告（FR-005）。`ArtifactVersion.parse` 回傳 sealed result 而非 null，強迫呼叫端處理失敗分支 |
| **V. 可擴展性與可覆寫性** | ✅ | ✅ | 任務註冊於 `subprojects {}` 迴圈，新模組自動納入（FR-002 / SC-006）。核准為逐筆、限定版本區間，**格式中不存在全域停用開關**（FR-015） |
| **VI. 命名與風格一致性** | ✅ | ✅ | KDoc 與註解繁體中文；報告全繁體中文（FR-022）。`*Source` 為 port、`*Calculator` / `*Matcher` / `*Renderer` 為職責明確的純邏輯類別，與既有 `*Factory` / `*Builder` / `*Provider` 慣例同構。buildSrc 型別不外流下游，故**不加** `Knolux` 前綴，改以 package `com.knolux.build.depgate` 界定 |

**技術與相容性約束**：

- 「模組層 `build.gradle.kts` 只宣告 `description` 與 `dependencies`」 → 任務註冊全部在根 `build.gradle.kts` 的 `subprojects {}`，模組層**零變更** ✅
- 「兩個 starter 保持互相獨立」 → 閘門逐模組獨立判定，不產生跨模組依賴 ✅
- 「`gradle.properties` 恆為 `0.0.0-SNAPSHOT`」 → 不涉及 ✅

**開發流程與品質閘門**：

- 既有的 `./gradlew build` / `./gradlew test --continue` 行為**完全不變**——閘門刻意不掛在 `check` 之下（理由見 [contracts/gradle-tasks.md](./contracts/gradle-tasks.md)）✅
- 本功能會為憲章「開發流程與品質閘門」新增一條發版步驟（發版前執行 `updateDependencyBaseline`）。憲章修訂須經 `/speckit-constitution` 並走 PR ——列為實作階段的收尾任務 ⚠️

**結論**：無違規項。`Complexity Tracking` 中記錄兩項需要說明的新增結構。

## Project Structure

### Documentation (this feature)

```text
specs/001-dep-breaking-change-gate/
├── spec.md              # 規格（/speckit-specify 產出）
├── plan.md              # 本檔
├── research.md          # Phase 0：六項技術決策
├── data-model.md        # Phase 1：八個型別
├── quickstart.md        # Phase 1：十個驗收情境
├── contracts/           # Phase 1
│   ├── gradle-tasks.md      # 四個任務的 CLI 介面與結束狀態
│   ├── file-formats.md      # 基準線檔與核准檔格式
│   └── report-format.md     # Markdown 報告格式
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2（/speckit-tasks 產出，非本命令）
```

### Source Code (repository root)

```text
buildSrc/                                          # 新增
├── build.gradle.kts                               # kotlin-dsl + tomlj + JUnit 5
└── src/
    ├── main/kotlin/com/knolux/build/depgate/
    │   ├── ArtifactVersion.kt                     # 純：版本解析（FR-008 / FR-009）
    │   ├── DependencyCoordinate.kt                # 純：座標值物件
    │   ├── DependencySet.kt                       # 純：集合 + 決定性序列化
    │   ├── DependencyDelta.kt                     # 純：差異 + DeltaKind
    │   ├── DeltaCalculator.kt                     # 純：差異計算與分類（FR-006 / FR-007）
    │   ├── Approval.kt                            # 純：核准值物件
    │   ├── ApprovalStore.kt                       # 純：TOML 剖析（FR-014）
    │   ├── ApprovalMatcher.kt                     # 純：五欄逐字比對（FR-015）
    │   ├── GateVerdict.kt                         # 純：判定聚合（FR-012）
    │   ├── ReportRenderer.kt                      # 純：Markdown 渲染（FR-018 ~ FR-022）
    │   ├── BaselineSource.kt                      # Port
    │   ├── GitBaselineSource.kt                   # Adapter：git show
    │   ├── ResolvedDependencyReader.kt            # Adapter：Gradle ResolutionResult
    │   └── task/                                  # Composition root
    │       ├── CheckDependencyCompatibilityTask.kt
    │       ├── CheckDependencyBaselineTask.kt
    │       ├── UpdateDependencyBaselineTask.kt
    │       └── DependencyChangeReportTask.kt
    └── test/
        ├── kotlin/com/knolux/build/depgate/       # 對應單元測試（TDD：先寫）
        └── resources/fixtures/
            └── redis-v1.3.0-baseline.txt          # SC-001 回歸測試 fixture

gradle/
├── libs.versions.toml                             # 既有
├── dependency-baseline/                           # 新增（產生物，簽入版控）
│   ├── knolux-redis-spring-boot-starter.txt
│   └── knolux-s3-spring-boot-starter.txt
└── dependency-approvals.toml                      # 新增（手寫，初始為空）

build.gradle.kts                                   # 修改：subprojects {} 內註冊四個任務 + 根聚合任務
.github/workflows/ci.yml                           # 修改：fetch-depth: 0、執行閘門、上傳報告
.github/workflows/publish.yml                      # 修改：發布前執行 checkDependencyBaseline

knolux-redis-spring-boot-starter/build.gradle.kts  # 零變更
knolux-s3-spring-boot-starter/build.gradle.kts     # 零變更
```

**Structure Decision**：新增 `buildSrc/` 作為唯一的建置邏輯落點。兩個 starter 模組的
`build.gradle.kts` 與所有 `src/` 完全不動——本功能不觸碰任何會進入發布 artifact 的程式碼。
`gradle/` 目錄同時容納既有的 `libs.versions.toml` 與新增的基準線／核准檔，維持「依賴相關設定集中於 `gradle/`」的既有慣例。

## 實作順序（供 /speckit-tasks 展開）

依 TDD 與相依關係排列。每一層的測試先於實作。

1. **buildSrc 骨架** — `build.gradle.kts`、Kotlin + JUnit 5、確認 `-p buildSrc test` 可執行
2. **純邏輯層（TDD）** — `ArtifactVersion` → `DependencyCoordinate` / `DependencySet` → `DeltaCalculator` → `ApprovalStore` / `ApprovalMatcher` → `ReportRenderer`
3. **SC-001 回歸測試** — 擷取 v1.3.0 fixture，斷言同時產出 Lettuce `MAJOR` 與 Netty `REMOVED`
4. **Adapter 層** — `ResolvedDependencyReader`、`GitBaselineSource`
5. **任務層** — 四個任務 + 根 `build.gradle.kts` 註冊
6. **首次產生基準線** — 執行 `updateDependencyBaseline`，簽入（基準線＝當前 HEAD，即已發布的 redis 1.4.0 / s3 1.3.0 狀態）
7. **CI 整合** — `ci.yml` 的 `fetch-depth: 0` 與閘門步驟、`publish.yml` 的基準線斷言
8. **端對端驗收** — 逐一執行 [quickstart.md](./quickstart.md) 的十個情境
9. **文件收尾** — 更新 `CLAUDE.md` 建置指令、`README.md` 發布流程、以 `/speckit-constitution` 為憲章新增發版步驟

## Complexity Tracking

> 無憲章違規。下列兩項為新增結構，記錄理由備查。

| 新增項 | 為何需要 | 已否決的更簡方案 |
|---|---|---|
| `buildSrc/` 建置邏輯來源樹 | 憲章「I. 測試驅動開發（不可協商）」要求先寫失敗測試。寫在 `build.gradle.kts` 內的邏輯無法單元測試，等於這條原則對本功能整體失效 | 內嵌於根 `build.gradle.kts`：不可測；獨立 `build-logic` included build：多一層 settings，本專案規模用不到 |
| `org.tomlj:tomlj`（僅 buildSrc） | 核准檔含多欄位與繁體中文 `reason`，手寫剖析器在「專為確保正確性而生」的程式碼裡風險過高。此依賴不進入任何發布 artifact，對下游零影響 | 自訂單行文字格式：省一個依賴，但把剖析錯誤風險放進最不該有錯的地方；沿用 `.properties`：多欄位結構表達力不足 |

## 對 spec 的回饋（已回填）

研究過程浮現三項 spec 未涵蓋之處，已依憲章「實作偏離規格時 MUST 回頭修改規格」**回填至 spec.md**，
修訂紀錄見 [checklists/requirements.md](./checklists/requirements.md)：

1. **FR-003 / FR-004 拆分為兩種比對基準** — 閘門用「vs 本次變更集起點」（本次造成什麼），
   發版揭露用「vs 上一個 release」（累積了什麼）。原 spec 只描述後者，會讓已核准的變更重複阻擋。見 research.md R2
2. **新增 FR-007a：0.x 的 minor 前進視為 major** — 語意化版本規範明訂 0.y.z 為不穩定期。見 research.md R5
3. **FR-007 / FR-010 新增「版本後退」類別** — 版本後退時基準線曾提供的 API 可能消失，風險等同 major。見 data-model.md §4

spec.md 與本計畫現已一致，可進入 `/speckit-tasks`。
