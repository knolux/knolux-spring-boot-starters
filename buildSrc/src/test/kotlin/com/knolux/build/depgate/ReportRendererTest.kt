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

        /**
         * US3／FR-010、FR-011：非阻擋性變動 MUST NOT 出現在阻擋性區塊。
         *
         * 只斷言「有出現在報告裡」不夠——patch 若同時被列進阻擋性表格，字串仍然找得到，
         * 但維護者看到的是一份說他的 Dependabot PR 被 patch 擋下的報告。誤擋的代價不是
         * 多按一次重跑，是閘門被關掉。
         *
         * `ADDED` 特別點名：直覺上「多了東西」聽起來也像破壞性變更，是最容易被誤歸的一類。
         */
        @Test
        fun `patch 與 minor 與新增只出現在資訊性區塊`() {
            val blocking = sectionOf("## ❌ 阻擋性變動（需核准或還原）")

            listOf(
                "ch.qos.logback:logback-classic",
                "org.springframework.data:spring-data-redis",
                "org.springframework:spring-messaging",
            ).forEach { coordinate ->
                assertFalse(
                    blocking.contains(coordinate),
                    "『$coordinate』屬非阻擋性變動，不得出現在阻擋性區塊：\n$blocking",
                )
            }
        }

        /** 取出以 [heading] 起始的區塊；區塊由 `---` 分隔（見 `ReportRenderer.SECTION_SEPARATOR`）。 */
        private fun sectionOf(heading: String): String {
            val section = markdown.split("\n\n---\n\n").firstOrNull { it.startsWith(heading) }
            assertTrue(section != null, "報告應含『$heading』區塊，實際內容為：\n$markdown")
            return section!!
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
    inner class 情形B_阻擋性變更已核准 {

        private val markdown = ReportRenderer.renderGateReport(approvedReport())

        @Test
        fun `判定為通過並說明已核准的筆數`() {
            assertContains("**判定**：✅ 通過 — 1 項破壞性變更已核准")
        }

        @Test
        fun `已核准表格帶出核准理由原文`() {
            // 理由是這份報告唯一無法自動產生的部分，也是發版時要抄進 CHANGELOG 的那一段；
            // 被截斷或改寫，維護者就得回頭翻 TOML，等於報告沒寫。
            assertContains("## ✅ 已核准的破壞性變動")
            assertContains(
                "| `knolux-redis-spring-boot-starter` | `io.lettuce:lettuce-core` | 6.8.2.RELEASE | " +
                    "7.5.2.RELEASE | major | $REASON |",
            )
        }

        @Test
        fun `保留已核准不代表下游不受影響的提醒`() {
            // 核准解除的是「建置阻擋」，不是「下游會不會壞」。少了這句，
            // 核准會被當成「處理完了」，而揭露就不會被寫進 CHANGELOG。
            assertContains("⚠️ 已核准不代表下游不受影響")
            assertContains("升級前必讀")
        }

        @Test
        fun `已核准者不進阻擋性區塊也不附處理指引`() {
            assertFalse(markdown.contains("## ❌ 阻擋性變動"), "已核准不應仍列為阻擋，實際內容為：\n$markdown")
            assertFalse(markdown.contains("## 如何處理阻擋性變動"), "已通過時印出處理指引會訓練讀者忽略整份報告")
        }

        @Test
        fun `過期核准列入資訊性區塊並說明清除方式`() {
            // FR-017 的可觀測面：不講出來，過期核准只會越積越多，
            // 最終沒有人敢動這份檔案，也就沒有人會再審視它。
            assertContains("## ℹ️ 過期的核准")
            assertContains("`org.apache.commons:commons-lang3`")
            assertContains("updateDependencyBaseline")
        }

        @Test
        fun `沒有過期核准時不出現該區塊`() {
            val clean = ReportRenderer.renderGateReport(
                GateReport(comparisonBase = "`origin/dev`", verdicts = listOf(GateVerdict.evaluate(REDIS, emptyList()))),
            )

            assertFalse(clean.contains("過期的核准"), "沒有過期核准卻立一個空區塊只是雜訊")
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

        /**
         * 「通過」可以，「無變動」不行：一個模組都沒比對過時，說得出口的只有
         * 「這次沒擋下任何東西」，而不是「沒有東西需要擋」。
         */
        @Test
        fun `全部模組都跳過時判定行不得宣稱無變動`() {
            val allSkipped = ReportRenderer.renderGateReport(
                GateReport(
                    comparisonBase = "`origin/dev`",
                    verdicts = listOf(GateVerdict.skipped(REDIS, reason), GateVerdict.skipped(S3, reason)),
                ),
            )

            assertFalse(allSkipped.contains("外溢依賴無變動"), "什麼都沒比過，不得宣稱無變動：\n$allSkipped")
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

    /**
     * 發版報告 `change-report.md`（contracts/report-format.md §2）。
     *
     * 與閘門報告的差異全都源自同一件事：**這份報告不做判定，只做揭露**。
     * 發版當下需要的是「下游要注意什麼」，不是「這支 PR 能不能合」——
     * 留著「判定」與「如何處理」只會讓發版者以為還有什麼事沒做完。
     */
    @Nested
    inner class 發版報告 {

        private val markdown = ReportRenderer.renderChangeReport(releaseReport())

        @Test
        fun `標頭指明比較基準為上一個發布版本`() {
            assertTrue(markdown.startsWith("# 依賴變更報告"), "實際首行為『${markdown.lineSequence().first()}』")
            assertContains("**比較基準**：`$REDIS/v1.3.0`（上一個發布版本）")
        }

        @Test
        fun `無判定行`() {
            // 發版報告永不失敗（T049），出現「判定」會讓讀者去找一個不存在的成敗結論。
            assertFalse(markdown.contains("**判定**"), "實際內容為：\n$markdown")
            assertFalse(markdown.contains("❌ 阻擋"), "實際內容為：\n$markdown")
        }

        @Test
        fun `無如何處理段落`() {
            // 那段是給「被擋下來的人」看的；發版時該做的是把揭露寫進 CHANGELOG，不是去加核准。
            assertFalse(markdown.contains("## 如何處理阻擋性變動"), "實際內容為：\n$markdown")
            assertFalse(markdown.contains("[[approval]]"), "實際內容為：\n$markdown")
        }

        @Test
        fun `破壞性變動的區塊標題即為升級前必讀`() {
            assertContains("## ⚠️ 升級前必讀")
            assertFalse(
                markdown.contains("## ❌ 阻擋性變動（需核准或還原）"),
                "發版報告沒有「阻擋」這回事，實際內容為：\n$markdown",
            )
        }

        /**
         * 本測試是這份報告最重要的一條：**核准解除的是建置阻擋，不是下游會不會壞**。
         * 若已核准的破壞性變更在發版報告中消失，2026-08-01 的失效模式就會原封不動地重演——
         * 只是這次「沒揭露」的原因從「沒人發現」變成「工具幫忙藏起來了」。
         */
        @Test
        fun `已核准的破壞性變更仍列入升級前必讀`() {
            assertContains("`io.lettuce:lettuce-core`")
            assertContains("6.8.2.RELEASE")
            assertContains("7.5.2.RELEASE")
            assertFalse(
                markdown.contains("## ✅ 已核准的破壞性變動"),
                "發版報告不分核准與否，一律揭露，實際內容為：\n$markdown",
            )
        }

        @Test
        fun `依賴移除同樣列入升級前必讀`() {
            assertContains("`io.netty:netty-transport`")
            assertContains("**依賴移除**")
        }

        @Test
        fun `資訊性變動仍完整保留`() {
            // 發版報告的讀者要的是完整清單；patch 變動雖不阻擋，仍可能是下游排查問題的線索。
            assertContains("## ℹ️ 資訊性變動")
            assertContains("`ch.qos.logback:logback-classic`")
        }

        @Test
        fun `無發布 tag 的模組必須揭露而非略過`() {
            assertContains("## ⏭️ 已跳過的模組")
            assertContains("`$S3`")
        }

        @Test
        fun `版本字串原文呈現不做正規化`() {
            assertContains("6.8.2.RELEASE")
            assertFalse(markdown.contains("| 6.8.2 |"), "版本一旦被正規化，貼進 CHANGELOG 就與實際 POM 不符")
        }

        /** 情形 C 的發版版本：無變動時仍要產出報告，並明白寫出「無變動」。 */
        @Test
        fun `無任何變動時明說無變動而非產出空白報告`() {
            val empty = ReportRenderer.renderChangeReport(
                GateReport(
                    comparisonBase = "`$REDIS/v1.3.0`（上一個發布版本）",
                    verdicts = listOf(GateVerdict.evaluate(REDIS, emptyList())),
                ),
            )

            assertTrue(empty.contains("外溢依賴無變動"), "實際內容為：\n$empty")
        }

        /**
         * 「全部模組都跳過」與「全部模組都沒變動」在資料上長得一樣（deltas 皆為空），
         * 但意義完全相反：前者是**什麼都沒比過**，後者是**比過了且相同**。
         *
         * 實際踩到過：以 `--since=knolux-redis-spring-boot-starter/v1.3.0` 產報告時，
         * 該 tag 早於基準線檔存在的時點，兩個模組都因取不到基準線而跳過，
         * 報告標頭卻寫著「✅ 外溢依賴無變動」。那正是本功能要根除的失效模式——
         * 一份看起來乾淨的報告，實際上什麼都沒檢查。
         */
        @Test
        fun `全部模組都跳過時不得宣稱無變動`() {
            val allSkipped = ReportRenderer.renderChangeReport(
                GateReport(
                    comparisonBase = "`$REDIS/v1.3.0`（上一個發布版本）",
                    verdicts = listOf(
                        GateVerdict.skipped(REDIS, "取不到基準線"),
                        GateVerdict.skipped(S3, "取不到基準線"),
                    ),
                ),
            )

            assertFalse(allSkipped.contains("外溢依賴無變動"), "什麼都沒比過，不得宣稱無變動：\n$allSkipped")
            assertTrue(allSkipped.contains("## ⏭️ 已跳過的模組"), "實際內容為：\n$allSkipped")
        }

        /** 一個模組跳過、另一個確實無變動時，「無變動」只能講那個真的比過的模組。 */
        @Test
        fun `部分模組跳過時無變動的結論不得涵蓋未比對的模組`() {
            val partial = ReportRenderer.renderChangeReport(
                GateReport(
                    comparisonBase = "`$REDIS/v1.3.0`（上一個發布版本）",
                    verdicts = listOf(
                        GateVerdict.evaluate(REDIS, emptyList()),
                        GateVerdict.skipped(S3, "取不到基準線"),
                    ),
                ),
            )

            assertFalse(
                partial.contains("**結果**：✅ 外溢依賴無變動"),
                "有模組未比對時，不得下全域無變動的結論：\n$partial",
            )
            assertTrue(partial.contains("## ⏭️ 已跳過的模組"), "實際內容為：\n$partial")
        }

        private fun assertContains(expected: String) =
            assertTrue(markdown.contains(expected), "應含『$expected』，實際內容為：\n$markdown")
    }

    // ---------- fixtures ----------

    /**
     * 發版情境：一筆已核准的 major 跳動、一筆依賴移除、一筆 patch 前進，
     * 外加一個尚無發布 tag 的模組。
     */
    private fun releaseReport(): GateReport {
        val approval = Approval(
            module = REDIS,
            coordinate = DependencyCoordinate.parse("io.lettuce:lettuce-core"),
            from = "6.8.2.RELEASE",
            to = "7.5.2.RELEASE",
            kind = DeltaKind.MAJOR,
            reason = REASON,
        )
        val matcher = ApprovalMatcher(listOf(approval))
        val redis = GateVerdict.evaluate(
            REDIS,
            listOf(
                delta(REDIS, "io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                DependencyDelta(
                    REDIS,
                    DependencyCoordinate.parse("io.netty:netty-transport"),
                    "4.1.125.Final",
                    null,
                    DeltaKind.REMOVED,
                ),
                delta(REDIS, "ch.qos.logback:logback-classic", "1.5.32", "1.5.34", DeltaKind.PATCH),
            ),
            matcher,
        )

        return GateReport.of(
            "`$REDIS/v1.3.0`（上一個發布版本）",
            listOf(redis, GateVerdict.skipped(S3, "找不到 $S3 的發布 tag，視為首次發布")),
            matcher,
        )
    }

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

    /** 情形 B：唯一的阻擋性變更已核准，另有一筆配不到任何差異的過期核准。 */
    private fun approvedReport(): GateReport {
        val approval = Approval(
            module = REDIS,
            coordinate = DependencyCoordinate.parse("io.lettuce:lettuce-core"),
            from = "6.8.2.RELEASE",
            to = "7.5.2.RELEASE",
            kind = DeltaKind.MAJOR,
            reason = REASON,
        )
        val stale = Approval(
            module = REDIS,
            coordinate = DependencyCoordinate.parse("org.apache.commons:commons-lang3"),
            from = "3.19.0",
            to = "4.0.0",
            kind = DeltaKind.MAJOR,
            reason = "上一輪升級留下的核准",
        )
        val matcher = ApprovalMatcher(listOf(approval, stale))
        val redis = GateVerdict.evaluate(
            REDIS,
            listOf(delta(REDIS, "io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR)),
            matcher,
        )

        return GateReport.of("`origin/dev`（merge-base `a1b2c3d`）", listOf(redis), matcher)
    }

    private fun delta(module: String, raw: String, from: String, to: String, kind: DeltaKind) =
        DependencyDelta(module, DependencyCoordinate.parse(raw), from, to, kind)

    private companion object {
        const val REDIS = "knolux-redis-spring-boot-starter"
        const val S3 = "knolux-s3-spring-boot-starter"
        const val REASON = "隨 Spring Boot 4.1.0 升級而必然發生；已於 CHANGELOG 揭露"
    }
}
