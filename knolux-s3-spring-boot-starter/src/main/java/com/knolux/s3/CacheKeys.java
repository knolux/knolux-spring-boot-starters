package com.knolux.s3;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 快取鍵輔助工具，提供用於 {@link KnoluxS3ConnectionDetails#toCacheKey()} 的雜湊函式。
 *
 * <p>將雜湊邏輯從值物件中分離，使 {@link KnoluxS3ConnectionDetails} 專注於持有連線參數，
 * 雜湊策略的變更（如演算法升級）則在此類別中集中維護。
 */
final class CacheKeys {

    private CacheKeys() {
    }

    /**
     * 計算字串的 SHA-256 摘要並以十六進位字串回傳。
     * {@code null} 輸入回傳空字串（語義等同「未設定」）。
     *
     * @param value 待雜湊的字串，可為 {@code null}
     * @return SHA-256 十六進位字串，或空字串（當 {@code value} 為 {@code null} 時）
     * @throws IllegalStateException 若 JVM 不支援 SHA-256（不應發生）
     */
    static String sha256Hex(String value) {
        if (value == null) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java SE 強制實作的演算法，不應到達此處
            throw new IllegalStateException("SHA-256 演算法不可用", e);
        }
    }
}
