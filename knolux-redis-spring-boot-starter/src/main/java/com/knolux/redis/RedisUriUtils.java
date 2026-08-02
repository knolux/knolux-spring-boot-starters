package com.knolux.redis;

import io.lettuce.core.ReadFrom;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Redis URI 解析工具，將 URL 字串解析邏輯從 {@link KnoluxRedisAutoConfiguration} 中分離。
 *
 * <p>各方法可獨立進行單元測試，URL 格式變動（如支援 IPv6、百分號編碼密碼）
 * 僅需修改此類別，不影響設定類別。
 */
@Slf4j
public final class RedisUriUtils {

    /**
     * TLS scheme 至其明文對應 scheme 的別名表。
     *
     * <p>刻意採「具名別名表」而非 {@code startsWith("rediss")} 字首比對：
     * 字首比對會把 {@code redissomething://} 之類的錯字誤判為合法 TLS scheme，
     * 進而落入某個 builder 而非在 Auto-Configuration 得到明確錯誤。
     * 新增連線模式時在此補一組對應即可。
     */
    private static final Map<String, String> TLS_SCHEME_ALIASES = Map.of(
            "rediss", "redis",
            "rediss-sentinel", "redis-sentinel",
            "rediss-cluster", "redis-cluster"
    );

    private RedisUriUtils() {
    }

    /**
     * 判斷 URI 是否使用 TLS（{@code rediss} 系列 scheme）。
     *
     * <p>本 starter 以 scheme 單一來源決定是否加密，不另設 {@code ssl.enabled} 旗標，
     * 避免出現「{@code rediss://} 但 {@code ssl.enabled=false}」這種互相矛盾的無效狀態。
     *
     * <p>支援的 TLS scheme（不區分大小寫，RFC 3986 規定 scheme 不分大小寫）：
     * {@code rediss://}、{@code rediss-sentinel://}、{@code rediss-cluster://}。
     *
     * @param uri 已解析的 Redis URI，可為 {@code null}
     * @return {@code true} 當 scheme 為已知的 TLS 別名
     */
    public static boolean isTls(URI uri) {
        return baseScheme(uri) != null
                && TLS_SCHEME_ALIASES.containsKey(uri.getScheme().toLowerCase(Locale.ROOT));
    }

    /**
     * 取得 URI 的「基礎 scheme」——轉為小寫並去除 TLS 標記。
     *
     * <p>例如 {@code REDISS-CLUSTER://} 與 {@code redis-cluster://} 都會得到
     * {@code "redis-cluster"}，讓各 builder 的 {@code supports()} 只需比對一個值，
     * 不必為每種模式重複寫「明文或 TLS」的雙重判斷。
     *
     * <p>未知 scheme 原樣以小寫回傳（不做任何猜測式改寫），交由呼叫端 fail-fast。
     *
     * @param uri 已解析的 Redis URI，可為 {@code null}
     * @return 小寫且去除 TLS 標記的 scheme；URI 或 scheme 為 {@code null} 時回傳 {@code null}
     */
    public static String baseScheme(URI uri) {
        if (uri == null || uri.getScheme() == null) return null;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        return TLS_SCHEME_ALIASES.getOrDefault(scheme, scheme);
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
     * path 為空、{@code "/"} 或空白時回傳 {@code 0}（預設 DB）；非數字則視為設定錯誤拋出例外。
     *
     * @param path URI 的 path 部分
     * @return 資料庫編號（空路徑回傳 {@code 0}）
     * @throws IllegalArgumentException 若 DB 區段為非數字
     */
    public static int parseDb(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return 0;
        String dbSegment = path.replaceFirst("^/", "");
        try {
            return Integer.parseInt(dbSegment);
        } catch (NumberFormatException e) {
            // 非數字 DB 區段是設定錯誤（例如把 sentinel 的 /mastername 餵給 standalone）。
            // 過去靜默退回 DB 0 會導致跨租戶 key 碰撞且無任何訊號；改為 fail-fast。
            throw new IllegalArgumentException(
                    "Redis URL 的 DB 區段必須為數字，實際為: \"" + dbSegment
                            + "\"（例如 redis://host:6379/3）", e);
        }
    }

    /**
     * 將策略字串轉換為 {@link ReadFrom}。
     *
     * <p>解析流程：先以大小寫不敏感的 switch 比對所有已知的具名策略，
     * 再將 {@code subnet:} / {@code regex:} 前綴形式委派至 {@link ReadFrom#valueOf(String)}。
     *
     * <p>支援策略（不區分大小寫）：
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
     */
    public static ReadFrom parseReadFrom(String readFrom) {
        if (readFrom == null || readFrom.isBlank()) {
            log.warn("readFrom 未設定，使用預設值 REPLICA_PREFERRED");
            return ReadFrom.REPLICA_PREFERRED;
        }
        String trimmed = readFrom.trim();
        return switch (trimmed.toUpperCase()) {
            case "MASTER", "UPSTREAM" -> ReadFrom.MASTER;
            case "MASTER_PREFERRED", "UPSTREAM_PREFERRED" -> ReadFrom.MASTER_PREFERRED;
            case "REPLICA", "SLAVE" -> ReadFrom.REPLICA;
            // SLAVE_PREFERRED 是本 starter 自訂的底線形式別名；
            // Lettuce valueOf() 使用 camelCase（slavePreferred），不接受底線形式。
            case "REPLICA_PREFERRED", "SLAVE_PREFERRED" -> ReadFrom.REPLICA_PREFERRED;
            case "LOWEST_LATENCY", "NEAREST" -> ReadFrom.LOWEST_LATENCY;
            case "ANY" -> ReadFrom.ANY;
            case "ANY_REPLICA" -> ReadFrom.ANY_REPLICA;
            default -> {
                // subnet:<cidr,...> 和 regex:<pattern> 委派給 Lettuce 解析。
                // 使用 trimmed（保留原始大小寫）以確保 CIDR / regex 內容不被大寫化。
                try {
                    yield ReadFrom.valueOf(trimmed);
                } catch (IllegalArgumentException ex) {
                    log.warn("未知的 readFrom 策略 '{}'，退回使用 REPLICA_PREFERRED。" +
                                    "支援值（不區分大小寫）：MASTER/UPSTREAM、MASTER_PREFERRED/UPSTREAM_PREFERRED、" +
                                    "REPLICA/SLAVE、REPLICA_PREFERRED/SLAVE_PREFERRED、LOWEST_LATENCY/NEAREST、" +
                                    "ANY、ANY_REPLICA、subnet:<cidr,...>、regex:<pattern>",
                            readFrom);
                    yield ReadFrom.REPLICA_PREFERRED;
                }
            }
        };
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
