package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 對應 data-model.md §3 與 contracts/file-formats.md §1。
 *
 * 這組測試守的是「決定性輸出」。R2 選擇簽入基準線檔的核心理由，是讓基準線變動
 * 直接出現在 PR diff 供人審閱；只要序列化順序或換行不穩定，每次都會產生整檔
 * diff，人就會開始無視它——那條決策的價值會整個歸零。
 */
class DependencySetTest {

    private val module = "knolux-redis-spring-boot-starter"

    @Test
    fun `輸出依完整行字串字典序排序且與插入順序無關`() {
        val shuffled = DependencySet(
            module,
            mapOf(
                DependencyCoordinate("org.springframework.data", "spring-data-redis") to "4.1.0",
                DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE",
                DependencyCoordinate("ch.qos.logback", "logback-classic") to "1.5.34",
                DependencyCoordinate("io.netty", "netty-buffer") to "4.2.15.Final",
            ),
        )

        assertEquals(
            listOf(
                "ch.qos.logback:logback-classic:1.5.34",
                "io.lettuce:lettuce-core:7.5.2.RELEASE",
                "io.netty:netty-buffer:4.2.15.Final",
                "org.springframework.data:spring-data-redis:4.1.0",
            ),
            shuffled.serialize().lines().filterNot { it.startsWith("#") || it.isBlank() },
        )
    }

    @Test
    fun `輸出以 LF 換行且檔尾恰為單一換行`() {
        // 本 repo 於 Windows 開發、CI 於 Linux。若讓平台決定換行，
        // 基準線檔會在每次跨平台更新時整檔 diff（見 T029 的 .gitattributes）。
        val rendered = DependencySet(
            module,
            mapOf(DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE"),
        ).serialize()

        assertFalse(rendered.contains('\r'), "不得出現 CR")
        assertTrue(rendered.endsWith("\n"), "檔尾應有換行")
        assertFalse(rendered.endsWith("\n\n"), "檔尾不得有多餘空行")
    }

    @Test
    fun `輸出帶有請勿手動編輯的標頭註解`() {
        val rendered = DependencySet(module, emptyMap()).serialize()

        assertTrue(rendered.lines().first().startsWith("#"), "首行應為註解")
        assertTrue(rendered.contains("updateDependencyBaseline"), "標頭應指出重新產生的方式")
    }

    @Test
    fun `空集合仍可輸出且可回讀`() {
        // 模組尚未有任何外溢依賴時不該產生格式怪異的檔案。
        val empty = DependencySet(module, emptyMap())

        assertEquals(empty, DependencySet.parse(module, empty.serialize()))
    }

    @Test
    fun `parse 與 serialize 互為反函數`() {
        val original = DependencySet(
            module,
            mapOf(
                DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE",
                DependencyCoordinate("io.netty", "netty-buffer") to "4.2.15.Final",
                DependencyCoordinate("org.yaml", "snakeyaml") to "2.6",
            ),
        )

        assertEquals(original, DependencySet.parse(module, original.serialize()))
    }

    @Test
    fun `parse 忽略註解與空行`() {
        val content = """
            # 由 ./gradlew updateDependencyBaseline 產生，請勿手動編輯。

            io.lettuce:lettuce-core:7.5.2.RELEASE
            # 中途插入的註解
            io.netty:netty-buffer:4.2.15.Final

        """.trimIndent()

        val parsed = DependencySet.parse(module, content)

        assertEquals(2, parsed.entries.size)
        assertEquals("7.5.2.RELEASE", parsed.entries[DependencyCoordinate("io.lettuce", "lettuce-core")])
    }

    @Test
    fun `parse 保留版本原字串不做正規化`() {
        val parsed = DependencySet.parse(module, "io.lettuce:lettuce-core:7.5.2.RELEASE\n")

        // 正規化成 7.5.2 會讓基準線與實際發布的 POM 對不上。
        assertEquals("7.5.2.RELEASE", parsed.entries.values.single())
    }

    @Test
    fun `parse 接受含版本的 CRLF 內容`() {
        // 讀取端要能容忍 CRLF（有人在 Windows 上手動改過檔案），寫入端則一律 LF。
        val parsed = DependencySet.parse(module, "io.lettuce:lettuce-core:7.5.2.RELEASE\r\n")

        assertEquals("7.5.2.RELEASE", parsed.entries.values.single())
    }

    // ---------- 格式錯誤：fail-fast 且指出行號 ----------

    @Test
    fun `行格式錯誤時拋出例外並指出行號與原內容`() {
        val content = "io.lettuce:lettuce-core:7.5.2.RELEASE\n這行壞掉了\n"

        val error = assertThrows<IllegalArgumentException> { DependencySet.parse(module, content) }

        assertTrue(error.message!!.contains("2"), "訊息應指出行號，實際為『${error.message}』")
        assertTrue(error.message!!.contains("這行壞掉了"), "訊息應帶出該行原內容")
        assertTrue(error.message!!.contains(module), "訊息應指出是哪個模組的基準線檔")
    }

    @Test
    fun `版本欄為空時拋出例外`() {
        val error = assertThrows<IllegalArgumentException> {
            DependencySet.parse(module, "io.lettuce:lettuce-core:\n")
        }

        assertTrue(error.message!!.contains("io.lettuce:lettuce-core:"))
    }

    @Test
    fun `同一座標重複出現時拋出例外`() {
        // 靜默採用最後一筆會讓基準線與實際解析結果悄悄脫節。
        val content = """
            io.lettuce:lettuce-core:7.5.2.RELEASE
            io.lettuce:lettuce-core:6.8.2.RELEASE
        """.trimIndent()

        val error = assertThrows<IllegalArgumentException> { DependencySet.parse(module, content) }

        assertTrue(error.message!!.contains("io.lettuce:lettuce-core"))
    }
}
