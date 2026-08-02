package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 spec FR-004（發版報告的比較基準＝上一個已發布 tag）。
 *
 * 本測試的核心是 spec 明列的 edge case：**tag 排序不可用字典序**。
 * `knolux-redis-spring-boot-starter/v1.10.0` 在字典序下排在 `v1.9.0` **之前**，
 * 若採字典序，發版報告會拿一個更舊的版本當基準，於是「這次新增了什麼破壞性變更」
 * 的答案會多出一堆早已揭露過的項目——報告一旦開始講廢話，就沒有人會再讀它。
 */
class ReleaseTagSelectorTest {

    private val redis = "knolux-redis-spring-boot-starter"
    private val s3 = "knolux-s3-spring-boot-starter"

    // ---------- 排序：語意化版本，而非字典序 ----------

    @Test
    fun `v1_10_0 勝過 v1_9_0——字典序會給出相反答案`() {
        val found = foundOf(redis, listOf("$redis/v1.9.0", "$redis/v1.10.0"))

        assertEquals("$redis/v1.10.0", found.tag)
    }

    @Test
    fun `挑選結果與輸入順序無關`() {
        val tags = listOf("$redis/v1.10.0", "$redis/v1.9.0", "$redis/v1.2.0")

        assertEquals("$redis/v1.10.0", foundOf(redis, tags).tag)
        assertEquals("$redis/v1.10.0", foundOf(redis, tags.reversed()).tag)
    }

    @Test
    fun `major 優先於 minor 與 patch`() {
        val found = foundOf(redis, listOf("$redis/v1.99.99", "$redis/v2.0.0"))

        assertEquals("$redis/v2.0.0", found.tag)
    }

    /**
     * 數字段相同時必須有明確的勝負規則，否則結果取決於 `git tag` 的輸出順序，
     * 同一份 repo 在不同機器上會產出不同的報告基準。
     */
    @Test
    fun `數字段相同時正式版勝過帶 qualifier 的版本，且與輸入順序無關`() {
        val tags = listOf("$redis/v1.4.0-rc1", "$redis/v1.4.0")

        assertEquals("$redis/v1.4.0", foundOf(redis, tags).tag)
        assertEquals("$redis/v1.4.0", foundOf(redis, tags.reversed()).tag)
    }

    // ---------- 過濾：只看自己模組的 tag ----------

    @Test
    fun `排除其他模組的 tag`() {
        val found = foundOf(redis, listOf("$redis/v1.3.0", "$s3/v9.9.9"))

        assertEquals("$redis/v1.3.0", found.tag)
    }

    /**
     * 前綴比對必須含 `/`：只比 `startsWith(moduleName)` 的話，
     * 日後新增一個名稱以現有模組為前綴的模組（例如 `-reactive` 變體），
     * 它的 tag 會悄悄變成別人的比較基準。
     */
    @Test
    fun `名稱以現有模組為前綴的其他模組 tag 不得誤配`() {
        val found = foundOf(redis, listOf("$redis/v1.3.0", "$redis-reactive/v9.9.9"))

        assertEquals("$redis/v1.3.0", found.tag)
    }

    @Test
    fun `非 v 開頭的 tag 不納入`() {
        val found = foundOf(redis, listOf("$redis/v1.3.0", "$redis/2.0.0", "$redis/nightly"))

        assertEquals("$redis/v1.3.0", found.tag)
    }

    // ---------- 無基準線：必須帶出理由 ----------

    @Test
    fun `完全沒有 tag 時回報無基準線且理由帶出模組名`() {
        val missing = missingOf(redis, emptyList())

        assertTrue(missing.reason.contains(redis), "理由需帶出模組名，實際：${missing.reason}")
    }

    @Test
    fun `只有其他模組的 tag 時仍為無基準線`() {
        val missing = missingOf(redis, listOf("$s3/v1.3.0", "$s3/v1.2.0"))

        assertTrue(missing.reason.contains(redis), "理由需帶出模組名，實際：${missing.reason}")
    }

    // ---------- 無法解析的 tag：排除但不得靜默 ----------

    @Test
    fun `版本號無法解析的 tag 被排除，且列入 ignoredTags`() {
        val found = foundOf(redis, listOf("$redis/v1.3.0", "$redis/vNEXT"))

        assertEquals("$redis/v1.3.0", found.tag)
        assertEquals(listOf("$redis/vNEXT"), found.ignoredTags)
    }

    /**
     * 全部都解析不了時，若只回一句「沒有 tag」，維護者會以為這個模組從未發布過，
     * 而真正的問題是 tag 命名壞了。理由與 [ReleaseTagLookup.ignoredTags] 都必須說得出來。
     */
    @Test
    fun `所有 tag 都無法解析時回報無基準線並列出被略過者`() {
        val missing = missingOf(redis, listOf("$redis/vNEXT", "$redis/vlatest"))

        assertEquals(listOf("$redis/vNEXT", "$redis/vlatest"), missing.ignoredTags)
    }

    // ---------- 回傳值可直接餵給 git ----------

    @Test
    fun `回傳的 tag 為原始完整名稱`() {
        val found = foundOf(redis, listOf("$redis/v1.4.0"))

        assertEquals("$redis/v1.4.0", found.tag, "MUST 為完整 tag：呼叫端要拿它去跑 git show")
        assertEquals("1.4.0", found.version.raw, "版本部分去掉 v 前綴")
    }

    /**
     * 報告標頭的描述由 [ReleaseTagLookup.Found] 自己給出（contracts/report-format.md §2）。
     *
     * 放在這裡而非任務類別：「比較基準叫什麼名字」是判斷，憲章 III 要求
     * composition root 只做組裝。留在任務裡的話，它就再也測不到了。
     */
    @Test
    fun `描述文字標明這是上一個發布版本`() {
        val found = foundOf(redis, listOf("$redis/v1.4.0"))

        assertEquals("`$redis/v1.4.0`（上一個發布版本）", found.describe)
    }

    /** 以本 repo 2026-08-01 當下的真實 tag 清單驗證，避免測試只在人造資料上成立。 */
    @Test
    fun `以本 repo 的真實 tag 清單挑出各模組最新版`() {
        val tags = listOf(
            "$redis/v1.0.0", "$redis/v1.0.1", "$redis/v1.0.2", "$redis/v1.1.0", "$redis/v1.1.1",
            "$redis/v1.2.0", "$redis/v1.2.1", "$redis/v1.2.2", "$redis/v1.3.0", "$redis/v1.4.0",
            "$s3/v1.0.0", "$s3/v1.0.1", "$s3/v1.1.0", "$s3/v1.1.1", "$s3/v1.1.2",
            "$s3/v1.2.0", "$s3/v1.3.0",
        )

        assertEquals("$redis/v1.4.0", foundOf(redis, tags).tag)
        assertEquals("$s3/v1.3.0", foundOf(s3, tags).tag)
    }

    private fun foundOf(moduleName: String, tags: List<String>): ReleaseTagLookup.Found {
        val lookup = ReleaseTagSelector.select(moduleName, tags)
        assertTrue(lookup is ReleaseTagLookup.Found, "應回傳 Found，實際為 $lookup")
        return lookup as ReleaseTagLookup.Found
    }

    private fun missingOf(moduleName: String, tags: List<String>): ReleaseTagLookup.Missing {
        val lookup = ReleaseTagSelector.select(moduleName, tags)
        assertTrue(lookup is ReleaseTagLookup.Missing, "應回傳 Missing，實際為 $lookup")
        return lookup as ReleaseTagLookup.Missing
    }
}
