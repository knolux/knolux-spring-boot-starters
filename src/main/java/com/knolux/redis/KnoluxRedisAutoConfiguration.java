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

@AutoConfiguration
@EnableConfigurationProperties(KnoluxRedisProperties.class)
public class KnoluxRedisAutoConfiguration {
    private final KnoluxRedisProperties properties;

    public KnoluxRedisAutoConfiguration(KnoluxRedisProperties properties) {
        this.properties = properties;
    }

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

    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

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
