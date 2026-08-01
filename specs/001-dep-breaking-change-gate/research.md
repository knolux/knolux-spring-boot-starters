# Phase 0 研究：傳遞依賴破壞性變更閘門

**Feature**: [spec.md](./spec.md) | **Date**: 2026-08-01

本文件解決 plan.md「Technical Context」中的未知項，每項以「決策 / 理由 / 已評估的替代方案」呈現。

---

## R1. 「外溢依賴集合」對應到哪個 Gradle configuration

**決策**：`runtimeClasspath` 的 **resolution result**（非 `dependencies` 任務的文字輸出）。

**理由**：

1. 實證驗證（2026-08-01，於 `knolux-redis-spring-boot-starter` 執行）：`runtimeClasspath` **不含** `compileOnly` 引入的 Actuator 與 Lombok（grep 結果 0 筆），符合 spec Assumptions 中「選用依賴不在保護範圍」。
2. 它同時涵蓋 `api` 與 `implementation` 的遞移閉包，正是下游執行期會拿到的集合。
3. 根 `build.gradle.kts:52` 的 `versionMapping { usage("java-api") { fromResolutionOf("runtimeClasspath") } }` 已用它決定 published POM 的版號——閘門與實際發布的 artifact 因此看到同一份資料，不會有第二套真相。
4. 本次事件的人工比對即以 `runtimeClasspath` 進行（redis 51 筆、s3 68 筆），閘門沿用同一基準可直接對照。

**必須使用 API 而非文字輸出**：實證發現 `./gradlew :module:dependencies --configuration runtimeClasspath` 的輸出同時含「請求版本」與「解析後版本」，例如 Netty 同時出現 `4.2.13.Final` 與 `4.2.15.Final`。文字剖析會把請求版本誤當成實際依賴，直接違反 FR-009 的正確性要求。

因此改用：

```kotlin
configurations.getByName("runtimeClasspath")
    .incoming.resolutionResult.allComponents
    .mapNotNull { it.id as? ModuleComponentIdentifier }
```

`ResolutionResult` 只回傳衝突解決後的最終選定版本。

**已評估的替代方案**：

| 方案 | 否決理由 |
|---|---|
| `apiElements` / compile scope（僅 `api` 依賴） | 涵蓋面過窄。依賴自 runtime classpath 消失同樣會讓下游炸掉（Netty 4.1.x 即為實例），而 compile scope 看不到 |
| `compileClasspath` | 含 `compileOnly`（Actuator、Lombok），會把不外溢的依賴誤判為外溢 |
| 剖析 published POM | 需先執行 publish 才有 POM，無法在 PR 階段執行 |
| `dependencies` 任務文字輸出 | 如上，混雜請求版本與解析版本，不可靠 |

**已知取捨**：`runtimeClasspath` 是「編譯期可見」的超集。以它為準會在「僅 runtime 可見的依賴跨 major」時也阻擋，屬過度攔截。這與 spec Assumptions「以精確度換取零漏報——寧可多攔一次由人核准，不可漏放一次」一致，且有 User Story 4 的核准途徑作為出口。

---

## R2. 基準線如何取得（使用者指定須決斷的問題 1）

**決策**：**(b) 簽入基準線檔案**，並以三項機制補上其唯一弱點（檔案與實際發布脫節）。

**理由**：

先看 (a) 執行期重建的實際成本與風險：

- 需 checkout 前一個 tag 到 worktree，並以**該 tag 當時的** `gradlew` 執行（v1.3.0 為 Gradle 9.4.1，與現行 9.6.1 不同），CI 上會多下載一份 Gradle distribution 並跑一次完整 configuration。
- spec 已列出的 edge case「基準線對應的 commit 已無法重建依賴圖」在 (a) 之下是**致命**的：舊 tag 一旦因 toolchain 或 repository 變動而無法解析，閘門就整個失效，且失效方式是「建置錯誤」而非「偵測到變更」，容易被誤當成雜訊而略過。
- 對 SC-005 的 3 分鐘預算是實質壓力。

(b) 的弱點只有一個——檔案可能與實際發布的 artifact 脫節——而這個弱點可以被完全消除（見下方三項機制）。消除之後，(b) 還額外帶來兩個 (a) 給不了的好處：

1. **依賴變更直接出現在 PR diff**。Lettuce `6.8.2.RELEASE → 7.5.2.RELEASE` 會是一行紅、一行綠，審查者不需要理解閘門也看得見。這正是本次事件真正缺少的東西。
2. **歷史基準線由 git 免費提供**。撰寫發版揭露時要的「自上一個 release 以來的依賴變化」，變成 `git show <tag>:gradle/dependency-baseline/<module>.txt` 與當前檔案比對，不需重建任何舊環境。這直接滿足 User Story 2 與 FR-018。

**消除脫節風險的三項機制**：

| 機制 | 內容 | 擋住什麼 |
|---|---|---|
| M1 | `checkDependencyBaseline`：解析當前 `runtimeClasspath`，斷言與簽入的基準線檔案逐字相同，否則失敗並提示執行 `updateDependencyBaseline` | 忘記重新產生基準線 |
| M2 | `publish.yml` 在發布前先跑 M1 | 發布的 artifact 與簽入基準線不一致 |
| M3 | 基準線檔案標頭含「由任務產生，請勿手動編輯」，且格式為排序後純文字 | 手動竄改難以隱藏，且 diff 乾淨 |

M2 建立的不變量是本方案的核心保證：

> **任何被發布出去的 artifact，其外溢依賴集合必定等於當時 repo 中簽入的基準線檔案。**

有了這個不變量，基準線檔案就是可信的歷史紀錄，(a) 的「永遠真實」優勢即被 (b) 以更低成本取得。

**比較基準的選擇**：閘門在 PR 上比對的是「基準線檔案在 merge-base 的版本」與「基準線檔案在 PR HEAD 的版本」，也就是**本次變更集對依賴造成的改變**。這與 FR-003 字面上的「最近一次已發布的版本」略有差異，理由如下：

- 已合併進 `dev` 的破壞性變更，在當初的 PR 上已被攔截並核准過；每支後續 PR 重複報同一項只會產生雜訊，最終導致閘門被忽略。
- 「自上一個 release 以來的累積變化」是**發版揭露**要的視角，由獨立的 `dependencyChangeReport --since=<tag>` 提供，兩者用途不同、不互相取代。

此差異已回填至 spec 的 Assumptions 待確認清單（見下方「對 spec 的回饋」）。

**已評估的替代方案**：

| 方案 | 否決理由 |
|---|---|
| (a) 執行期重建舊 tag | 慢、脆弱，且 spec 已列的 edge case 在此方案下無解 |
| Gradle 內建 dependency locking | spec Assumptions 已排除：鎖住全部依賴，Dependabot 對 lockfile 再生支援不完整，會讓每支 bot PR 紅燈，違反 User Story 3 |
| 由 CI 自動 commit 更新後的基準線 | 憲章 Governance 明文「MUST NOT 直接推送至 `main`」 |
| 從 GitHub Packages 下載已發布的 POM 作為基準線 | 需憑證、需網路、離線無法重現，違反 FR-023 |

---

## R3. 核准紀錄放在哪（使用者指定須決斷的問題 2）

**決策**：簽入 repo 的 `gradle/dependency-approvals.toml`，每筆核准限定 `module` + `coordinate` + `from` + `to`。

**理由**：

- **FR-015（不得是全域開關）**：TOML 的 `[[approval]]` 陣列天然是逐筆的。沒有「關閉閘門」這個選項存在於格式中。
- **FR-016（可事後查閱）**：檔案在版控內，`git log -p gradle/dependency-approvals.toml` 即完整審計軌跡——誰、何時、在哪支 PR、寫了什麼理由。不需要額外的紀錄系統。
- **FR-023（本地可重現）**：純檔案，離線可讀。
- **格式一致性**：專案已有 `gradle/libs.versions.toml`，同目錄同格式，維護者不需學新東西。
- **FR-017（成為新基準線後不再影響判定）**：核准以 `from → to` 版本區間為鍵。基準線推進到 `to` 之後，該筆差異不再產生，核准自然失配而無作用。`updateDependencyBaseline` 額外負責清除已失配的核准並在輸出中列出被清除的項目。

**已評估的替代方案**：

| 方案 | 否決理由 |
|---|---|
| GitHub PR label | 綁定 GitHub、本地無法重現（違反 FR-023）、label 可事後移除而不留痕 |
| Commit message trailer（如 `Approved-Dependency-Change:`） | 需在 CI 讀取 commit range，本地重現需相同 range 推導；且 rebase / squash 會遺失 |
| 環境變數或 Gradle property（如 `-PskipDepGate`） | 這正是 FR-015 禁止的全域開關 |
| 純文字自訂格式（避免引入 TOML parser） | 見下方 R4；核准項含中文理由與多欄位，自訂格式的剖析錯誤風險高於引入一個成熟 parser |

---

## R4. 閘門邏輯放在哪、用什麼語言、如何測試

**決策**：`buildSrc/`，Kotlin，JUnit 5；純邏輯與 I/O 嚴格分離。

**理由**：

- **憲章「I. 測試驅動開發（不可協商）」是決定性因素**。寫在 `build.gradle.kts` 內的邏輯無法單元測試。`buildSrc` 有自己的 source set 與 test 任務，版本解析、差異分類、核准比對、報告渲染全部可以先寫失敗測試再實作。
- **憲章「III. 六角形架構與關注點分離」直接適用**：
  - **Port**：`BaselineSource`（取得某個 git ref 上的基準線內容）
  - **Adapter**：`GitBaselineSource`（外呼 git）、`FileBaselineSource`（讀工作區檔案，測試用）
  - **Composition root**：Gradle 任務類別，只負責組裝與條件判斷，不含分類邏輯
- **語言選 Kotlin**：閘門需大量操作 Gradle API（`ResolutionResult`、`Provider`、`@TaskAction`），而 `build.gradle.kts` 與根建置腳本已是 Kotlin DSL。buildSrc 用 Java 會在同一層建置邏輯內混用兩種語言，得不償失。此決策不影響兩個 starter 的產出物——buildSrc 完全不進入發布的 artifact。
- **註解語言**：依憲章「VI. 命名與風格一致性」，KDoc 與註解一律繁體中文。
- **命名**：憲章要求「對外型別 MUST 使用 `Knolux` 前綴」，指的是 starter 發布給下游的型別。buildSrc 型別不外流，**不加** `Knolux` 前綴，改以 package `com.knolux.build.depgate` 界定範圍。

**待實作時驗證的事項**：Gradle 9.6.1 是否仍在主建置時自動執行 `buildSrc` 的 `test` 任務。近年 Gradle 對 buildSrc 生命週期有調整，不應憑印象斷定。實作時以實測確認；若未自動執行，於 `ci.yml` 明確加入 `./gradlew -p buildSrc test`，避免閘門自身的測試從未跑過。

**已評估的替代方案**：

| 方案 | 否決理由 |
|---|---|
| 直接寫在根 `build.gradle.kts` | 無法單元測試，違反憲章 I |
| 獨立的 `build-logic` included build | 比 buildSrc 多一層 settings 設定，本專案規模用不到 |
| 獨立 shell script + CI 呼叫 | 違反 FR-023（本地重現需複製 CI 環境）；且 shell 難以單元測試版本解析邏輯 |
| 現成的 Gradle 外掛（如 dependency-analysis-plugin） | 功能遠超所需、設定量大，違反 spec Assumptions 的「輕量工具鏈原則」；且無一提供「跨 major 即阻擋 + 逐筆核准」這個特定語意 |

---

## R5. 版本字串如何判定 major（FR-008 / FR-009）

**決策**：取前導數字段。以 `.` 或 `-` 切分，第一段為 major，第二段為 minor，第三段為 patch（缺者補 0），其後的非數字段（`RELEASE`、`Final`、`M1`…）視為 qualifier 並忽略。無法取得任何前導數字段時判定為「無法解析」。

**本專案實際出現的版本形式**（取自 2026-08-01 的解析結果）：

| 樣本 | 來源 | 解析為 |
|---|---|---|
| `7.5.2.RELEASE` | `io.lettuce:lettuce-core` | 7.5.2 (qualifier `RELEASE`) |
| `4.2.15.Final` | `io.netty:*` | 4.2.15 (qualifier `Final`) |
| `2.49.3` | AWS SDK | 2.49.3 |
| `2.6` | `snakeyaml` | 2.6.0 |
| `1.5.34` | `logback` | 1.5.34 |

**額外決策：0.x 視 minor 跳動為阻擋性**。語意化版本規範明訂 0.y.z 為不穩定期，任何 minor 前進皆可能破壞相容。`0.5.0 → 0.6.0` 因此與 major 跳動同等對待。此項為 spec FR-007 / FR-010 的細化，屬新增判定規則，已回填至下方「對 spec 的回饋」。

**無法解析時的行為**：依 FR-009 與憲章「IV. Fail-Fast 與可觀測性」，標示為「無法判定」並讓建置失敗，訊息帶上依賴座標與**實際的版本字串原文**。明確禁止「解析失敗就當作無變動」——那正是本功能要根除的靜默放行模式。

**已評估的替代方案**：

| 方案 | 否決理由 |
|---|---|
| 嚴格 SemVer 剖析器（不接受後綴） | `6.8.2.RELEASE`、`4.2.15.Final` 全數解析失敗，等於全紅，不可用 |
| Maven `ComparableVersion` | 能比大小，但不切分 major/minor/patch，無法做本功能要的分類 |
| 直接字串相等比對（不分類） | 無法區分 patch 與 major，違反 FR-007 與 User Story 3 |

---

## R6. CI 如何取得比較基準

**決策**：`GitBaselineSource` 外呼 `git show <ref>:<path>`；CI 的 checkout 加上 `fetch-depth: 0`。

**理由**：現行 `.github/workflows/ci.yml:24` 的 `actions/checkout@v7` 未設 `fetch-depth`，預設淺層 clone（depth 1），**取不到 merge-base，也取不到任何 tag**。不改這一行，閘門在 CI 上必然無法運作。這是實作時最容易漏掉、且失敗訊息最不直觀的一點，故明列於此。

本 repo 體積小，全深度 clone 的額外成本可忽略。

**PR 的比較基準取得方式**：`git merge-base HEAD origin/<target-branch>`，再 `git show <merge-base>:gradle/dependency-baseline/<module>.txt`。基準線檔案在該 ref 不存在時（功能首次導入前的歷史 commit），視為 FR-005 的「無基準線」情形——跳過比對、記入報告、不失敗。

---

## 對 spec 的回饋

以下三項在研究過程中浮現，屬 spec 未涵蓋或需修正之處。依憲章「規格是產出物的來源，實作偏離規格時 MUST 回頭修改規格」，應於進入 `/speckit-tasks` 前回填 spec：

1. **FR-003 的比較基準需區分兩種用途**（見 R2）。閘門用「vs merge-base」，發版揭露用「vs 上一個 release tag」。spec 目前只描述後者。
2. **0.x 的 minor 跳動應視為阻擋性**（見 R5）。spec FR-007 / FR-010 目前只提 major。
3. **`ci.yml` 需要 `fetch-depth: 0`**（見 R6）。屬實作細節，不需進 spec，但需進 tasks.md 且不可遺漏。

---

## 未解決項

無。plan.md 的 Technical Context 已無 NEEDS CLARIFICATION。
