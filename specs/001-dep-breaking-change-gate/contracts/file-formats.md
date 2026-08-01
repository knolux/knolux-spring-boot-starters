# Contract：檔案格式

**Feature**: [../spec.md](../spec.md) | **Date**: 2026-08-01

---

## 1. 基準線檔 `gradle/dependency-baseline/<module>.txt`

由 `updateDependencyBaseline` 產生，**每個已發布模組一份**。

```text
# 由 ./gradlew updateDependencyBaseline 產生，請勿手動編輯。
# 此檔記錄本模組會外溢給下游消費者的依賴集合（runtimeClasspath 解析結果）。
ch.qos.logback:logback-classic:1.5.34
io.lettuce:lettuce-core:7.5.2.RELEASE
io.netty:netty-buffer:4.2.15.Final
org.springframework.data:spring-data-redis:4.1.0
```

**格式規則**（全部為硬性要求，違反即為 bug）：

| 規則 | 理由 |
|---|---|
| 每行 `group:artifact:version`，無空白 | 剖析簡單，無需外部函式庫 |
| 以完整行字串**字典序**排序 | 決定性輸出。排序不穩定會讓基準線檔產生無意義 diff，摧毀「PR diff 可讀」這個核心價值 |
| `#` 開頭為註解，空行忽略 | 標頭警語 |
| 檔尾恆為單一換行 | 避免 diff 出現 `\ No newline at end of file` 雜訊 |
| 換行一律 LF | 本 repo 於 Windows 開發，CI 於 Linux；不統一會導致整檔 diff |
| UTF-8 無 BOM | |

**版本欄保留原字串**，不做正規化（`7.5.2.RELEASE` 不得寫成 `7.5.2`）。
正規化會遺失資訊，也會讓基準線與實際發布的 POM 對不上。

**檔案不存在時**：視為 FR-005 的「無基準線」——跳過該模組、記入報告、**不失敗**。

---

## 2. 核准檔 `gradle/dependency-approvals.toml`

由維護者手寫。與 `gradle/libs.versions.toml` 同目錄同格式，維持慣例一致。

```toml
# 對「阻擋性依賴變更」的逐筆核准。
#
# 每筆核准只對一個具體的版本區間生效；版本一動即失效，需重新審視。
# 基準線推進之後，已失配的項目會由 ./gradlew updateDependencyBaseline 清除。
#
# kind 可用值：MAJOR / REMOVED / DOWNGRADE / UNPARSEABLE

[[approval]]
module = "knolux-redis-spring-boot-starter"
coordinate = "io.lettuce:lettuce-core"
kind = "MAJOR"
from = "6.8.2.RELEASE"
to = "7.5.2.RELEASE"
reason = "隨 Spring Boot 4.1.0 升級而必然發生；已於 CHANGELOG 的「升級前必讀」段落揭露"

[[approval]]
module = "knolux-redis-spring-boot-starter"
coordinate = "io.netty:netty-transport"
kind = "REMOVED"
from = "4.1.125.Final"
reason = "Netty 4.1.x 線隨 Spring Boot 4.1.0 移除，統一至 4.2.x；已於 CHANGELOG 揭露"
```

**欄位規則**：

| 欄位 | 必填 | 說明 |
|---|---|---|
| `module` | ✅ | 需對應 `settings.gradle.kts` 中存在的模組 |
| `coordinate` | ✅ | `group:artifact` |
| `kind` | ✅ | 限阻擋性類別；填入資訊性類別（如 `PATCH`）即為設定錯誤，須 fail-fast |
| `from` | ✅ | 逐字相符 |
| `to` | `kind = "REMOVED"` 時省略 | 逐字相符 |
| `reason` | ✅ | 繁體中文，會原文出現在報告中 |

**比對語意**：五欄全部逐字相符才算核准。**不支援萬用字元、不支援版本區間**。

**檔案不存在**：視為零筆核准，正常運作（尚無任何破壞性變更需核准時的常態）。

**格式錯誤或欄位缺漏**：fail-fast，訊息帶出實際內容與所在項目序號。
不得靜默忽略無法解析的項目——否則打錯字的核准會變成「沒有核准」，而使用者以為擋不住的東西已被放行。

**剖析**：以 `org.tomlj:tomlj` 於 buildSrc 剖析。此依賴僅存在於建置邏輯，
不進入任何發布的 artifact，不影響下游。

---

## 3. 報告輸出位置

| 檔案 | 產生者 |
|---|---|
| `build/reports/dependency-gate/gate-report.md` | `checkDependencyCompatibility` |
| `build/reports/dependency-gate/change-report.md` | `dependencyChangeReport` |

兩者格式見 [report-format.md](./report-format.md)。位於 `build/` 之下，隨 `clean` 一併清除，不進版控。

CI 需以 `actions/upload-artifact` 上傳，且 `if: always()`——閘門失敗時報告尤其重要（FR-019）。
