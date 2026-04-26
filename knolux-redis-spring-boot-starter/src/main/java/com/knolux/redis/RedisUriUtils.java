package com.knolux.redis;

import io.lettuce.core.ReadFrom;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

/**
 * Redis URI 解析工具，將 URL 字串解析邏輯從 {@link KnoluxRedisAutoConfiguration} 中分離。
 *
 * <p>各方法可獨立進行單元測試，URL 格式變動（如支援 IPv6、百分號編碼密碼）
 * 僅需修改此類別，不影響設定類別。
 */
@Slf4j
public final class RedisUriUtils {

    private RedisUriUtils() {
    }

    /**
     * 從 URI 的 userInfo（格式 {@code [:username]:password}）解析密碼。
     * 無 userInfo 或無 {@code :} 分隔符時回傳 {@code null}。
     *
     * @param uri 已解析的 Redis URI
     * @return 密碼字串，或 {@code null}（無密碼時）
     */
    public static String parsePassword(URI uri) {
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
    public static int parseDb(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return 0;
        try {
            return Integer.parseInt(path.replaceFirst("^/", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 將策略字串轉換為 {@link ReadFrom}，直接委派至 Lettuce 的 {@link ReadFrom#valueOf(String)}。
     *
     * <p>支援 Lettuce 全部策略（不區分大小寫）：
     * <ul>
     *   <li>單一節點選擇 — {@code MASTER} / {@code UPSTREAM}、{@code MASTER_PREFERRED} /
     *       {@code UPSTREAM_PREFERRED}、{@code REPLICA} / {@code SLAVE}、
     *       {@code REPLICA_PREFERRED} / {@code SLAVE_PREFERRED}、
     *       {@code ANY}、{@code ANY_REPLICA}</li>
     *   <li>延遲導向 — {@code LOWEST_LATENCY} / {@code NEAREST}（需動態 topology refresh）</li>
     *   <li>子網路選擇 — {@code subnet:192.168.0.0/16,2001:db8::/52}</li>
     *   <li>正規表示式選擇 — {@code regex:.*region-1.*}</li>
     * </ul>
     *
     * <p>{@code null}、空白或無法解析的值會記錄 {@code WARN} 並回傳 {@link ReadFrom#REPLICA_PREFERRED}。
     *
     * @param readFrom 策略字串
     * @return 對應的 {@link ReadFrom} 實例
     * @see ReadFrom#valueOf(String)
     */
    public static ReadFrom parseReadFrom(String readFrom) {
        if (readFrom == null || readFrom.isBlank()) {
            log.warn("readFrom 未設定，使用預設值 REPLICA_PREFERRED");
            return ReadFrom.REPLICA_PREFERRED;
        }
        try {
            return ReadFrom.valueOf(readFrom.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("未知的 readFrom 策略 '{}'，退回使用 REPLICA_PREFERRED。" +
                            "支援值（不區分大小寫）：MASTER/UPSTREAM、MASTER_PREFERRED/UPSTREAM_PREFERRED、" +
                            "REPLICA/SLAVE、REPLICA_PREFERRED、LOWEST_LATENCY/NEAREST、" +
                            "ANY、ANY_REPLICA、subnet:<cidr,...>、regex:<pattern>",
                    readFrom);
            return ReadFrom.REPLICA_PREFERRED;
        }
    }

    /**
     * 判斷給定的 readFrom 字串是否為純 MASTER / UPSTREAM 模式。
     *
     * <p>純 MASTER 模式下 Lettuce 不需要 topology refresh，可降低背景連線開銷。
     * 此判斷僅針對 String 別名，不展開為 {@link ReadFrom} 實例後比對，
     * 避免 {@code subnet:} / {@code regex:} 等複合語法被誤判。
     *
     * @param readFrom 策略字串，可為 {@code null}
     * @return {@code true} 當值為 {@code "MASTER"} 或 {@code "UPSTREAM"}（不區分大小寫）
     */
    public static boolean isMasterOnly(String readFrom) {
        if (readFrom == null) return false;
        String trimmed = readFrom.trim();
        return "MASTER".equalsIgnoreCase(trimmed) || "UPSTREAM".equalsIgnoreCase(trimmed);
    }
}
