package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * 對應 data-model.md §2。
 *
 * 座標的 `toString()` 同時是基準線檔的鍵、核准檔的鍵、以及報告中的顯示文字。
 * 只要 `toString()` 與 `parse()` 不是互為反函數，核准就會對不上 delta，
 * 而失效的模式是「看起來有核准、實際上擋不住」——最糟的一種。
 */
class DependencyCoordinateTest {

    @Test
    fun `toString 為 group 冒號 artifact`() {
        val coordinate = DependencyCoordinate("io.lettuce", "lettuce-core")

        assertEquals("io.lettuce:lettuce-core", coordinate.toString())
    }

    @ParameterizedTest(name = "往返：{0}")
    @ValueSource(
        strings = [
            "io.lettuce:lettuce-core",
            "io.netty:netty-transport-native-unix-common",
            "software.amazon.awssdk:s3",
            "org.springframework.boot:spring-boot-starter-data-redis",
        ],
    )
    fun `parse 與 toString 互為反函數`(raw: String) {
        assertEquals(raw, DependencyCoordinate.parse(raw).toString())
    }

    @Test
    fun `parse 拆出 group 與 artifact`() {
        val coordinate = DependencyCoordinate.parse("software.amazon.awssdk:s3")

        assertEquals("software.amazon.awssdk", coordinate.group)
        assertEquals("s3", coordinate.artifact)
    }

    @Test
    fun `parse 容許前後空白`() {
        // 核准檔由人手寫，多打一個空白不該讓核准靜默失配。
        assertEquals(
            DependencyCoordinate("io.lettuce", "lettuce-core"),
            DependencyCoordinate.parse("  io.lettuce : lettuce-core  "),
        )
    }

    @Test
    fun `相同座標可作為 Map 的鍵`() {
        val map = mapOf(DependencyCoordinate("io.lettuce", "lettuce-core") to "7.5.2.RELEASE")

        assertEquals("7.5.2.RELEASE", map[DependencyCoordinate.parse("io.lettuce:lettuce-core")])
    }

    // ---------- 格式錯誤：fail-fast，不得靜默略過 ----------

    @ParameterizedTest(name = "格式錯誤：''{0}''")
    @ValueSource(
        strings = [
            "io.lettuce",                    // 缺 artifact
            "io.lettuce:",                   // artifact 為空
            ":lettuce-core",                 // group 為空
            "io.lettuce:lettuce-core:7.5.2", // 多帶了版本，屬呼叫端用錯 API
            "",
            "   ",
            ":",
        ],
    )
    fun `格式錯誤時拋出例外`(raw: String) {
        // 這裡刻意與 ArtifactVersion 的 sealed ParseResult 做法不同：
        // 無法解析的「版本」是要被記進報告的一種差異；無法解析的「座標」則是
        // 基準線檔或核准檔的格式錯誤，除了修檔案沒有第二條路，故 fail-fast。
        val error = assertThrows<IllegalArgumentException> { DependencyCoordinate.parse(raw) }

        assertTrue(
            error.message!!.contains("group:artifact"),
            "例外訊息應帶出期望格式，實際為『${error.message}』",
        )
    }

    @Test
    fun `例外訊息帶出原始字串與期望格式`() {
        val error = assertThrows<IllegalArgumentException> { DependencyCoordinate.parse("io.lettuce") }

        assertTrue(error.message!!.contains("io.lettuce"), "訊息應帶出原始字串")
        assertTrue(error.message!!.contains("group:artifact"), "訊息應帶出期望格式")
    }
}
