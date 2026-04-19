package com.knolux.redis;

import io.lettuce.core.ReadFrom;

import java.net.URI;

/**
 * Redis URI 解析工具，將 URL 字串解析邏輯從 {@link KnoluxRedisAutoConfiguration} 中分離。
 *
 * <p>各方法可獨立進行單元測試，URL 格式變動（如支援 IPv6、百分號編碼密碼）
 * 僅需修改此類別，不影響設定類別。
 */
final class RedisUriUtils {

    private RedisUriUtils() {}

    /**
     * 從 URI 的 userInfo（格式 {@code [:username]:password}）解析密碼。
     * 無 userInfo 或無 {@code :} 分隔符時回傳 {@code null}。
     *
     * @param uri 已解析的 Redis URI
     * @return 密碼字串，或 {@code null}（無密碼時）
     */
    static String parsePassword(URI uri) {
        if (uri.getUserInfo() == null) return null;
        String[] parts = uri.getUserInfo().split(":", 2);
        return parts.length == 2 ? parts[1] : null;
    }

    /**
     * 從 URI path（如 {@code /3}）解析 Redis 資料庫編號。
     * path 為空或非數字時回傳 {@code 0}。
     *
     * @param path URI 的 path 部分
     * @return 資料庫編號（0 ~ 15），解析失敗時為 {@code 0}
     */
    static int parseDb(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return 0;
        try {
            return Integer.parseInt(path.replaceFirst("^/", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 將策略字串（不區分大小寫）轉換為 {@link ReadFrom}；未知值回傳 {@link ReadFrom#REPLICA_PREFERRED}。
     *
     * @param readFrom 策略字串，如 {@code "MASTER"}、{@code "REPLICA"}、{@code "ANY"}
     * @return 對應的 {@link ReadFrom} 列舉值
     */
    static ReadFrom parseReadFrom(String readFrom) {
        return switch (readFrom.toUpperCase()) {
            case "REPLICA" -> ReadFrom.REPLICA;
            case "ANY"     -> ReadFrom.ANY;
            case "MASTER"  -> ReadFrom.MASTER;
            default        -> ReadFrom.REPLICA_PREFERRED;
        };
    }
}
