package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 對應 data-model.md §4：把「解析出來的一堆元件」收斂成「會外溢給下游的依賴集合」。
 *
 * 走訪 Gradle 的 `ResolvedComponentResult` 圖是 adapter 的事（[DependencyGraphReader]），
 * **哪些該收進來**則是領域決策，因此獨立在此測試——這一層決定了基準線檔的內容，
 * 而基準線一旦收錯東西，往後每次比對都是錯的。
 */
class DependencySetBuilderTest {

    @Test
    fun `排除同 repo 的模組只留下外部依賴`() {
        // 兄弟模組會與本模組一起發版，不是「外溢給下游的第三方依賴」；
        // 收進來只會讓基準線隨自家版號跳動而整檔變更。
        val set = build(
            external("io.lettuce", "lettuce-core", "6.8.2.RELEASE"),
            project("com.knolux", "knolux-redis-spring-boot-starter", "0.0.0-SNAPSHOT"),
        )

        assertEquals(
            mapOf(DependencyCoordinate("io.lettuce", "lettuce-core") to "6.8.2.RELEASE"),
            set.entries,
        )
    }

    @Test
    fun `依座標排序使輸出具決定性`() {
        // 基準線檔會簽入版控，順序浮動會產生整檔 diff，人便會開始無視它。
        val set = build(
            external("org.springframework", "spring-core", "7.0.8"),
            external("io.lettuce", "lettuce-core", "6.8.2.RELEASE"),
            external("io.netty", "netty-common", "4.2.12.Final"),
        )

        assertEquals(
            listOf("io.lettuce:lettuce-core", "io.netty:netty-common", "org.springframework:spring-core"),
            set.entries.keys.map { it.toString() },
        )
    }

    @Test
    fun `同座標重複出現且版本一致時收斂為一筆`() {
        // 依賴圖是 DAG，同一個元件可由多條路徑抵達。
        val set = build(
            external("io.netty", "netty-common", "4.2.12.Final"),
            external("io.netty", "netty-common", "4.2.12.Final"),
        )

        assertEquals(1, set.entries.size)
    }

    @Test
    fun `同座標出現不同版本時 fail-fast`() {
        // Gradle 衝突解析後同一座標必為單一版本；若不然，代表我們對解析結果的假設是錯的。
        // 靜默取其中一個會讓基準線在兩次執行間跳動，且沒有人會發現。
        val error = runCatching {
            build(
                external("io.netty", "netty-common", "4.2.12.Final"),
                external("io.netty", "netty-common", "4.1.125.Final"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "實際為 $error")
        assertTrue(error!!.message!!.contains("io.netty:netty-common"))
        assertTrue(error.message!!.contains("4.2.12.Final"), "訊息須帶出兩個版本，實際為『${error.message}』")
        assertTrue(error.message!!.contains("4.1.125.Final"))
    }

    @Test
    fun `版本為空時 fail-fast 而非寫入空版本`() {
        // 一筆 `group:artifact:` 會讓 DependencySet.parse 在下次讀取時炸掉，
        // 屆時錯誤現場離成因已經很遠——在產生的當下就擋住。
        val error = runCatching { build(external("io.lettuce", "lettuce-core", " ")) }.exceptionOrNull()

        assertTrue(error is IllegalStateException, "實際為 $error")
        assertTrue(error!!.message!!.contains("io.lettuce:lettuce-core"), "實際為『${error.message}』")
    }

    @Test
    fun `輸出可被 parse 還原形成往返不變式`() {
        // serialize 與 parse 互為反函數是基準線機制的根本前提。
        val set = build(
            external("io.lettuce", "lettuce-core", "6.8.2.RELEASE"),
            external("io.netty", "netty-common", "4.2.12.Final"),
        )

        assertEquals(set, DependencySet.parse(MODULE, set.serialize()))
    }

    @Test
    fun `沒有任何外部依賴時回傳空集合而非拋例外`() {
        // 空集合本身是合法狀態；能不能發版是別人的判斷，不是這裡的。
        assertEquals(emptyMap<DependencyCoordinate, String>(), build().entries)
    }

    private fun build(vararg entries: ResolvedEntry) = DependencySetBuilder.build(MODULE, entries.toList())

    private fun external(group: String, artifact: String, version: String) =
        ResolvedEntry(group, artifact, version, isProject = false)

    private fun project(group: String, artifact: String, version: String) =
        ResolvedEntry(group, artifact, version, isProject = true)

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
    }
}
