package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SC-001 回歸測試——**本功能存在的理由**。
 *
 * 2026-08-01 發布的 redis 1.4.0，自有原始碼零變更（`v1.3.0..main` 的 `.java` diff 為空），
 * 卻因 Spring Boot 4.0.6 → 4.1.0 把 `io.lettuce:lettuce-core` 從 6.8.2.RELEASE 帶到
 * 7.5.2.RELEASE（跨 major）。此事直到發布後才由人工比對發現，版號已無法回收。
 *
 * 兩份 fixture **都是簽入的**，不在測試中即時解析。若當前側改為執行期解析，
 * 日後任何一次依賴升級都會讓兩側同步前進，這個測試將靜默地不再驗證任何事——
 * SC-001 的回歸保護會在沒有人察覺的情況下消失。
 *
 * fixture 取得方式（tasks.md T023）：於 `git worktree add` 出的
 * `knolux-redis-spring-boot-starter/v1.3.0` 工作區與當前 HEAD 分別匯出 `runtimeClasspath`
 * 的解析結果。取 `runtimeClasspath` 是因為它與 `maven-publish` 的 `versionMapping` 同源，
 * 也就是實際寫進 POM、下游真正會拿到的那一組版本。
 */
class Regression20260801Test {

    private val deltas: List<DependencyDelta> = DeltaCalculator.calculate(
        baseline = load("redis-v1.3.0-baseline.txt"),
        current = load("redis-current-baseline.txt"),
    )

    @Test
    fun `Lettuce 的跨 major 升級必須被判為 MAJOR`() {
        val lettuce = deltas.single { it.coordinate.toString() == "io.lettuce:lettuce-core" }

        assertEquals(DeltaKind.MAJOR, lettuce.kind)
        assertEquals("6.8.2.RELEASE", lettuce.from)
        assertEquals("7.5.2.RELEASE", lettuce.to)
        assertTrue(lettuce.blocking, "跨 major 必須阻擋建置，否則整個閘門形同虛設")
    }

    @Test
    fun `阻擋清單恰為 Lettuce 一項`() {
        // 只斷言「Lettuce 有被抓到」是不夠的——當時人工比對第一遍就是漏看了第二項。
        // 這裡改為斷言**完整集合**：日後任何新增或消失的阻擋項都會讓測試失敗，
        // 迫使開發者回頭確認那是刻意的變更還是又一次漏看。
        assertEquals(
            listOf("io.lettuce:lettuce-core"),
            deltas.filter { it.blocking }.map { it.coordinate.toString() },
        )
    }

    @Test
    fun `redis 兩側都不存在 Netty 4-1-x 因此沒有依賴移除`() {
        // CHANGELOG.md 的 redis 1.4.0 段落記載「`io.netty:*` 4.1.125 與 4.2.12 並存 →
        // 統一為 4.2.x（4.1.x 已移除）」。以 runtimeClasspath 實測，redis 模組在 v1.3.0
        // 當下的 Netty 已全為 4.2.12.Final，並不存在 4.1.x，因此也就沒有「移除」這回事。
        //
        // 該筆敘述應是來自 `dependencies` 任務的文字輸出（research.md R1 已實證它會把請求版本
        // 與解析版本混在一起），或與確實同時帶有兩條 Netty 線的 s3 模組混淆。這正是本功能的
        // 論據：人工比對依賴樹會出錯，而錯誤會一路寫進 Release notes。
        val nettyBaseline = load("redis-v1.3.0-baseline.txt").entries
            .filterKeys { it.group == "io.netty" }

        assertTrue(nettyBaseline.isNotEmpty(), "fixture 應含 Netty，否則此斷言沒有意義")
        assertTrue(
            nettyBaseline.values.all { it.startsWith("4.2.") },
            "v1.3.0 的 Netty 應全為 4.2.x，實際為 $nettyBaseline",
        )
        assertTrue(
            deltas.none { it.kind == DeltaKind.REMOVED },
            "實測無任何依賴移除，實際為 ${deltas.filter { it.kind == DeltaKind.REMOVED }}",
        )
    }

    @Test
    fun `新增的 spring-messaging 列為資訊性而非阻擋`() {
        val added = deltas.single { it.kind == DeltaKind.ADDED }

        assertEquals("org.springframework:spring-messaging", added.coordinate.toString())
        assertEquals("7.0.8", added.to)
        assertTrue(!added.blocking, "新增依賴不破壞既有下游程式碼，不應阻擋")
    }

    @Test
    fun `一次算完全部差異而非遇到第一項就停`() {
        // FR-012：這一次升級共動了二十餘個座標，逐項回報會讓維護者來回重跑 CI 十幾輪。
        assertTrue(deltas.size > 20, "應一次算出全部差異，實際為 ${deltas.size} 筆")
        assertTrue(
            deltas.any { it.kind == DeltaKind.MINOR } && deltas.any { it.kind == DeltaKind.PATCH },
            "同一批差異中應同時含 minor 與 patch，實際類別為 ${deltas.map { it.kind }.toSet()}",
        )
    }

    private fun load(fixture: String): DependencySet {
        val content = checkNotNull(javaClass.getResourceAsStream("/fixtures/$fixture")) {
            "找不到 fixture /fixtures/$fixture"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

        return DependencySet.parse(MODULE, content)
    }

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
    }
}
