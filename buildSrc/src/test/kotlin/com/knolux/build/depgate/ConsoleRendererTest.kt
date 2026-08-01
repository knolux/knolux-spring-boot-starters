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
        // research.md R2 機制 M1：簽入的基準線與當前解析不一致時，留給下一個人的比較對象
        // 就是一份不真實的檔案。訊息要能讓人直接照做，而不是先去讀文件。
        val stale = ConsoleRenderer.renderStaleBaseline(
            BaselineStaleness(
                REDIS,
                listOf(
                    DependencyDelta(REDIS, coordinate("io.lettuce:lettuce-core"), "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                    DependencyDelta(REDIS, coordinate("org.springframework:spring-messaging"), null, "7.0.8", DeltaKind.ADDED),
                ),
            ),
            baselinePath = "gradle/dependency-baseline/$REDIS.txt",
        )

        assertTrue(stale.contains("gradle/dependency-baseline/$REDIS.txt"), "實際輸出為：\n$stale")
        assertTrue(stale.contains("- io.lettuce:lettuce-core:6.8.2.RELEASE"), "應以 - 標出基準線側")
        assertTrue(stale.contains("+ io.lettuce:lettuce-core:7.5.2.RELEASE"), "應以 + 標出當前側")
        assertTrue(stale.contains("+ org.springframework:spring-messaging:7.0.8"), "新增項只有當前側")
        assertTrue(stale.contains("./gradlew updateDependencyBaseline"), "必須直接給出修正指令")
    }

    /**
     * 落差全屬非阻擋性時閘門只警告不失敗（FR-011），而警告最容易被無視。
     *
     * 因此這段訊息除了差異行與修正指令，還**必須**寫出兩件事：
     * 這次為何沒讓建置失敗，以及不補會在哪裡爆——否則讀者只會學到
     * 「這行黃字每週都出現，不用理」，等到發版前 `checkDependencyBaseline` 紅燈才回頭找原因。
     */
    @Test
    fun `落差非阻擋時說明為何不失敗與不補的後果`() {
        val tolerated = ConsoleRenderer.renderToleratedStaleBaseline(
            BaselineStaleness(
                S3,
                listOf(DependencyDelta(S3, coordinate("software.amazon.awssdk:s3"), "2.49.3", "2.49.4", DeltaKind.PATCH)),
            ),
            baselinePath = "gradle/dependency-baseline/$S3.txt",
        )

        assertTrue(tolerated.contains("- software.amazon.awssdk:s3:2.49.3"), "實際輸出為：\n$tolerated")
        assertTrue(tolerated.contains("+ software.amazon.awssdk:s3:2.49.4"), "實際輸出為：\n$tolerated")
        assertTrue(tolerated.contains("./gradlew updateDependencyBaseline"), "必須直接給出修正指令")
        assertTrue(tolerated.contains("checkDependencyBaseline"), "須點出不補的話會在發版前被擋下")
        assertFalse(tolerated.contains("已過期："), "非阻擋性落差不得沿用失敗語氣，實際輸出為：\n$tolerated")
    }

    @Test
    fun `清除過期核准時逐筆列出被刪的內容`() {
        // FR-017 的清除是**刪除**維護者手寫的內容。只印「已清除 2 筆」等於要求維護者
        // 事後翻 git diff 才知道被刪了什麼——而刪錯時最需要的正是當下就看得見。
        val purged = ConsoleRenderer.renderPurgedApprovals(
            removed = listOf(
                Approval(REDIS, coordinate("io.lettuce:lettuce-core"), "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR, "隨 Spring Boot 4.1.0 升級"),
                Approval(REDIS, coordinate("io.netty:netty-transport"), "4.1.125.Final", null, DeltaKind.REMOVED, "Netty 4.1.x 線移除"),
            ),
            approvalsPath = "gradle/dependency-approvals.toml",
        )

        assertTrue(purged.contains("2 筆"), "應標出筆數，實際輸出為：\n$purged")
        assertTrue(purged.contains("gradle/dependency-approvals.toml"), "應標出被改動的檔案")
        assertTrue(purged.contains("io.lettuce:lettuce-core"), "實際輸出為：\n$purged")
        assertTrue(purged.contains("6.8.2.RELEASE -> 7.5.2.RELEASE"), "實際輸出為：\n$purged")
        assertTrue(purged.contains("4.1.125.Final -> （已移除）"), "REMOVED 亦須可讀，實際輸出為：\n$purged")
        assertTrue(purged.contains("隨 Spring Boot 4.1.0 升級"), "理由須一併列出，否則刪錯了也認不出來")
    }

    private fun coordinate(raw: String) = DependencyCoordinate.parse(raw)

    private companion object {
        const val REDIS = "knolux-redis-spring-boot-starter"
        const val S3 = "knolux-s3-spring-boot-starter"
        const val REPORT_PATH = "build/reports/dependency-gate/gate-report.md"
    }
}
