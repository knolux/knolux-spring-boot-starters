package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 對應 spec FR-006 / FR-007 / FR-007a / FR-012。
 *
 * 這是整個閘門的判斷核心。它是純函式——不知道 git、不知道 Gradle、不做 I/O——
 * 因此可以把所有真實案例（包含 2026-08-01 那次事件）直接寫成測試表。
 */
class DeltaCalculatorTest {

    @Test
    fun `版本字串完全相同時不產生 delta`() {
        val deltas = calculate(
            baseline = mapOf("io.lettuce:lettuce-core" to "7.5.2.RELEASE"),
            current = mapOf("io.lettuce:lettuce-core" to "7.5.2.RELEASE"),
        )

        assertTrue(deltas.isEmpty(), "無變動不該產生噪音，實際為 $deltas")
    }

    @Test
    fun `major 段前進為 MAJOR 且完整保留前後版本`() {
        // 2026-08-01 的真實案例：Spring Boot 4.0.6 → 4.1.0 把 Lettuce 帶過 major。
        val delta = single(
            baseline = mapOf("io.lettuce:lettuce-core" to "6.8.2.RELEASE"),
            current = mapOf("io.lettuce:lettuce-core" to "7.5.2.RELEASE"),
        )

        assertEquals(DeltaKind.MAJOR, delta.kind)
        assertEquals("6.8.2.RELEASE", delta.from)
        assertEquals("7.5.2.RELEASE", delta.to)
    }

    @Test
    fun `跨越多個 major 仍為單一項 MAJOR`() {
        // 6.x → 8.x 不該被拆成兩筆，也不該因為「跳太多」而改判成別的類別。
        val delta = single(
            baseline = mapOf("org.example:lib" to "6.4.1"),
            current = mapOf("org.example:lib" to "8.0.0"),
        )

        assertEquals(DeltaKind.MAJOR, delta.kind)
        assertEquals("6.4.1", delta.from)
        assertEquals("8.0.0", delta.to)
    }

    @Test
    fun `零點版本線的 minor 前進視為 MAJOR`() {
        // FR-007a：SemVer 對 0.x 不保證相容，0.5 → 0.6 的破壞性與 1.x → 2.x 相同。
        val delta = single(
            baseline = mapOf("org.example:lib" to "0.5.0"),
            current = mapOf("org.example:lib" to "0.6.0"),
        )

        assertEquals(DeltaKind.MAJOR, delta.kind)
    }

    @Test
    fun `零點版本線的 patch 前進仍為 PATCH`() {
        val delta = single(
            baseline = mapOf("org.example:lib" to "0.5.0"),
            current = mapOf("org.example:lib" to "0.5.1"),
        )

        assertEquals(DeltaKind.PATCH, delta.kind)
    }

    @Test
    fun `minor 段前進為 MINOR`() {
        val delta = single(
            baseline = mapOf("org.springframework.data:spring-data-redis" to "4.0.6"),
            current = mapOf("org.springframework.data:spring-data-redis" to "4.1.0"),
        )

        assertEquals(DeltaKind.MINOR, delta.kind)
    }

    @Test
    fun `patch 段前進為 PATCH`() {
        val delta = single(
            baseline = mapOf("ch.qos.logback:logback-classic" to "1.5.33"),
            current = mapOf("ch.qos.logback:logback-classic" to "1.5.34"),
        )

        assertEquals(DeltaKind.PATCH, delta.kind)
    }

    @Test
    fun `僅 qualifier 變動為 PATCH`() {
        // qualifier 的排序規則各家 library 不一致，猜錯會產生假警報；
        // 數字完全相同就代表沒有跨越相容性邊界。
        val delta = single(
            baseline = mapOf("org.example:lib" to "1.0.0.RELEASE"),
            current = mapOf("org.example:lib" to "1.0.0.Final"),
        )

        assertEquals(DeltaKind.PATCH, delta.kind)
    }

    @Test
    fun `新增依賴為 ADDED 且 from 為 null`() {
        val delta = single(
            baseline = emptyMap(),
            current = mapOf("io.micrometer:micrometer-core" to "1.16.0"),
        )

        assertEquals(DeltaKind.ADDED, delta.kind)
        assertNull(delta.from)
        assertEquals("1.16.0", delta.to)
    }

    @Test
    fun `移除依賴為 REMOVED 且 to 為 null`() {
        // 2026-08-01 的真實案例：io.netty 的 4.1.x 線整個消失。
        val delta = single(
            baseline = mapOf("io.netty:netty-transport" to "4.1.125.Final"),
            current = emptyMap(),
        )

        assertEquals(DeltaKind.REMOVED, delta.kind)
        assertEquals("4.1.125.Final", delta.from)
        assertNull(delta.to)
    }

    @Test
    fun `版本後退為 DOWNGRADE 而非 MINOR`() {
        val delta = single(
            baseline = mapOf("org.example:lib" to "2.5.0"),
            current = mapOf("org.example:lib" to "2.4.0"),
        )

        assertEquals(DeltaKind.DOWNGRADE, delta.kind)
    }

    @Test
    fun `major 後退亦為 DOWNGRADE`() {
        val delta = single(
            baseline = mapOf("org.example:lib" to "3.0.0"),
            current = mapOf("org.example:lib" to "2.9.9"),
        )

        assertEquals(DeltaKind.DOWNGRADE, delta.kind)
    }

    @Test
    fun `當前版本無法解析為 UNPARSEABLE 且理由帶出原字串`() {
        val delta = single(
            baseline = mapOf("org.example:lib" to "1.0.0"),
            current = mapOf("org.example:lib" to "latest.release"),
        )

        assertEquals(DeltaKind.UNPARSEABLE, delta.kind)
        assertEquals("1.0.0", delta.from)
        assertEquals("latest.release", delta.to)
        assertTrue(
            delta.unparseableReason!!.contains("latest.release"),
            "理由應帶出原字串，實際為『${delta.unparseableReason}』",
        )
    }

    @Test
    fun `基準線版本無法解析同樣為 UNPARSEABLE`() {
        val delta = single(
            baseline = mapOf("org.example:lib" to "master-SNAPSHOT"),
            current = mapOf("org.example:lib" to "1.0.0"),
        )

        assertEquals(DeltaKind.UNPARSEABLE, delta.kind)
        assertTrue(delta.unparseableReason!!.contains("master-SNAPSHOT"))
    }

    // ---------- 整體行為 ----------

    @Test
    fun `一次算完全部差異不得遇到第一項就中止`() {
        // FR-012：只回報第一筆會讓維護者陷入「修一筆、重跑、又冒一筆」的迴圈，
        // 而 CI 一輪要數分鐘。
        val deltas = calculate(
            baseline = mapOf(
                "io.lettuce:lettuce-core" to "6.8.2.RELEASE",
                "io.netty:netty-transport" to "4.1.125.Final",
                "ch.qos.logback:logback-classic" to "1.5.33",
            ),
            current = mapOf(
                "io.lettuce:lettuce-core" to "7.5.2.RELEASE",
                "ch.qos.logback:logback-classic" to "1.5.34",
                "io.micrometer:micrometer-core" to "1.16.0",
            ),
        )

        assertEquals(
            listOf(
                DeltaKind.PATCH to "ch.qos.logback:logback-classic",
                DeltaKind.MAJOR to "io.lettuce:lettuce-core",
                DeltaKind.ADDED to "io.micrometer:micrometer-core",
                DeltaKind.REMOVED to "io.netty:netty-transport",
            ),
            deltas.map { it.kind to it.coordinate.toString() },
        )
    }

    @Test
    fun `輸出依座標排序以維持報告的決定性`() {
        val deltas = calculate(
            baseline = emptyMap(),
            current = mapOf(
                "org.zzz:lib" to "1.0.0",
                "org.aaa:lib" to "1.0.0",
                "org.mmm:lib" to "1.0.0",
            ),
        )

        assertEquals(
            listOf("org.aaa:lib", "org.mmm:lib", "org.zzz:lib"),
            deltas.map { it.coordinate.toString() },
        )
    }

    @Test
    fun `delta 帶有模組名`() {
        val delta = single(
            baseline = emptyMap(),
            current = mapOf("org.example:lib" to "1.0.0"),
        )

        assertEquals(MODULE, delta.moduleName)
    }

    @Test
    fun `比對不同模組的集合屬程式錯誤`() {
        val baseline = DependencySet("knolux-redis-spring-boot-starter", emptyMap())
        val current = DependencySet("knolux-s3-spring-boot-starter", emptyMap())

        assertThrows<IllegalArgumentException> { DeltaCalculator.calculate(baseline, current) }
    }

    // ---------- helpers ----------

    private fun calculate(
        baseline: Map<String, String>,
        current: Map<String, String>,
    ): List<DependencyDelta> = DeltaCalculator.calculate(depsOf(baseline), depsOf(current))

    private fun single(
        baseline: Map<String, String>,
        current: Map<String, String>,
    ): DependencyDelta = calculate(baseline, current).single()

    private fun depsOf(entries: Map<String, String>): DependencySet =
        DependencySet(MODULE, entries.mapKeys { (key, _) -> DependencyCoordinate.parse(key) })

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
    }
}
