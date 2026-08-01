package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * 對應 contracts/report-format.md 的情形 A / C / D / E 與 spec FR-018 ~ FR-022。
 *
 * 這裡斷言的是**格式**而非措辭美感——報告的「阻擋性變動」區塊必須能原文貼進
 * `CHANGELOG.md` 的「⚠️ 升級前必讀」段落。一旦格式漂移（表格欄位改名、版本字串被正規化、
 * 座標少了反引號），貼上去就得手工修，發版者很快會退回人工比對依賴樹，
 * 這個功能存在的理由也就消失了。
 */
class ReportRendererTest {

    @Nested
    inner class 情形A_存在未核准的阻擋性變更 {

        private val markdown = ReportRenderer.renderGateReport(blockedReport())

        @Test
        fun `標頭帶出比較基準與判定`() {
            assertTrue(markdown.startsWith("# 依賴相容性閘門報告"), "報告首行應為標題，實際為『${markdown.lineSequence().first()}』")
            assertContains("**比較基準**：`origin/dev`（merge-base `a1b2c3d`）")
            assertContains("**判定**：❌ 阻擋 — 2 項未核准的破壞性變更")
        }

        @Test
        fun `阻擋性與資訊性為不同的區塊標題`() {
            // FR-020：兩者混在同一個標題下，讀者無法一眼分辨哪些需要行動。
            assertContains("## ❌ 阻擋性變動（需核准或還原）")
            assertContains("## ℹ️ 資訊性變動")
        }

        @Test
        fun `阻擋性區塊的小標即為可貼進 CHANGELOG 的升級前必讀`() {
            assertContains("### ⚠️ 升級前必讀：`knolux-redis-spring-boot-starter` 的傳遞依賴破壞性變更")
        }

        @Test
        fun `major 跳動與依賴移除都出現在阻擋性表格`() {
            assertContains("| `io.lettuce:lettuce-core` | 6.8.2.RELEASE | **7.5.2.RELEASE** | **跨 major 版本** |")
            assertContains("| `io.netty:netty-transport` | 4.1.125.Final | **（已移除）** | **依賴移除** |")
        }

        @Test
        fun `版本字串原文呈現不做正規化`() {
            // FR-009／FR-013：`7.5.2.RELEASE` 被寫成 `7.5.2` 會讓讀者查不到對應的 release notes。
            assertContains("7.5.2.RELEASE")
            assertContains("4.1.125.Final")
            assertFalse(
                markdown.contains("| `io.lettuce:lettuce-core` | 6.8.2 |"),
                "版本不得被正規化為 major.minor.patch",
            )
        }

        @Test
        fun `資訊性表格含 patch minor 與新增`() {
            assertContains("| `ch.qos.logback:logback-classic` | 1.5.32 | 1.5.34 | patch |")
            assertContains("| `org.springframework.data:spring-data-redis` | 4.0.5 | 4.1.0 | minor |")
            assertContains("| `org.springframework:spring-messaging` | — | 7.0.8 | 新增 |")
        }

        @Test
        fun `無資訊性變動的模組明白寫出無變動`() {
            // 留白會讓人誤以為報告漏了這個模組。
            assertContains("### `knolux-s3-spring-boot-starter`")
            assertContains("無變動。")
        }

        @Test
        fun `附上如何處理的具體指引與 toml 範本`() {
            // FR-018：只說「被擋住了」而不說怎麼辦，維護者的下一步是去找人問，不是自己解決。
            assertContains("## 如何處理阻擋性變動")
            assertContains("gradle/dependency-approvals.toml")
            assertContains("[[approval]]")
            assertContains("""coordinate = "io.lettuce:lettuce-core"""")
            assertContains("""kind = "MAJOR"""")
            assertContains("""from = "6.8.2.RELEASE"""")
            assertContains("""to = "7.5.2.RELEASE"""")
        }

        @Test
        fun `表格為標準 GFM 不依賴渲染擴充`() {
            // FR-021：報告會出現在 GitHub PR 留言、CI log 與 CHANGELOG 三個地方，
            // 只要用了某一處才支援的語法，其他兩處就會顯示成亂碼。
            val tableLines = markdown.lines().filter { it.startsWith("|") }
            assertTrue(tableLines.isNotEmpty(), "報告應含表格")
            tableLines.forEach { line ->
                assertTrue(line.endsWith("|"), "表格列應以 | 結尾：『$line』")
            }
            assertFalse(markdown.contains("<"), "不得使用 HTML 標籤")
            assertFalse(markdown.contains(":::"), "不得使用 admonition 等渲染擴充語法")
        }

        @Test
        fun `依賴座標一律以反引號包覆`() {
            // 與既有 CHANGELOG 寫法一致；沒有反引號時 `io.netty:netty-*` 之類的字串會被誤判為強調語法。
            listOf(
                "io.lettuce:lettuce-core",
                "io.netty:netty-transport",
                "ch.qos.logback:logback-classic",
            ).forEach { coordinate ->
                assertContains("`$coordinate`")
            }
        }

        @Test
        fun `說明文字為繁體中文`() {
            // FR-022／憲章 VI：本專案的 Javadoc、註解、README、CHANGELOG 皆為繁體中文。
            listOf("依賴相容性閘門報告", "比較基準", "判定", "阻擋性變動", "資訊性變動", "如何處理").forEach {
                assertContains(it)
            }
        }

        private fun assertContains(expected: String) =
            assertTrue(markdown.contains(expected), "報告應含『$expected』，實際內容為：\n$markdown")
    }

    @Nested
    inner class 情形C_無變動 {

        private val markdown = ReportRenderer.renderGateReport(
            GateReport(
                comparisonBase = "`origin/dev`（merge-base `a1b2c3d`）",
                verdicts = listOf(GateVerdict.evaluate(REDIS, emptyList()), GateVerdict.evaluate(S3, emptyList())),
            ),
        )

        @Test
        fun `判定為通過且明說無變動`() {
            assertTrue(markdown.contains("**判定**：✅ 通過 — 外溢依賴無變動"), "實際內容為：\n$markdown")
        }

        @Test
        fun `不出現阻擋性區塊與處理指引`() {
            // 沒有問題卻印出「如何處理阻擋性變動」，會訓練讀者忽略整份報告。
            assertFalse(markdown.contains("## ❌ 阻擋性變動"), "無變動時不應有阻擋性區塊")
            assertFalse(markdown.contains("## 如何處理阻擋性變動"), "無變動時不應有處理指引")
        }
    }

    @Nested
    inner class 情形D_模組無基準線 {

        private val reason = "基準線檔 `gradle/dependency-baseline/knolux-new-spring-boot-starter.txt` " +
            "在比較基準上不存在（模組尚未發布過）"

        private val markdown = ReportRenderer.renderGateReport(
            GateReport(
                comparisonBase = "`origin/dev`",
                verdicts = listOf(
                    GateVerdict.evaluate(REDIS, emptyList()),
                    GateVerdict.skipped("knolux-new-spring-boot-starter", reason),
                ),
            ),
        )

        @Test
        fun `跳過的模組必須出現在報告中並附原因`() {
            // FR-005 允許跳過，但憲章 IV 不允許靜默——
            // 靜默跳過等同「這個模組沒被保護，而沒有人知道」。
            assertTrue(markdown.contains("## ⏭️ 已跳過的模組"), "實際內容為：\n$markdown")
            assertTrue(markdown.contains("`knolux-new-spring-boot-starter`"))
            assertTrue(markdown.contains(reason), "原因需原文呈現，實際內容為：\n$markdown")
        }

        @Test
        fun `跳過不影響通過判定`() {
            assertTrue(markdown.contains("✅ 通過"), "跳過不應被算成失敗")
        }
    }

    @Nested
    inner class 情形E_版本無法解析 {

        private val reason = "版本字串『latest.release』找不到前導的數字版本段，無法判斷 major/minor/patch"

        private val markdown = ReportRenderer.renderGateReport(
            GateReport(
                comparisonBase = "`origin/dev`",
                verdicts = listOf(
                    GateVerdict.evaluate(
                        S3,
                        listOf(
                            DependencyDelta(
                                moduleName = S3,
                                coordinate = DependencyCoordinate.parse("com.example:weird-lib"),
                                from = "latest.release",
                                to = "2.0.0",
                                kind = DeltaKind.UNPARSEABLE,
                                unparseableReason = reason,
                            ),
                        ),
                    ),
                ),
            ),
        )

        @Test
        fun `無法解析的版本自成一個區塊`() {
            // 混進「跨 major 版本」表格會謊報事實——實際上是「不知道」而非「知道它跨了 major」。
            assertTrue(markdown.contains("## ❌ 無法判定的版本"), "實際內容為：\n$markdown")
            assertFalse(
                markdown.contains("**跨 major 版本**"),
                "無法解析不得被呈現為已判定的 major 跳動",
            )
        }

        @Test
        fun `必須帶出版本字串原文與原因`() {
            // FR-009：不附上原文，維護者無從得知該去修哪一個版本宣告。
            assertTrue(markdown.contains("latest.release"), "需帶出版本原文，實際內容為：\n$markdown")
            assertTrue(markdown.contains(reason), "需帶出無法解析的原因")
        }

        @Test
        fun `無法解析仍算阻擋`() {
            assertTrue(markdown.contains("❌ 阻擋"), "無法判定時放行等同於沒有閘門")
        }
    }

    // ---------- fixtures ----------

    private fun blockedReport(): GateReport {
        val redis = GateVerdict.evaluate(
            REDIS,
            listOf(
                delta(REDIS, "ch.qos.logback:logback-classic", "1.5.32", "1.5.34", DeltaKind.PATCH),
                delta(REDIS, "io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                DependencyDelta(
                    REDIS,
                    DependencyCoordinate.parse("io.netty:netty-transport"),
                    "4.1.125.Final",
                    null,
                    DeltaKind.REMOVED,
                ),
                delta(REDIS, "org.springframework.data:spring-data-redis", "4.0.5", "4.1.0", DeltaKind.MINOR),
                DependencyDelta(
                    REDIS,
                    DependencyCoordinate.parse("org.springframework:spring-messaging"),
                    null,
                    "7.0.8",
                    DeltaKind.ADDED,
                ),
            ),
        )
        return GateReport(
            comparisonBase = "`origin/dev`（merge-base `a1b2c3d`）",
            verdicts = listOf(redis, GateVerdict.evaluate(S3, emptyList())),
        )
    }

    private fun delta(module: String, raw: String, from: String, to: String, kind: DeltaKind) =
        DependencyDelta(module, DependencyCoordinate.parse(raw), from, to, kind)

    private companion object {
        const val REDIS = "knolux-redis-spring-boot-starter"
        const val S3 = "knolux-s3-spring-boot-starter"
    }
}
