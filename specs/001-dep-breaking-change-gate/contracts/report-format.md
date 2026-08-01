# Contract：報告輸出格式

**Feature**: [../spec.md](../spec.md) | **Date**: 2026-08-01

對應 spec FR-018 ~ FR-022 與 User Story 2。

**設計約束**：報告的「阻擋性變動」區塊必須能**原文貼進** `CHANGELOG.md` 的「⚠️ 升級前必讀」段落。
本 repo 的 CHANGELOG 已有既定寫法（見 `CHANGELOG.md:20-34`），報告格式即向該寫法對齊，
而非另創一套再要求發版者手動轉換。

---

## 1. 閘門報告 `gate-report.md`

### 情形 A — 存在未核准的阻擋性變更（建置失敗）

````markdown
# 依賴相容性閘門報告

**比較基準**：`origin/dev`（merge-base `a1b2c3d`）
**判定**：❌ 阻擋 — 2 項未核准的破壞性變更

---

## ❌ 阻擋性變動（需核准或還原）

### ⚠️ 升級前必讀：`knolux-redis-spring-boot-starter` 的傳遞依賴破壞性變更

| 依賴 | 基準線 | 當前 | 影響 |
|---|---|---|---|
| `io.lettuce:lettuce-core` | 6.8.2.RELEASE | **7.5.2.RELEASE** | **跨 major 版本** |
| `io.netty:netty-transport` | 4.1.125.Final | **（已移除）** | **依賴移除** |

---

## ℹ️ 資訊性變動

### `knolux-redis-spring-boot-starter`

| 依賴 | 基準線 | 當前 | 類別 |
|---|---|---|---|
| `ch.qos.logback:logback-classic` | 1.5.32 | 1.5.34 | patch |
| `org.springframework.data:spring-data-redis` | 4.0.5 | 4.1.0 | minor |
| `org.springframework:spring-messaging` | — | 7.0.8 | 新增 |

### `knolux-s3-spring-boot-starter`

無變動。

---

## 如何處理阻擋性變動

若變更並非有意，請還原造成該變更的依賴調整。

若變更確屬有意且已評估影響，於 `gradle/dependency-approvals.toml` 逐筆加入核准：

```toml
[[approval]]
module = "knolux-redis-spring-boot-starter"
coordinate = "io.lettuce:lettuce-core"
kind = "MAJOR"
from = "6.8.2.RELEASE"
to = "7.5.2.RELEASE"
reason = "（填寫理由）"
```

核准後請將上表的「升級前必讀」段落一併寫入 `CHANGELOG.md` 與 GitHub Release notes。
````

### 情形 B — 阻擋性變更已核准（建置通過）

```markdown
# 依賴相容性閘門報告

**比較基準**：`origin/dev`（merge-base `a1b2c3d`）
**判定**：✅ 通過 — 1 項破壞性變更已核准

## ✅ 已核准的破壞性變動

| 模組 | 依賴 | 基準線 | 當前 | 類別 | 核准理由 |
|---|---|---|---|---|---|
| `knolux-redis-spring-boot-starter` | `io.lettuce:lettuce-core` | 6.8.2.RELEASE | 7.5.2.RELEASE | major | 隨 Spring Boot 4.1.0 升級而必然發生；已於 CHANGELOG 揭露 |

⚠️ 已核准不代表下游不受影響。發版時仍須將上表寫入 CHANGELOG 的「升級前必讀」段落。
```

### 情形 C — 無變動

```markdown
# 依賴相容性閘門報告

**比較基準**：`origin/dev`（merge-base `a1b2c3d`）
**判定**：✅ 通過 — 外溢依賴無變動
```

### 情形 D — 模組無基準線（FR-005）

```markdown
## ⏭️ 已跳過的模組

| 模組 | 原因 |
|---|---|
| `knolux-new-spring-boot-starter` | 基準線檔 `gradle/dependency-baseline/knolux-new-spring-boot-starter.txt` 在比較基準上不存在（模組尚未發布過） |
```

跳過**必須**出現在報告中。靜默跳過等同於「這個模組沒被保護，而沒有人知道」——
正是憲章「IV. Fail-Fast 與可觀測性」反對的模式。

### 情形 E — 版本無法解析（FR-009，建置失敗）

```markdown
## ❌ 無法判定的版本

| 模組 | 依賴 | 基準線 | 當前 | 問題 |
|---|---|---|---|---|
| `knolux-s3-spring-boot-starter` | `com.example:weird-lib` | `latest.release` | `2.0.0` | 基準線版本字串 `latest.release` 無前導數字段，無法判定 major |
```

訊息**必須**帶出版本字串原文。

---

## 2. 發版報告 `change-report.md`

由 `dependencyChangeReport --since=<tag>` 產生。結構與閘門報告相同，差異：

- 標頭為 `**比較基準**：`knolux-redis-spring-boot-starter/v1.3.0`（上一個發布版本）`
- 無「判定」行、無「如何處理」段落——純資訊，永不失敗
- 阻擋性區塊標題改為「⚠️ 升級前必讀」，可整段貼入 CHANGELOG

---

## 3. Console 輸出

檔案報告是給人讀的；console 輸出是給「在 CI log 裡快速掃過的人」讀的。
失敗時 console **必須**含完整的阻擋清單，不得只寫「請見報告檔」——
CI 上點開 artifact 的成本高到讓人選擇忽略。

```text
> Task :knolux-redis-spring-boot-starter:checkDependencyCompatibility FAILED

依賴相容性閘門：❌ 阻擋（2 項未核准的破壞性變更）

  [MAJOR]   knolux-redis-spring-boot-starter  io.lettuce:lettuce-core
            6.8.2.RELEASE -> 7.5.2.RELEASE
  [REMOVED] knolux-redis-spring-boot-starter  io.netty:netty-transport
            4.1.125.Final -> （已移除）

  完整報告：build/reports/dependency-gate/gate-report.md
  核准方式：於 gradle/dependency-approvals.toml 逐筆加入 [[approval]]
```

---

## 格式硬性要求

| 要求 | 對應 |
|---|---|
| 說明文字一律繁體中文 | FR-022、憲章 VI |
| 阻擋性與資訊性必須是不同的區塊標題 | FR-020 |
| 建置成功與失敗皆須產出檔案報告 | FR-019 |
| 表格為標準 GitHub-flavored Markdown，不依賴任何渲染擴充 | FR-021 |
| 版本字串原文呈現，不正規化 | FR-009、FR-013 |
| 依賴座標以反引號包覆 | 與既有 CHANGELOG 寫法一致 |
