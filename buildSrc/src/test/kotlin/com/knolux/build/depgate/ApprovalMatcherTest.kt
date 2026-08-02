package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 data-model.md §6 與 spec FR-015 ~ FR-017。
 *
 * 這裡的每一則「改動任一欄位就不再放行」都是 FR-015「不得是全域開關」的實質保障：
 * 核准的是「這一次、這個依賴、從這一版到那一版」這個具體事實，不是一類事實。
 * 比對只要放寬一格（例如允許萬用字元、或忽略 `to`），核准就從「一次性豁免」
 * 退化成「長期關閉」，而閘門的價值也隨之歸零。
 */
class ApprovalMatcherTest {

    @Test
    fun `五個欄位全部逐字相符才算已核准`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval()))

        assertEquals(lettuceApproval(), matcher.find(lettuceDelta()))
    }

    @Test
    fun `模組不同即不放行`() {
        // 同一個依賴在兩個 starter 的升級風險並不相同，核准不得跨模組共用。
        val matcher = ApprovalMatcher(listOf(lettuceApproval(module = "knolux-s3-spring-boot-starter")))

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `座標不同即不放行`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval(coordinate = "io.lettuce:lettuce-core-jdk8")))

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `來源版本不同即不放行`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval(from = "6.8.1.RELEASE")))

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `目標版本不同即不放行`() {
        // 最關鍵的一則：核准 6.8.2 → 7.5.2 之後，BOM 又把它帶到 8.0.0 時必須重新審視。
        val matcher = ApprovalMatcher(listOf(lettuceApproval(to = "7.5.3.RELEASE")))

        assertNull(matcher.find(lettuceDelta(to = "8.0.0.RELEASE")))
    }

    @Test
    fun `類別不同即不放行`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval(kind = DeltaKind.DOWNGRADE)))

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `萬用字元不具特殊意義`() {
        // 純字面比對：`*` 只是一個不會等於任何真實版本的字串。
        val matcher = ApprovalMatcher(
            listOf(
                lettuceApproval(to = "*"),
                lettuceApproval(coordinate = "io.lettuce:*", to = "*"),
            ),
        )

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `版本區間不具特殊意義`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval(to = "[7.0,8.0)")))

        assertNull(matcher.find(lettuceDelta()))
    }

    @Test
    fun `移除類別以 to 皆為 null 相符`() {
        val approval = Approval(
            module = MODULE,
            coordinate = DependencyCoordinate.parse(NETTY),
            from = "4.1.125.Final",
            to = null,
            kind = DeltaKind.REMOVED,
            reason = "Netty 4.1.x 整線隨 Spring Boot 4.1.0 移除",
        )
        val matcher = ApprovalMatcher(listOf(approval))

        assertEquals(approval, matcher.find(nettyRemoval()))
    }

    @Test
    fun `多筆核准中挑出對應的那一筆`() {
        val lettuce = lettuceApproval()
        val matcher = ApprovalMatcher(listOf(lettuceApproval(coordinate = "a.a:other"), lettuce))

        assertEquals(lettuce, matcher.find(lettuceDelta()))
    }

    @Test
    fun `無核准時回傳 null`() {
        assertNull(ApprovalMatcher(emptyList()).find(lettuceDelta()))
    }

    @Test
    fun `未配對到任何差異的核准為過期`() {
        // FR-017 的偵測面：基準線推進後，舊核准會永遠配不到東西，
        // 留著只會讓核准檔越積越長，最終沒有人敢動——也就沒有人會再審視它。
        val used = lettuceApproval()
        val unused = lettuceApproval(coordinate = "org.apache.commons:commons-lang3", from = "3.19.0", to = "4.0.0")
        val matcher = ApprovalMatcher(listOf(used, unused))

        assertEquals(listOf(unused), matcher.staleApprovals(listOf(lettuceDelta()), setOf(MODULE)))
    }

    @Test
    fun `全部核准都配對到時無過期項`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval()))

        assertTrue(matcher.staleApprovals(listOf(lettuceDelta()), setOf(MODULE)).isEmpty())
    }

    @Test
    fun `未被評估的模組其核准不算過期`() {
        // 模組因無基準線而被跳過時，本輪根本沒算出任何 delta；此時斷言它的核准
        // 「沒配對到東西」只是同義反覆。照這個結果清除核准，等基準線補上後，
        // 那筆本該被擋下的破壞性變更就會無聲通過。
        val matcher = ApprovalMatcher(listOf(lettuceApproval()))

        assertTrue(matcher.staleApprovals(emptyList(), evaluatedModules = emptySet()).isEmpty())
    }

    @Test
    fun `過期判定跨模組一次算完`() {
        // 一次執行涵蓋全部模組，故必須把所有模組的差異一起餵進來；
        // 只用單一模組的差異判定，會把另一個模組正在使用的核准誤判為過期。
        val redis = lettuceApproval()
        val s3 = lettuceApproval(
            module = S3_MODULE,
            coordinate = NETTY,
            from = "4.1.125.Final",
            to = "4.2.12.Final",
        )
        val nettyUpgrade = DependencyDelta(
            moduleName = S3_MODULE,
            coordinate = DependencyCoordinate.parse(NETTY),
            from = "4.1.125.Final",
            to = "4.2.12.Final",
            kind = DeltaKind.MAJOR,
        )
        val matcher = ApprovalMatcher(listOf(redis, s3))

        val bothModules = setOf(MODULE, S3_MODULE)

        assertTrue(matcher.staleApprovals(listOf(lettuceDelta(), nettyUpgrade), bothModules).isEmpty())
        // 反面：少餵一個模組的差異，該模組正在使用的核准就會被誤判為過期。
        assertEquals(listOf(s3), matcher.staleApprovals(listOf(lettuceDelta()), bothModules))
    }

    @Test
    fun `模組有被評估但零差異時其核准為過期`() {
        val matcher = ApprovalMatcher(listOf(lettuceApproval()))

        assertEquals(1, matcher.staleApprovals(emptyList(), setOf(MODULE)).size)
    }

    // ---------- fixtures ----------

    private fun lettuceApproval(
        module: String = MODULE,
        coordinate: String = LETTUCE,
        from: String = "6.8.2.RELEASE",
        to: String? = "7.5.2.RELEASE",
        kind: DeltaKind = DeltaKind.MAJOR,
    ) = Approval(
        module = module,
        coordinate = DependencyCoordinate.parse(coordinate),
        from = from,
        to = to,
        kind = kind,
        reason = "隨 Spring Boot 4.1.0 升級而必然發生，已於 CHANGELOG 揭露",
    )

    private fun lettuceDelta(to: String = "7.5.2.RELEASE") = DependencyDelta(
        moduleName = MODULE,
        coordinate = DependencyCoordinate.parse(LETTUCE),
        from = "6.8.2.RELEASE",
        to = to,
        kind = DeltaKind.MAJOR,
    )

    private fun nettyRemoval() = DependencyDelta(
        moduleName = MODULE,
        coordinate = DependencyCoordinate.parse(NETTY),
        from = "4.1.125.Final",
        to = null,
        kind = DeltaKind.REMOVED,
    )

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
        const val S3_MODULE = "knolux-s3-spring-boot-starter"
        const val LETTUCE = "io.lettuce:lettuce-core"
        const val NETTY = "io.netty:netty-transport"
    }
}
