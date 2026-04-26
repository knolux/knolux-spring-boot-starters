package com.knolux.redis;

import com.knolux.redis.connection.LettuceConnectionFactoryBuilder;
import com.knolux.redis.connection.SentinelConnectionFactoryBuilder;
import com.knolux.redis.connection.StandaloneConnectionFactoryBuilder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;
import java.util.List;

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

    private static final List<LettuceConnectionFactoryBuilder> BUILDERS = List.of(
            new SentinelConnectionFactoryBuilder(),
            new StandaloneConnectionFactoryBuilder()
    );

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
     * <p>此方法解析 {@code knolux.redis.url} 的 URI scheme，並委派給對應的
     * {@link LettuceConnectionFactoryBuilder} 實作（策略模式）建立連線工廠。
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
        return BUILDERS.stream()
                .filter(b -> b.supports(uri))
                .findFirst()
                .map(b -> b.build(uri, properties))
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支援的 Redis URI scheme: " + uri.getScheme()));
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

}
