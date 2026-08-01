# Contract：Gradle 任務介面

**Feature**: [../spec.md](../spec.md) | **Date**: 2026-08-01

閘門對維護者與 CI 曝露的唯一介面就是這四個 Gradle 任務。任務註冊於根 `build.gradle.kts` 的
`subprojects {}` 區塊（每模組一份）並加上根層級聚合任務，符合憲章「模組層的 `build.gradle.kts`
只宣告 `description` 與 `dependencies`」。

group 一律為 `verification`。

---

## `checkDependencyCompatibility` — 閘門本體

**用途**：spec User Story 1 / 3 / 4。這是 CI 上實際擋 PR 的任務。

```bash
./gradlew checkDependencyCompatibility                      # 全部模組（FR-023 的單一指令）
./gradlew :knolux-redis-spring-boot-starter:checkDependencyCompatibility
./gradlew checkDependencyCompatibility --base=origin/dev    # 明確指定比較基準
```

| 選項 | 預設 | 說明 |
|---|---|---|
| `--base=<git-ref>` | `origin/dev`，不存在時退回 `origin/main` | 與此 ref 的 merge-base 作為比較基準 |

**行為**：

1. 解析當前 `runtimeClasspath` 得到 current `DependencySet`
2. 斷言簽入的基準線檔與 current 相同（等同內嵌一次 `checkDependencyBaseline`）
3. 自 merge-base 取得 baseline `DependencySet`
4. 計算並分類差異，比對核准檔
5. **先**寫出報告，**再**依判定決定成敗

**結束狀態**：

| 情形 | 結束碼 | 訊息 |
|---|---|---|
| 無差異，或差異全為資訊性 | 0 | 摘要行 |
| 阻擋性差異已全數核准 | 0 | 摘要行 + 已核准清單 |
| 存在未核准的阻擋性差異 | 非 0 | 逐筆列出模組／座標／`from → to`／類別（FR-013） |
| 基準線檔與當前解析不一致 | 非 0 | 提示執行 `./gradlew updateDependencyBaseline` |
| 版本無法解析 | 非 0 | 帶出座標與**版本字串原文**（FR-009） |
| 該模組無基準線 | 0 | 報告中記錄跳過原因（FR-005） |

**FR-012**：所有模組、所有差異一次算完才決定成敗，不得遇到第一項阻擋就中止。

---

## `checkDependencyBaseline` — 基準線新鮮度檢查

**用途**：research.md R2 的機制 M1／M2。單獨存在是為了讓 `publish.yml` 能在不做差異比對的情況下，
只驗證「即將發布的內容與簽入基準線一致」。

```bash
./gradlew checkDependencyBaseline
```

**結束狀態**：一致則 0；不一致則非 0，並列出差異行與修正指令。

**建立的不變量**：發布出去的 artifact，其外溢依賴集合必定等於當時簽入的基準線檔案。

---

## `updateDependencyBaseline` — 重新產生基準線

**用途**：維護者在有意接受依賴變動後執行。

```bash
./gradlew updateDependencyBaseline
```

**行為**：

1. 以當前 `runtimeClasspath` 解析結果覆寫各模組的基準線檔
2. 清除 `gradle/dependency-approvals.toml` 中已失配的核准項（FR-017）
3. 於 console 列出：新增/移除/變更的依賴筆數，以及被清除的核准項

**明確不做的事**：不執行 git 操作、不 commit。變更留在工作區，由維護者檢視後併入 PR。
CI 不得執行此任務——憲章 Governance 明文禁止直接推送 `main`。

---

## `dependencyChangeReport` — 發版揭露報告

**用途**：spec User Story 2 / FR-018 ~ FR-021。

```bash
./gradlew dependencyChangeReport --since=knolux-redis-spring-boot-starter/v1.3.0
```

| 選項 | 預設 | 說明 |
|---|---|---|
| `--since=<git-ref>` | 該模組最新的 `<module>/v*` tag，依語意化版本排序（FR-004） | 比較起點 |

**與 `checkDependencyCompatibility` 的差異**：比較基準是**上一個 release tag**（累積變化），
而非 merge-base（單次變更）。純報告用途，**永不失敗**——發版當下需要的是完整資訊，不是阻擋。

**輸出**：`build/reports/dependency-gate/change-report.md`，格式見 [report-format.md](./report-format.md)。

---

## 與既有建置的整合

| 位置 | 變更 |
|---|---|
| `check` 任務 | `checkDependencyCompatibility` **不掛**在 `check` 之下 |
| `ci.yml` | 新增獨立步驟執行閘門；checkout 需加 `fetch-depth: 0` |
| `publish.yml` | 發布前新增 `checkDependencyBaseline` 步驟 |

**為何不掛在 `check` 之下**：`check` 會被 `build` 觸發，而閘門需要 git 歷史與遠端 ref。
在無 git 環境（如 source tarball）或淺層 clone 中，掛上去會讓一般建置直接失敗。
既有的 `./gradlew build` / `./gradlew test --continue` 行為因此完全不變。
