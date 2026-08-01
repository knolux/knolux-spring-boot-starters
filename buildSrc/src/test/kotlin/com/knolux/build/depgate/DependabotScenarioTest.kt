package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 spec User Story 3 與 FR-010 / FR-011：**Dependabot 的例行 PR 必須綠燈**。
 *
 * 這條看似是「順便」的需求，實際上決定閘門能不能活過三週。若每週幾支 bot PR 都紅燈，
 * 維護者的理性選擇是關掉閘門——屆時 US1 的攔截能力、US2 的揭露報告全部歸零。
 * 誤擋的代價不是「多按一次重跑」，是整個功能被停用。
 *
 * 刻意走 `DependencySet` → [DeltaCalculator] → [GateVerdict] 的完整鏈路，而非直接
 * 手捏 [DependencyDelta]：手捏 delta 等於自己先替分類器決定了 `kind`，那樣測到的
 * 只有 `GateVerdict` 的加總邏輯，真正會誤擋人的分類環節反而繞過了。
 */
class DependabotScenarioTest {

    @Test
    fun `僅 patch 前進的更新一律通過`() {
        // 最常見的 Dependabot PR 形態：安全性修補的 patch bump。
        val verdict = evaluate(
            baseline = mapOf(
                "org.springframework:spring-core" to "7.0.7",
                "ch.qos.logback:logback-classic" to "1.5.32",
            ),
            current = mapOf(
                "org.springframework:spring-core" to "7.0.8",
                "ch.qos.logback:logback-classic" to "1.5.34",
            ),
        )

        assertPassedWithInformationalOnly(verdict, expectedCount = 2)
    }

    @Test
    fun `僅 minor 前進的更新一律通過`() {
        val verdict = evaluate(
            baseline = mapOf(
                "org.springframework.boot:spring-boot" to "4.0.6",
                "org.yaml:snakeyaml" to "2.5",
            ),
            current = mapOf(
                "org.springframework.boot:spring-boot" to "4.1.0",
                "org.yaml:snakeyaml" to "2.6",
            ),
        )

        assertPassedWithInformationalOnly(verdict, expectedCount = 2)
    }

    /**
     * 新增依賴最容易被誤歸為阻擋性——直覺上「多了東西」聽起來也像破壞性變更。
     * 但 FR-011 明訂它不阻擋，理由是新增不會讓下游既有的程式碼編不過或跑不動；
     * 真正會壞的是「原本有的東西不見了」（`REMOVED`）與「同名但換了 major」。
     */
    @Test
    fun `僅新增依賴的更新一律通過`() {
        val verdict = evaluate(
            baseline = mapOf("org.springframework:spring-core" to "7.0.8"),
            current = mapOf(
                "org.springframework:spring-core" to "7.0.8",
                "org.springframework:spring-messaging" to "7.0.8",
            ),
        )

        assertPassedWithInformationalOnly(verdict, expectedCount = 1)
        assertEquals(DeltaKind.ADDED, verdict.deltas.single().kind)
    }

    /**
     * 一支 PR 同時含三類非阻擋變動仍須通過。
     *
     * 分開測會漏掉「單獨看每一類都不阻擋，湊在一起卻被某條加總規則擋下」的情形，
     * 而 Dependabot 的 grouped update 產出的正是這種混合變更集。
     */
    @Test
    fun `patch 與 minor 與新增混在同一支 PR 仍然通過`() {
        val verdict = evaluate(
            baseline = mapOf(
                "org.springframework:spring-core" to "7.0.7",
                "org.springframework.boot:spring-boot" to "4.0.6",
            ),
            current = mapOf(
                "org.springframework:spring-core" to "7.0.8",
                "org.springframework.boot:spring-boot" to "4.1.0",
                "org.springframework:spring-messaging" to "7.0.8",
            ),
        )

        assertPassedWithInformationalOnly(verdict, expectedCount = 3)
    }

    /**
     * 反向對照：同一條路徑遇到真正的破壞性變更時**必須**擋下。
     *
     * 少了這一則，把 `blocking` 全部改成 `false` 也能讓上面四則測試通過——
     * 那正是「閘門在但不作用」的失效方向，且從 CI 的綠燈上完全看不出來。
     */
    @Test
    fun `同一條路徑遇到 major 跳動仍會擋下`() {
        val verdict = evaluate(
            baseline = mapOf("io.lettuce:lettuce-core" to "6.8.2.RELEASE"),
            current = mapOf("io.lettuce:lettuce-core" to "7.5.2.RELEASE"),
        )

        assertEquals(GateStatus.BLOCKED, verdict.status)
        assertEquals(DeltaKind.MAJOR, verdict.blockedDeltas.single().kind)
    }

    private fun evaluate(baseline: Map<String, String>, current: Map<String, String>): GateVerdict =
        GateVerdict.evaluate(MODULE, DeltaCalculator.calculate(dependencySet(baseline), dependencySet(current)))

    private fun dependencySet(entries: Map<String, String>) = DependencySet(
        MODULE,
        entries.mapKeys { (coordinate, _) -> DependencyCoordinate.parse(coordinate) },
    )

    private fun assertPassedWithInformationalOnly(verdict: GateVerdict, expectedCount: Int) {
        assertEquals(GateStatus.PASSED, verdict.status, "Dependabot 型變更集不得被擋下")
        assertTrue(verdict.blockedDeltas.isEmpty(), "實際被擋下：${verdict.blockedDeltas}")
        assertEquals(expectedCount, verdict.deltas.size, "變動仍須完整列入報告，實際為 ${verdict.deltas}")
        assertTrue(verdict.deltas.none { it.blocking }, "實際含阻擋性變動：${verdict.deltas.filter { it.blocking }}")
    }

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
    }
}
