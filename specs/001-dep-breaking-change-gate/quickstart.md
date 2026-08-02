# Quickstart：驗證閘門確實有效

**Feature**: [spec.md](./spec.md) | **Contracts**: [contracts/](./contracts/) | **Date**: 2026-08-01

本文件是**驗收指引**，不是實作說明。每個情境都對應 spec 的一條 Success Criteria，
可在實作完成後逐一執行以確認功能真的成立。

## 前置條件

- Java 25 toolchain（Temurin）
- 完整 git clone（**非**淺層）——閘門需要 merge-base 與 tag
- 無需 Docker

---

## 情境 1：重現 2026-08-01 事件（對應 SC-001）★ 最關鍵

這是整個功能存在的理由。redis 1.4.0 在自有原始碼零變更的情況下，
把 Lettuce 帶過了 major，而人工比對要到發布**之後**才發現。

**做法**：以 `knolux-redis-spring-boot-starter/v1.3.0` 的實際依賴集合作為測試 fixture，
斷言差異計算器對「當前（Spring Boot 4.1.0）」的解析結果同時產出兩項阻擋性差異。

一次性擷取 fixture（實作時執行，產物簽入 buildSrc 測試資源）：

```bash
git worktree add /tmp/v130 knolux-redis-spring-boot-starter/v1.3.0
# 於 /tmp/v130 解析 runtimeClasspath，輸出為基準線檔格式，
# 存成 buildSrc/src/test/resources/fixtures/redis-v1.3.0-baseline.txt
git worktree remove /tmp/v130
```

**驗證**：

```bash
./gradlew -p buildSrc test --tests '*Regression2026080*'
```

**預期**：測試通過，斷言涵蓋：

| 依賴 | 期望類別 |
|---|---|
| `io.lettuce:lettuce-core` 6.8.2.RELEASE → 7.5.2.RELEASE | `MAJOR` |

> **原始事件回顧有一處記錯，實作時已以 fixture 更正**：當時記為「`io.netty` 4.1.x 整條線消失」，
> 但以 `v1.3.0` worktree 實際解析後確認，**兩側 runtimeClasspath 都不含 `io.netty` 4.1.x**，
> 因此不存在依賴移除，真正發生的是 Netty 版本線隨 Lettuce 一併換代。
> 測試以 `redis 兩側都不存在 Netty 4-1-x 因此沒有依賴移除` 明確固定此事實，
> 免得日後有人依錯誤前提「補上」一個永遠不會成立的斷言。

此情境的價值因此在於**一次列出全部阻擋項**（FR-012），而非補抓某個特定的漏看項目。

---

## 情境 2：破壞性變更被阻擋（對應 SC-002 / User Story 1）

```bash
git switch -c tmp/verify-gate
# 在 build.gradle.kts 將 Spring Boot BOM 降到 4.0.6（製造反向的 major 變動）
./gradlew checkDependencyCompatibility
```

**預期**：建置失敗。console 逐筆列出模組、座標、`from → to`、類別（FR-013），
且**所有**阻擋項一次列完，不在第一項就中止（FR-012）。
`build/reports/dependency-gate/gate-report.md` 已產出（FR-019）。

```bash
git switch - && git branch -D tmp/verify-gate
```

---

## 情境 3：核准後放行（對應 User Story 4 / FR-014 ~ FR-016）

於 `gradle/dependency-approvals.toml` 加入對應的 `[[approval]]`
（格式見 [contracts/file-formats.md](./contracts/file-formats.md)），再執行一次。

> **不建議直接沿用情境 2 的降版狀態**：Spring Boot BOM 降版會一次產生數十項阻擋差異
> （實測 68 項），逐筆手寫核准不具可操作性。改在單一模組的 `build.gradle.kts` 以
> `DependencyManagementExtension` 指定單一座標的舊版號，製造 2~3 項差異即可
> （`resolutionStrategy.force` 會被 BOM 覆寫，無效）。

**預期**：建置通過；報告的「已核准的破壞性變動」區塊列出該項與其 `reason` 原文。

**同時驗證 FR-015（不得是全域開關）**：
把核准的 `to` 改成一個不相符的版本字串，重跑 → **必須重新失敗**。
逐字比對是這條需求的實質保障；若改了版本仍放行，代表比對過鬆，需修正。

---

## 情境 4：minor / patch 不阻擋（對應 SC-003 / User Story 3）

```bash
# 於 gradle/libs.versions.toml 將 awssdk 調成僅差 patch 的版本
./gradlew checkDependencyCompatibility
```

**預期**：建置**通過**。該變動出現在報告的「資訊性變動」區塊。

**同時確認**：即使**未**執行 `updateDependencyBaseline`（基準線檔仍停在舊版號），建置依然通過，
只在 console 出現一段警告，內容須說明「以上皆非阻擋性變動，依 FR-011 不讓建置失敗」
以及發版前仍須補上。Dependabot 不會替你重新產生基準線，若這裡失敗，等於每支 bot PR 都紅燈。

此情境決定閘門能否長期存活——若 Dependabot 的每週 PR 都紅燈，閘門三週內就會被停用。

---

## 情境 5：忘記重新產生基準線（research.md M1 / FR-024）

```bash
# 任意調整一個依賴版本但不執行 updateDependencyBaseline
./gradlew checkDependencyBaseline
```

**預期**：失敗，訊息明確指示執行 `./gradlew updateDependencyBaseline`，並列出不一致的行。

此任務是**發版前**的一致性驗證，任何落差皆失敗，不套用情境 4 的寬容——
發布是不可回收的動作。閘門本體則相反：僅在落差含阻擋性項目時失敗（FR-011a），
可用下列指令對照兩者的差異：

```bash
./gradlew checkDependencyCompatibility --base=HEAD   # patch 落差 → 通過（僅警告）
./gradlew checkDependencyBaseline                    # 同樣的落差 → 失敗
```

---

## 情境 6：版本無法解析（對應 FR-009）

以單元測試覆蓋，不需製造真實依賴：

```bash
./gradlew -p buildSrc test --tests '*ArtifactVersion*'
```

**預期**：`latest.release`、`master-SNAPSHOT`、空字串等輸入回傳 `Unparseable`，
且 `reason` 帶出**原始字串**。特別確認：**不得**回傳 null 或拋出被上層吞掉的例外——
靜默放行是本功能要根除的模式。

同時確認下列本專案實際存在的形式皆能正確解析：
`7.5.2.RELEASE`、`4.2.15.Final`、`2.49.3`、`2.6`、`1.5.34`。

---

## 情境 7：新模組自動納入保護（對應 SC-006 / FR-002）

於 `settings.gradle.kts` 暫時 `include` 一個最小模組，執行閘門。

**預期**：該模組出現在報告的「已跳過的模組」區塊，理由為無基準線；建置**不失敗**（FR-005）。
過程中**未修改**閘門本身任何設定。驗證後還原 `settings.gradle.kts`。

---

## 情境 8：本地與 CI 結果一致（對應 SC-007 / FR-023）

```bash
./gradlew checkDependencyCompatibility --base=origin/dev
```

**預期**：與同一 commit 在 CI 上的判定與報告內容完全一致。

---

## 情境 9：發版報告可直接使用（對應 SC-004 / User Story 2）

```bash
./gradlew dependencyChangeReport --since=knolux-redis-spring-boot-starter/v1.3.0
```

**預期**：`build/reports/dependency-gate/change-report.md` 的「⚠️ 升級前必讀」段落
可**原文**貼入 `CHANGELOG.md` 而不需重新排版，內容與 `CHANGELOG.md:20-34` 現有的
人工撰寫版本實質相符。此任務**永不失敗**。

---

## 情境 10：時間預算（對應 SC-005）

```bash
./gradlew checkDependencyCompatibility --base=origin/dev  # 量測 wall-clock
```

**預期**：相較於未加入閘門的同一組 CI 步驟，總時長增加**不超過 3 分鐘**。
含 buildSrc 編譯與測試在內。若超出，優先檢查是否誤觸發了完整建置。

---

## 驗收檢查表

| # | 情境 | 對應 |
|---|---|---|
| 1 | 重現 2026-08-01 事件 | SC-001 ★ |
| 2 | 破壞性變更被阻擋 | SC-002 |
| 3 | 核准後放行 + 逐字比對 | FR-014 ~ FR-016 |
| 4 | minor / patch 不阻擋 | SC-003 |
| 5 | 基準線過期被偵測 | research.md M1 |
| 6 | 版本無法解析即失敗 | FR-009 |
| 7 | 新模組自動納入 | SC-006 |
| 8 | 本地與 CI 一致 | SC-007 |
| 9 | 發版報告可直接使用 | SC-004 |
| 10 | 時間預算 | SC-005 |
