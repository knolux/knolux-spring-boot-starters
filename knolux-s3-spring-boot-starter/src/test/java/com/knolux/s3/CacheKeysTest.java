package com.knolux.s3;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheKeys} 的單元測試。
 *
 * <p>驗證 SHA-256 雜湊的 null 安全性、確定性與輸出格式。
 */
class CacheKeysTest {

    @Test
    void sha256Hex_null_returnsEmptyString() {
        // null 視為「未設定」，回傳空字串（不產生 "null" 字面、語義等同空）
        assertThat(CacheKeys.sha256Hex(null)).isEmpty();
    }

    @Test
    void sha256Hex_isDeterministicAnd64HexChars() {
        assertThat(CacheKeys.sha256Hex("access-key"))
                .isEqualTo(CacheKeys.sha256Hex("access-key"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void sha256Hex_differentInputs_produceDifferentHashes() {
        assertThat(CacheKeys.sha256Hex("a")).isNotEqualTo(CacheKeys.sha256Hex("b"));
    }
}
