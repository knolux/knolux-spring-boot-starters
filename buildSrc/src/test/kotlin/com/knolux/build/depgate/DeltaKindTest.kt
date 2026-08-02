package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * 對應 data-model.md §4 與 §5。
 *
 * 阻擋性是 [DeltaKind] 的固有屬性，不由呼叫端各自判斷——同一條規則散落在
 * 任務、報告、核准比對三處，遲早會出現「報告說會擋、任務卻放行」的分歧。
 */
class DeltaKindTest {

    @ParameterizedTest(name = "{0} 為阻擋性")
    @EnumSource(DeltaKind::class, names = ["MAJOR", "REMOVED", "DOWNGRADE", "UNPARSEABLE"])
    fun `破壞性類別為阻擋性`(kind: DeltaKind) {
        assertTrue(kind.blocking, "$kind 應為阻擋性")
    }

    @ParameterizedTest(name = "{0} 為資訊性")
    @EnumSource(DeltaKind::class, names = ["MINOR", "PATCH", "ADDED"])
    fun `非破壞性類別為資訊性`(kind: DeltaKind) {
        assertFalse(kind.blocking, "$kind 不應阻擋建置")
    }

    @Test
    fun `恰有七種類別`() {
        // 新增類別時這個斷言會失敗，強迫開發者回頭決定它的阻擋性，
        // 而不是讓一個未分類的類別以預設值悄悄溜過閘門。
        assertEquals(
            setOf(
                DeltaKind.MAJOR,
                DeltaKind.MINOR,
                DeltaKind.PATCH,
                DeltaKind.ADDED,
                DeltaKind.REMOVED,
                DeltaKind.DOWNGRADE,
                DeltaKind.UNPARSEABLE,
            ),
            DeltaKind.entries.toSet(),
        )
    }

    @Test
    fun `ADDED 的 delta 不得有 from`() {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = "2.0.0", kind = DeltaKind.ADDED)
        }
    }

    @Test
    fun `ADDED 的 delta 必須有 to`() {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = null, to = null, kind = DeltaKind.ADDED)
        }
    }

    @Test
    fun `REMOVED 的 delta 不得有 to`() {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = "2.0.0", kind = DeltaKind.REMOVED)
        }
    }

    @Test
    fun `REMOVED 的 delta 必須有 from`() {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = null, to = null, kind = DeltaKind.REMOVED)
        }
    }

    @ParameterizedTest(name = "{0} 的 from 與 to 皆不得為 null")
    @EnumSource(DeltaKind::class, names = ["MAJOR", "MINOR", "PATCH", "DOWNGRADE", "UNPARSEABLE"])
    fun `版本變動類別的兩側版本皆須存在`(kind: DeltaKind) {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = null, kind = kind)
        }
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = null, to = "2.0.0", kind = kind)
        }
    }

    @Test
    fun `合法的 delta 可以建立`() {
        val added = DependencyDelta(MODULE, COORDINATE, from = null, to = "1.0.0", kind = DeltaKind.ADDED)
        val removed = DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = null, kind = DeltaKind.REMOVED)
        val major = DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = "2.0.0", kind = DeltaKind.MAJOR)

        assertEquals(DeltaKind.ADDED, added.kind)
        assertEquals(DeltaKind.REMOVED, removed.kind)
        assertTrue(major.blocking)
    }

    @Test
    fun `unparseableReason 僅 UNPARSEABLE 可填`() {
        assertThrows<IllegalArgumentException> {
            DependencyDelta(
                MODULE,
                COORDINATE,
                from = "1.0.0",
                to = "2.0.0",
                kind = DeltaKind.MAJOR,
                unparseableReason = "不該出現在這裡",
            )
        }
    }

    @Test
    fun `UNPARSEABLE 必須說明無法解析的理由`() {
        // 少了理由，維護者只會看到「這筆擋下來了」卻不知道為什麼，
        // 也就無從判斷該修版本字串還是該核准。
        assertThrows<IllegalArgumentException> {
            DependencyDelta(MODULE, COORDINATE, from = "1.0.0", to = "latest.release", kind = DeltaKind.UNPARSEABLE)
        }
    }

    private companion object {
        const val MODULE = "knolux-redis-spring-boot-starter"
        val COORDINATE = DependencyCoordinate("io.lettuce", "lettuce-core")
    }
}
