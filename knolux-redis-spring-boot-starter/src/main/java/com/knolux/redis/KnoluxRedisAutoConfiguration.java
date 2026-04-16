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
 * Redis 自動設定。
 *
 * <p>根據 {@code knolux.redis.url} 的 URL scheme 自動建立下列 Bean：
 * <ul>
 *   <li>{@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory} — 連線工廠</li>
 *   <li>{@link org.springframework.data.redis.core.StringRedisTemplate} — 字串操作模板</li>
 *   <li>{@link org.springframework.data.redis.core.RedisTemplate} — 物件操作模板（key 與 hash key 使用字串序列化）</li>
 * </ul>
 *
 * <p>支援兩種連線模式：
 * <ul>
 *   <li>{@code redis://} — Standalone 直連模式</li>
 *   <li>{@code redis-sentinel://} — Sentinel 高可用模式</li>
 * </ul>
 *
 * <p>所有 Bean 均標注 {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}，
 * 可由使用者定義同型別的 Bean 覆寫預設行為。
 */
@AutoConfiguration
@EnableConfigurationProperties(KnoluxRedisProperties.class)
public class KnoluxRedisAutoConfiguration {
    private final KnoluxRedisProperties properties;

    public KnoluxRedisAutoConfiguration(KnoluxRedisProperties properties) {
        this.properties = properties;
    }

    /**
     * 建立 {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}。
     *
     * <p>根據 {@code knolux.redis.url} 的 scheme 判斷模式：
     * {@code redis://} 使用 Standalone，{@code redis-sentinel://} 使用 Sentinel。
     *
     * @return 設定好的 {@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory}
     * @throws IllegalArgumentException 若 {@code knolux.redis.url} 未設定或為空
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory() {
        String url = properties.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("""
                    knolux.redis.url is required.
                      Cluster: knolux.redis.url=redis://:password@host:port
                      Sentinel: knolux.redis.url=redis-sentinel://:password@host:port,host:port,host:port
                    """
            );
        }

        LettuceClientConfiguration clientConfig = this.buildClientConfig();
        URI uri = URI.create(url);
        return "redis-sentinel".equals(uri.getScheme())
                ? this.buildSentinelFactory(uri, clientConfig)
                : this.buildStandaloneFactory(uri, clientConfig);
    }

    // ─────────────────────────────────────────────
    // Templates
    // ─────────────────────────────────────────────

    /**
     * 建立 {@link org.springframework.data.redis.core.StringRedisTemplate}，適用於純字串型 key/value 操作。
     *
     * @param factory Redis 連線工廠
     * @return 設定好的 {@link org.springframework.data.redis.core.StringRedisTemplate}
     */
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 建立通用 {@link org.springframework.data.redis.core.RedisTemplate}，key 與 hash key 使用 {@link org.springframework.data.redis.serializer.StringRedisSerializer}。
     *
     * @param factory Redis 連線工廠
     * @return 設定好的 {@link org.springframework.data.redis.core.RedisTemplate}
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

    private LettuceClientConfiguration buildClientConfig() {
        ReadFrom readFrom = switch (this.properties.getReadFrom().toUpperCase()) {
            case "MASTER" -> ReadFrom.MASTER;
            case "REPLICA" -> ReadFrom.REPLICA;
            case "ANY" -> ReadFrom.ANY;
            default -> ReadFrom.REPLICA_PREFERRED;
        };

        return LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs())
                .readFrom(readFrom)
                .build();
    }

    private LettuceConnectionFactory buildSentinelFactory(URI uri, LettuceClientConfiguration clientConfig) {
        String masterName = uri.getPath().replaceFirst("^/", "");
        String password = this.parsePassword(uri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;

        RedisSentinelConfiguration config = new RedisSentinelConfiguration()
                .master(masterName)
                .sentinel(host, port);

        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
            config.setSentinelPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(config, clientConfig);
    }

    private LettuceConnectionFactory buildStandaloneFactory(URI uri, LettuceClientConfiguration clientConfig) {
        String password = this.parsePassword(uri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        int db = this.parseDb(uri.getPath());

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(db);
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }

        return new LettuceConnectionFactory(config, clientConfig);
    }

    private String parsePassword(URI uri) {
        if (uri.getUserInfo() == null) return null;
        String[] parts = uri.getUserInfo().split(":", 2);
        return parts.length == 2 ? parts[1] : null;
    }

    private int parseDb(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) return 0;
        try {
            return Integer.parseInt(path.replaceFirst("^/", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
