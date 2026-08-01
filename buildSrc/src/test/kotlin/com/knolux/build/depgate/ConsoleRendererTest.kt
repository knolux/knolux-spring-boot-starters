package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 contracts/report-format.md §3。
 *
 * 檔案報告是給人讀的；console 輸出是給「在 CI log 裡快速掃過的人」讀的。
 * 失敗時 console **必須**含完整的阻擋清單，不得只寫「請見報告檔」——
 * CI 上點開 artifact 的成本高到讓人選擇忽略，而被忽略的閘門等於不存在的閘門。
 */
class ConsoleRendererTest {

    private val report = GateReport(
        comparisonBase = "`origin/dev`",
        verdicts = listOf(
            GateVerdict.evaluate(
                REDIS,
                listOf(
                    DependencyDelta(REDIS, coordinate("io.lettuce:lettuce-core"), "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                    DependencyDelta(REDIS, coordinate("io.netty:netty-transport"), "4.1.125.Final", null, DeltaKind.REMOVED),
                    DependencyDelta(REDIS, coordinate("ch.qos.logback:logback-classic"), "1.5.32", "1.5.34", DeltaKind.PATCH),
                ),
            ),
        ),
    )

    private val summary = ConsoleRenderer.renderBlockedSummary(report, REPORT_PATH)

    @Test
    fun `摘要行帶出判定與筆數`() {
        assertTrue(summary.contains("依賴相容性閘門：❌ 阻擋（2 項未核准的破壞性變更）"), "實際輸出為：\n$summary")
    }

    @Test
    fun `完整列出每一筆阻擋項的模組座標與版本`() {
        // FR-013：少了任何一項，維護者就得去翻報告檔——而多數人不會翻。
        assertTrue(summary.contains("[MAJOR]"), "應標出類別")
        assertTrue(summary.contains("[REMOVED]"))
        assertTrue(summary.contains(REDIS), "應標出模組")
        assertTrue(summary.contains("io.lettuce:lettuce-core"))
        assertTrue(summary.contains("6.8.2.RELEASE -> 7.5.2.RELEASE"))
        assertTrue(summary.contains("io.netty:netty-transport"))
        assertTrue(summary.contains("4.1.125.Final -> （已移除）"))
    }

    @Test
    fun `資訊性變動不出現在失敗摘要中`() {
        // 摘要的用途是「要修什麼」，混入不需行動的項目會稀釋訊號。
        assertFalse(summary.contains("logback"), "資訊性項目不應出現在阻擋摘要，實際輸出為：\n$summary")
    }

    @Test
    fun `附上報告路徑與核准途徑`() {
        assertTrue(summary.contains(REPORT_PATH))
        assertTrue(summary.contains("gradle/dependency-approvals.toml"))
    }

    @Test
    fun `類別標籤對齊使多筆項目易於掃視`() {
        val labelLines = summary.lines().filter { Regex("""^\s*\[[A-Z]+]""").containsMatchIn(it) }
        assertTrue(labelLines.size == 2, "應有兩筆阻擋項，實際為 ${labelLines.size}")
        assertTrue(
            labelLines.map { it.indexOf(REDIS) }.distinct().size == 1,
            "模組名應對齊於同一欄，實際輸出為：\n$summary",
        )
    }

    @Test
    fun `基準線過期時列出差異行與修正指令`() {
        // research.md R2 機制 M1：簽入的基準線與當前解析不一致時，閘門的比較對象本身就是錯的，
        // 因此必須先失敗。訊息要能讓人直接照做，而不是先去讀文件。
        val stale = ConsoleRenderer.renderStaleBaseline(
            moduleName = REDIS,
            deltas = listOf(
                DependencyDelta(REDIS, coordinate("io.lettuce:lettuce-core"), "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                DependencyDelta(REDIS, coordinate("org.springframework:spring-messaging"), null, "7.0.8", DeltaKind.ADDED),
            ),
            baselinePath = "gradle/dependency-baseline/$REDIS.txt",
        )

        assertTrue(stale.contains("gradle/dependency-baseline/$REDIS.txt"), "實際輸出為：\n$stale")
        assertTrue(stale.contains("- io.lettuce:lettuce-core:6.8.2.RELEASE"), "應以 - 標出基準線側")
        assertTrue(stale.contains("+ io.lettuce:lettuce-core:7.5.2.RELEASE"), "應以 + 標出當前側")
        assertTrue(stale.contains("+ org.springframework:spring-messaging:7.0.8"), "新增項只有當前側")
        assertTrue(stale.contains("./gradlew updateDependencyBaseline"), "必須直接給出修正指令")
    }

    private fun coordinate(raw: String) = DependencyCoordinate.parse(raw)

    private companion object {
        const val REDIS = "knolux-redis-spring-boot-starter"
        const val REPORT_PATH = "build/reports/dependency-gate/gate-report.md"
    }
}
