# Phase 1 資料模型：傳遞依賴破壞性變更閘門

**Feature**: [spec.md](./spec.md) | **Research**: [research.md](./research.md) | **Date**: 2026-08-01

所有型別位於 `buildSrc/src/main/kotlin/com/knolux/build/depgate/`，語言 Kotlin，KDoc 繁體中文。
值物件一律為 `data class`（不可變），與專案「值物件為 record」的慣例在 Kotlin 側的對應寫法。

---

## 1. `ArtifactVersion` — 解析後的版本

對應 spec FR-007 / FR-008 / FR-009。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `raw` | `String` | 原始字串，**永遠保留**，錯誤訊息需帶出（FR-009、FR-013） |
| `major` | `Int` | 第一個數字段 |
| `minor` | `Int` | 第二個數字段，缺則 0 |
| `patch` | `Int` | 第三個數字段，缺則 0 |
| `qualifier` | `String?` | 尾端非數字段（`RELEASE`、`Final`、`M1`），無則 null |

**建構**：`ArtifactVersion.parse(raw: String): ParseResult`
回傳 `Parsed(version)` 或 `Unparseable(raw, reason)`。**不拋例外、不回傳 null**——呼叫端必須明確處理無法解析的分支，避免被當成「無變動」。

**驗證規則**：
- `raw` 不得為空白
- 至少需有一個前導數字段，否則為 `Unparseable`
- 數字段溢位 `Int` 時為 `Unparseable`（reason 帶出實際字串）

---

## 2. `DependencyCoordinate` — 依賴座標

| 欄位 | 型別 | 說明 |
|---|---|---|
| `group` | `String` | 例：`io.lettuce` |
| `artifact` | `String` | 例：`lettuce-core` |

`toString()` → `"io.lettuce:lettuce-core"`。此字串即基準線檔與核准檔中的鍵，格式必須與 `parse()` 互為反函數。

---

## 3. `DependencySet` — 外溢依賴集合

對應 spec Key Entities「外溢依賴集合」。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `moduleName` | `String` | 例：`knolux-redis-spring-boot-starter` |
| `entries` | `Map<DependencyCoordinate, String>` | 座標 → 版本原字串（**存原字串而非解析後版本**，讓解析失敗也能被完整記錄與呈現） |

**不變條件**：`entries` 依 `toString()` 字典序排序後才輸出。序列化必須是決定性的——否則基準線檔會產生無意義的 diff 噪音，摧毀 R2 決策中「PR diff 可讀」這個核心價值。

**來源**：`runtimeClasspath` 的 `ResolutionResult`（見 research.md R1），排除本 repo 自身的 project 依賴。

---

## 4. `DeltaKind` — 變更類別

對應 spec FR-006 / FR-007。

| 值 | 阻擋性 | 說明 |
|---|---|---|
| `MAJOR` | ✅ 阻擋 | major 段前進；或 0.x 內 minor 段前進（research.md R5） |
| `MINOR` | 資訊性 | minor 段前進（1.x 以上） |
| `PATCH` | 資訊性 | patch 段或 qualifier 變動 |
| `ADDED` | 資訊性 | 基準線無、當前有（spec FR-011 明訂不阻擋） |
| `REMOVED` | ✅ 阻擋 | 基準線有、當前無（Netty 4.1.x 實例） |
| `DOWNGRADE` | ✅ 阻擋 | 版本後退。基準線曾提供的 API 可能消失，風險等同 major |
| `UNPARSEABLE` | ✅ 阻擋 | 任一側版本無法解析（FR-009） |

阻擋性為 `DeltaKind` 的固有屬性（`val blocking: Boolean`），不由呼叫端各自判斷——避免同一規則散落多處而失去一致性。

> `DOWNGRADE` 未在 spec 中列出，但 BOM 調整導致版本後退是真實可能。歸為阻擋性以維持「零漏報」的假設。已列入對 spec 的回饋。

---

## 5. `DependencyDelta` — 單一項差異

對應 spec Key Entities「依賴差異」。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `moduleName` | `String` | 模組歸屬（FR-013） |
| `coordinate` | `DependencyCoordinate` | |
| `from` | `String?` | 基準線版本原字串；`ADDED` 時為 null |
| `to` | `String?` | 當前版本原字串；`REMOVED` 時為 null |
| `kind` | `DeltaKind` | |
| `unparseableReason` | `String?` | 僅 `UNPARSEABLE` 時填寫 |

**不變條件**：`kind == ADDED` ⇒ `from == null && to != null`；`kind == REMOVED` ⇒ `from != null && to == null`；其餘 ⇒ 兩者皆非 null。

---

## 6. `Approval` — 核准紀錄

對應 spec Key Entities「核准紀錄」與 FR-014 ~ FR-017。

| 欄位 | 型別 | 必填 | 說明 |
|---|---|---|---|
| `module` | `String` | ✅ | 模組名 |
| `coordinate` | `DependencyCoordinate` | ✅ | |
| `from` | `String` | ✅ | 需與 delta 的 `from` **逐字相同** |
| `to` | `String?` | 移除時省略 | 需與 delta 的 `to` 逐字相同 |
| `kind` | `DeltaKind` | ✅ | 限 `MAJOR` / `REMOVED` / `DOWNGRADE` / `UNPARSEABLE` |
| `reason` | `String` | ✅ | 繁體中文說明，會出現在報告中 |

**比對規則（`ApprovalMatcher`）**：五個欄位（module、coordinate、from、to、kind）**全部逐字相符**才算核准。刻意不支援萬用字元或版本區間比對——

> 核准的是「這一次、這個依賴、從這一版到那一版」這個具體事實，不是一類事實。
> 版本一動，核准即失效並需重新審視。這是 FR-015「不得是全域開關」的最強形式。

**過期核准（stale approval）**：檔案中存在但當次執行未配對到任何 delta 的項目。不影響判定結果，但列入報告的資訊性區塊，供 `updateDependencyBaseline` 清除。

---

## 7. `GateVerdict` — 單一模組的判定結果

對應 spec Key Entities「閘門判定」。

| 欄位 | 型別 | 說明 |
|---|---|---|
| `moduleName` | `String` | |
| `status` | `PASSED` / `BLOCKED` / `SKIPPED_NO_BASELINE` | |
| `deltas` | `List<DependencyDelta>` | 該模組全部差異（含資訊性） |
| `blockedDeltas` | `List<DependencyDelta>` | 阻擋性且未被核准者 |
| `approvedDeltas` | `List<Pair<DependencyDelta, Approval>>` | 阻擋性但已核准者 |
| `skipReason` | `String?` | 僅 `SKIPPED_NO_BASELINE` 時填寫（FR-005） |

**狀態推導**：
- 基準線不存在 → `SKIPPED_NO_BASELINE`（**不失敗**，但必須記入報告）
- `blockedDeltas` 非空 → `BLOCKED`
- 其餘 → `PASSED`

---

## 8. `GateReport` — 一次執行的完整結果

| 欄位 | 型別 | 說明 |
|---|---|---|
| `verdicts` | `List<GateVerdict>` | 每個模組一筆 |

**FR-012（不得遇到第一項就中止）**：所有模組、所有 delta 一次算完才決定成敗。任務的 `@TaskAction` 只在**最後**依 `verdicts.any { it.status == BLOCKED }` 拋出 `GradleException`。

**FR-019（成敗皆須產出報告）**：報告的寫檔動作必須在拋例外**之前**完成。此順序與專案既有的資源管理慣例同源——`KnoluxS3ClientFactory` 也是先把 HTTP client 入快取再 build，確保失敗路徑仍可回收。

---

## 型別間關係

```text
DependencySet(baseline) ─┐
                         ├─> DeltaCalculator ──> List<DependencyDelta>
DependencySet(current) ──┘                              │
                                                        v
                              ApprovalStore ──> ApprovalMatcher
                                                        │
                                                        v
                                                  GateVerdict
                                                        │
                                                        v
                                    GateReport ──> ReportRenderer ──> Markdown
```

左側全為純函式（可單元測試、無 I/O）。git 存取僅存在於 `GitBaselineSource`，Gradle 存取僅存在於任務類別。
