package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 「簽入的基準線檔已跟不上當前解析結果」該不該讓閘門失敗（research.md R2 機制 M1）。
 *
 * 這條判定是 T054 端對端驗證挖出來的：原本的實作只要基準線一有落差就讓
 * `checkDependencyCompatibility` 失敗，於是「把 awssdk 調成僅差 patch 的版本」——
 * 也就是 Dependabot 每週送來的那種 PR——必定紅燈，直接違反 FR-011
 * （MUST NOT 因 minor 變動、patch 變動或依賴新增而讓建置失敗）與 SC-003（失敗率 0%）。
 *
 * 諷刺的是，research.md R2 否決 Gradle dependency locking 的理由正是「會讓每支 bot PR 紅燈」，
 * 而簽入基準線的方案若把過期一律當成失敗，等於原地把同一個坑再挖一次。
 *
 * 因此落差的**嚴重度**決定成敗，而非落差的有無：
 * - 含阻擋性項目（major / 移除 / 無法解析）→ 失敗。這種落差留在 `dev` 上，
 *   下一次發版前的 `checkDependencyBaseline` 才會爆，而那是最不該被擋住的時刻。
 * - 全屬非阻擋性 → 警告。基準線晚一點補上只會讓後續 PR 多報幾行資訊性變動，
 *   代價遠低於讓每支 bot PR 紅燈進而使整個閘門被停用。
 */
class BaselineStalenessTest {

    @Test
    fun `無落差時回傳 null`() {
        val same = dependencySet("software.amazon.awssdk:s3" to "2.49.3")

        assertNull(BaselineStaleness.detect(same, same))
    }

    @Test
    fun `落差全屬非阻擋性時不讓建置失敗`() {
        // 正是 quickstart 情境 4：Dependabot 把 awssdk 前推一個 patch。
        val staleness = BaselineStaleness.detect(
            baseline = dependencySet("software.amazon.awssdk:s3" to "2.49.3"),
            current = dependencySet("software.amazon.awssdk:s3" to "2.49.4"),
        )

        assertTrue(staleness != null, "落差存在時必須被偵測到")
        assertFalse(staleness!!.blocking, "patch 落差不得讓建置失敗（FR-011）")
        assertEquals(MODULE, staleness.moduleName)
    }

    @Test
    fun `新增與 minor 落差同樣不讓建置失敗`() {
        val staleness = BaselineStaleness.detect(
            baseline = dependencySet("org.springframework:spring-core" to "7.0.7"),
            current = dependencySet(
                "org.springframework:spring-core" to "7.1.0",
                "org.springframework:spring-messaging" to "7.1.0",
            ),
        )

        assertFalse(staleness!!.blocking, "實際落差為 ${staleness.deltas}")
    }

    @Test
    fun `落差含 major 跳動時讓建置失敗`() {
        val staleness = BaselineStaleness.detect(
            baseline = dependencySet("io.lettuce:lettuce-core" to "6.8.2.RELEASE"),
            current = dependencySet("io.lettuce:lettuce-core" to "7.5.2.RELEASE"),
        )

        assertTrue(staleness!!.blocking, "major 落差必須當場擋下，不能拖到發版前才爆")
    }

    @Test
    fun `落差含依賴移除時讓建置失敗`() {
        val staleness = BaselineStaleness.detect(
            baseline = dependencySet(
                "io.netty:netty-transport" to "4.1.125.Final",
                "org.springframework:spring-core" to "7.0.8",
            ),
            current = dependencySet("org.springframework:spring-core" to "7.0.8"),
        )

        assertTrue(staleness!!.blocking, "實際落差為 ${staleness.deltas}")
    }

    /**
     * 一項阻擋性就足以讓整個模組的落差成為失敗——不因為旁邊有一堆 patch 而被稀釋。
     * Dependabot 的 grouped update 產出的正是這種混合落差。
     */
    @Test
    fun `混合落差中只要有一項阻擋性就讓建置失敗`() {
        val staleness = BaselineStaleness.detect(
            baseline = dependencySet(
                "software.amazon.awssdk:s3" to "2.49.3",
                "io.lettuce:lettuce-core" to "6.8.2.RELEASE",
            ),
            current = dependencySet(
                "software.amazon.awssdk:s3" to "2.49.4",
                "io.lettuce:lettuce-core" to "7.5.2.RELEASE",
            ),
        )

        assertTrue(staleness!!.blocking, "實際落差為 ${staleness.deltas}")
    }

    /**
     * 空的落差不是「沒問題的落差」，而是根本不該存在的物件。
     * 讓它建構得起來，等於允許輸出一段「基準線已過期，共 0 處不一致」的訊息——
     * 讀者只會當成顯示錯誤而略過，正是本功能要根除的雜訊。
     */
    @Test
    fun `落差為空時不得建構`() {
        assertThrows<IllegalArgumentException> { BaselineStaleness(MODULE, emptyList()) }
    }

    private fun dependencySet(vararg entries: Pair<String, String>) = DependencySet(
        MODULE,
        entries.toMap().mapKeys { (coordinate, _) -> DependencyCoordinate.parse(coordinate) },
    )

    private companion object {
        const val MODULE = "knolux-s3-spring-boot-starter"
    }
}
