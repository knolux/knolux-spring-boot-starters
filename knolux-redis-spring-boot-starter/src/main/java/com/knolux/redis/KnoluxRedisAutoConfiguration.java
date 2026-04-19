package com.knolux.redis;

import io.lettuce.core.ReadFrom;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;

/**
 * Knolux Redis Spring Boot Starter 的自動設定類別。
 *
 * <p>此類別會在 Spring Boot 應用程式啟動時自動執行，根據 {@code knolux.redis.url}
 * 所指定的 URL scheme，自動建立以下 Spring Bean：
 *
 * <ul>
 *   <li>{@link LettuceConnectionFactory} — Redis 連線工廠，負責管理底層連線池</li>
 *   <li>{@link StringRedisTemplate} — 字串操作模板，key 與 value 均使用 UTF-8 字串序列化</li>
 *   <li>{@link RedisTemplate}{@code <String, Object>} — 通用物件操作模板，
 *       key 與 hash key 使用 {@link StringRedisSerializer}，value 使用預設的 JDK 序列化</li>
 * </ul>
 *
 * <h2>支援的連線模式</h2>
 * <ul>
 *   <li><strong>Standalone（直連）模式</strong> — URL scheme 為 {@code redis://}，
 *       直接連接單一 Redis 節點，適用於開發環境或不需高可用的場景</li>
 *   <li><strong>Sentinel（高可用）模式</strong> — URL scheme 為 {@code redis-sentinel://}，
 *       透過 Redis Sentinel 哨兵機制實現主從自動切換，適用於生產環境</li>
 * </ul>
 *
 * <h2>Bean 覆寫機制</h2>
 * <p>所有 Bean 均標注 {@link ConditionalOnMissingBean}，
 * 若使用者在自己的 {@code @Configuration} 類別中定義了相同型別的 Bean，
 * 自動設定的 Bean 將不會被建立，讓使用者得以完全自訂連線行為。
 *
 * <h2>設定範例</h2>
 * <pre>{@code
 * # application.yml
 * knolux:
 *   redis:
 *     url: redis://:password@localhost:6379
 *     timeout-ms: 1000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 *
 * @see KnoluxRedisProperties
 * @see KnoluxRedisHealthIndicator
 */
@AutoConfiguration
@EnableConfigurationProperties(KnoluxRedisProperties.class)
public class KnoluxRedisAutoConfiguration {

    private final KnoluxRedisProperties properties;

    public KnoluxRedisAutoConfiguration(KnoluxRedisProperties properties) {
        this.properties = properties;
    }

    // ─────────────────────────────────────────────
    // 連線工廠
    // ─────────────────────────────────────────────

    /**
     * 建立並設定 {@link LettuceConnectionFactory}（Redis 連線工廠）。
     *
     * <p>此方法解析 {@code knolux.redis.url} 的 URI scheme，並據此選擇連線模式：
     * <ul>
     *   <li>scheme 為 {@code redis-sentinel} → 呼叫 {@link #buildSentinelFactory(URI)}
     *       建立 Sentinel 高可用連線工廠</li>
     *   <li>其他（通常為 {@code redis}）→ 呼叫 {@link #buildStandaloneFactory(URI)}
     *       建立 Standalone 直連工廠</li>
     * </ul>
     *
     * <p>每種模式內部獨立決定 {@link LettuceClientConfiguration}，
     * 例如 Standalone 且 {@code readFrom=MASTER} 時不啟動 topology refresh。
     *
     * @return 設定完成的 {@link LettuceConnectionFactory}，可直接被 {@link RedisTemplate} 使用
     * @throws IllegalArgumentException 若 {@code knolux.redis.url} 未設定或為空白字串
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory() {
        String url = properties.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("""
                    knolux.redis.url is required.
                      Standalone: knolux.redis.url=redis://:password@host:port
                      Sentinel:   knolux.redis.url=redis-sentinel://:password@host:port/mastername
                    """
            );
        }

        URI uri = URI.create(url);
        return "redis-sentinel".equals(uri.getScheme())
                ? buildSentinelFactory(uri)
                : buildStandaloneFactory(uri);
    }

    // ─────────────────────────────────────────────
    // Templates
    // ─────────────────────────────────────────────

    /**
     * 建立 {@link StringRedisTemplate}，key 與 value 均使用 UTF-8 字串序列化。
     *
     * <p>序列化結果可直接透過 Redis CLI 讀取，便於除錯。
     * 若應用程式已自訂 {@link StringRedisTemplate} Bean，此 Bean 不會被建立。
     *
     * @param factory Redis 連線工廠
     * @return 設定完成的 {@link StringRedisTemplate}
     */
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 建立通用 {@link RedisTemplate}{@code <String, Object>}，key 與 hash key 使用
     * {@link StringRedisSerializer}，value 使用預設的 JDK 序列化。
     *
     * <p>使用 {@code name = "redisTemplate"} 條件而非型別匹配，
     * 是為了避免 {@link StringRedisTemplate}（{@link RedisTemplate} 子類別）干擾型別判斷。
     * 若需自訂序列化（如 Jackson JSON），可定義同名 Bean 覆寫此設定。
     *
     * @param factory Redis 連線工廠
     * @return 設定完成的 {@link RedisTemplate}{@code <String, Object>}
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        return template;
    }

    // ─────────────────────────────────────────────
    // 內部工具
    // ─────────────────────────────────────────────

    /**
     * 建立 Sentinel（高可用）模式的 {@link LettuceConnectionFactory}。
     *
     * <p>從 URI 中解析以下資訊：
     * <ul>
     *   <li>Master 名稱：取自 URI 的 path 部分（去除開頭的 {@code /}），例如 {@code mymaster}</li>
     *   <li>密碼：取自 URI 的 userInfo 部分（格式為 {@code :password}）</li>
     *   <li>Sentinel 主機與埠號：取自 URI 的 host 與 port；若未指定埠號則預設為 {@code 26379}</li>
     * </ul>
     *
     * <p>密碼會同時套用至 Redis 資料節點（{@code setPassword}）與
     * Sentinel 節點（{@code setSentinelPassword}），確保兩者均可認證。
     *
     * <p>Sentinel 模式下強制設定 {@link ReadFrom} 策略，預設為 {@code REPLICA_PREFERRED}，
     * 以充分利用 Sentinel 管理的從節點進行讀取分流。
     *
     * @param uri 已解析的 Sentinel URI，scheme 必須為 {@code redis-sentinel}
     * @return 設定完成的 Sentinel 模式 {@link LettuceConnectionFactory}
     */
    private LettuceConnectionFactory buildSentinelFactory(URI uri) {
        String masterName = uri.getPath().replaceFirst("^/", "");
        String password = parsePassword(uri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 26379;

        RedisSentinelConfiguration config = new RedisSentinelConfiguration()
                .master(masterName)
                .sentinel(host, port);

        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
            config.setSentinelPassword(RedisPassword.of(password));
        }

        // Sentinel 模式永遠需要 readFrom（REPLICA_PREFERRED 為預設）
        ReadFrom rf = parseReadFrom(properties.getReadFrom());
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs())
                .readFrom(rf)
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }

    /**
     * 建立 Standalone（直連）模式的 {@link LettuceConnectionFactory}。
     *
     * <p>從 URI 中解析以下資訊：
     * <ul>
     *   <li>主機名稱：取自 URI 的 host 部分</li>
     *   <li>埠號：取自 URI 的 port 部分；若未指定則預設為 {@code 6379}</li>
     *   <li>資料庫編號：取自 URI 的 path 部分（去除開頭的 {@code /}）；
     *       若未指定或解析失敗則預設為 {@code 0}</li>
     *   <li>密碼：取自 URI 的 userInfo 部分</li>
     * </ul>
     *
     * <p>讀取策略的處理邏輯：
     * <ul>
     *   <li>當 {@code readFrom=MASTER} 時 — 不設定 {@link ReadFrom}，
     *       Lettuce 不會啟動 topology refresh 背景執行緒，適用於純單節點部署</li>
     *   <li>當 {@code readFrom} 為其他值時 — 設定對應的 {@link ReadFrom} 策略，
     *       並啟用 topology refresh 以支援讀寫分離</li>
     * </ul>
     *
     * @param uri 已解析的 Standalone URI，scheme 通常為 {@code redis}
     * @return 設定完成的 Standalone 模式 {@link LettuceConnectionFactory}
     */
    private LettuceConnectionFactory buildStandaloneFactory(URI uri) {
        String password = parsePassword(uri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        int db = parseDb(uri.getPath());

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(db);
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }

        String readFrom = properties.getReadFrom();
        if ("MASTER".equalsIgnoreCase(readFrom)) {
            // 純 Standalone：不設定 readFrom，Lettuce 不會啟動 topology refresh
            LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                    .commandTimeout(properties.getTimeoutMs())
                    .build();
            return new LettuceConnectionFactory(config, clientConfig);
        }

        // REPLICA_PREFERRED / REPLICA / ANY：啟用 topology refresh 支援讀寫分離
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs())
                .readFrom(parseReadFrom(readFrom))
                .build();
        return new LettuceConnectionFactory(config, clientConfig);
    }

    /**
     * 將策略字串（不區分大小寫）轉換為 {@link ReadFrom}；未知值回傳 {@link ReadFrom#REPLICA_PREFERRED}。
     */
    private ReadFrom parseReadFrom(String readFrom) {
        return switch (readFrom.toUpperCase()) {
            case "REPLICA" -> ReadFrom.REPLICA;
            case "ANY" -> ReadFrom.ANY;
            case "MASTER" -> ReadFrom.MASTER;
            default -> ReadFrom.REPLICA_PREFERRED;
        };
    }

    /**
     * 從 URI 的 userInfo（格式 {@code [:username]:password}）解析密碼。
     * 無 userInfo 或無 {@code :} 分隔符時回傳 {@code null}。
     */
    private String parsePassword(URI uri) {
        if (uri.getUserInfo() == null) return null;
        String[] parts = uri.getUserInfo().split(":", 2);
        return parts.length == 2 ? parts[1] : null;
    }

    /**
     * 從 URI path（如 {@code /3}）解析 Redis 資料庫編號；
     * path 為空或非數字時回傳 {@code 0}。
     */
    private int parseDb(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return 0;
        try {
            return Integer.parseInt(path.replaceFirst("^/", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
