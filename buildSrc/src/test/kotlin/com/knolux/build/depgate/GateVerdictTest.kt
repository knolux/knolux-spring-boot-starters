package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 data-model.md §7 / §8 與 spec FR-005 / FR-012。
 *
 * 狀態推導集中在 [GateVerdict.evaluate] 一處，不散落到任務類別裡。任務只該負責
 * 組裝與 I/O（憲章 III：composition root 不放判斷邏輯）；一旦「什麼情況算失敗」
 * 被寫進 `@TaskAction`，它就再也無法被單元測試涵蓋。
 */
class GateVerdictTest {

    @Test
    fun `無基準線時為跳過且不失敗`() {
        // FR-005：新模組首次加入時本來就沒有基準線。
        val verdict = GateVerdict.skipped(MODULE, "基準線檔不存在")

        assertEquals(GateStatus.SKIPPED_NO_BASELINE, verdict.status)
        assertTrue(verdict.deltas.isEmpty())
        assertTrue(verdict.blockedDeltas.isEmpty())
    }

    @Test
    fun `跳過時必須留下可讀的原因`() {
        // 靜默跳過等同「這個模組沒被保護，而沒有人知道」。
        val verdict = GateVerdict.skipped(MODULE, "基準線檔 xxx.txt 在比較基準上不存在")

        assertTrue(verdict.skipReason!!.isNotBlank())
    }

    @Test
    fun `無差異時為通過`() {
        val verdict = GateVerdict.evaluate(MODULE, emptyList())

        assertEquals(GateStatus.PASSED, verdict.status)
        assertNull(verdict.skipReason)
    }

    @Test
    fun `僅有資訊性差異時為通過`() {
        // 擋住每週的 Dependabot PR 只會讓人關掉閘門。
        val verdict = GateVerdict.evaluate(
            MODULE,
            listOf(
                delta("ch.qos.logback:logback-classic", "1.5.33", "1.5.34", DeltaKind.PATCH),
                delta("org.springframework.data:spring-data-redis", "4.0.6", "4.1.0", DeltaKind.MINOR),
                DependencyDelta(MODULE, coordinate("io.micrometer:micrometer-core"), null, "1.16.0", DeltaKind.ADDED),
            ),
        )

        assertEquals(GateStatus.PASSED, verdict.status)
        assertEquals(3, verdict.deltas.size)
        assertTrue(verdict.blockedDeltas.isEmpty())
    }

    @Test
    fun `存在未核准的阻擋性差異時為阻擋`() {
        val verdict = GateVerdict.evaluate(
            MODULE,
            listOf(delta("io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR)),
        )

        assertEquals(GateStatus.BLOCKED, verdict.status)
        assertEquals(1, verdict.blockedDeltas.size)
    }

    @Test
    fun `阻擋清單只含阻擋性項目但 deltas 保留全部`() {
        // FR-012／FR-020：報告要同時呈現兩者，但只有阻擋性的會影響成敗。
        val verdict = GateVerdict.evaluate(
            MODULE,
            listOf(
                delta("ch.qos.logback:logback-classic", "1.5.33", "1.5.34", DeltaKind.PATCH),
                delta("io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR),
                DependencyDelta(MODULE, coordinate("io.netty:netty-transport"), "4.1.125.Final", null, DeltaKind.REMOVED),
            ),
        )

        assertEquals(3, verdict.deltas.size)
        assertEquals(
            listOf("io.lettuce:lettuce-core", "io.netty:netty-transport"),
            verdict.blockedDeltas.map { it.coordinate.toString() },
        )
    }

    @Test
    fun `多筆阻擋性差異一次全部列出`() {
        // FR-012：只回報第一筆會讓維護者陷入「修一筆、重跑 CI、又冒一筆」的迴圈。
        val verdict = GateVerdict.evaluate(
            MODULE,
            listOf(
                delta("a.a:one", "1.0.0", "2.0.0", DeltaKind.MAJOR),
                delta("b.b:two", "1.0.0", "2.0.0", DeltaKind.MAJOR),
                delta("c.c:three", "2.0.0", "1.0.0", DeltaKind.DOWNGRADE),
            ),
        )

        assertEquals(3, verdict.blockedDeltas.size)
    }

    // ---------- GateReport ----------

    @Test
    fun `任一模組被阻擋則整體報告為阻擋`() {
        val report = GateReport(
            comparisonBase = "origin/dev",
            verdicts = listOf(
                GateVerdict.evaluate("knolux-s3-spring-boot-starter", emptyList()),
                GateVerdict.evaluate(
                    MODULE,
                    listOf(delta("io.lettuce:lettuce-core", "6.8.2.RELEASE", "7.5.2.RELEASE", DeltaKind.MAJOR)),
                ),
            ),
        )

        assertTrue(report.blocked)
        assertEquals(1, report.blockedDeltas.size)
    }

    @Test
    fun `全部模組通過或跳過時報告不阻擋`() {
        val report = GateReport(
            comparisonBase = "origin/dev",
            verdicts = listOf(
                GateVerdict.evaluate("knolux-s3-spring-boot-starter", emptyList()),
                GateVerdict.skipped(MODULE, "基準線檔不存在"),
            ),
        )

        assertTrue(!report.blocked)
    }

    // ---------- helpers ----------

    private fun coordinate(raw: String) = DependencyCoordinate.parse(raw)

    private fun delta(raw: String, from: String, to: String, kind: DeltaKind) =
        DependencyDelta(MODULE, coordinate(raw), from, to, kind)

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
    }
}
