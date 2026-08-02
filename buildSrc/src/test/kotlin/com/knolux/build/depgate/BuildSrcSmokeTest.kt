package com.knolux.build.depgate

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 確認 buildSrc 的測試基礎設施可用。
 *
 * 本功能選擇 `buildSrc/` 而非把邏輯寫進 `build.gradle.kts`，決定性理由就是
 * 「建置邏輯必須可單元測試」（憲章 I）。這個冒煙測試存在的意義是：
 * 一旦它跑不起來，代表整條 TDD 路徑從一開始就不成立，應立即停下修好，
 * 而不是繼續往下寫沒有測試保護的閘門邏輯。
 */
class BuildSrcSmokeTest {

    @Test
    fun `buildSrc 的測試任務可以執行`() {
        assertTrue(true)
    }
}
