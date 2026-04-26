package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Sentinel（高可用）模式的 {@link LettuceConnectionFactoryBuilder} 實作。
 *
 * <p>支援 {@code redis-sentinel://} scheme，透過 Redis Sentinel 哨兵機制實現主從自動切換。
 */
public class SentinelConnectionFactoryBuilder implements LettuceConnectionFactoryBuilder {

    @Override
    public boolean supports(URI uri) {
        return "redis-sentinel".equals(uri.getScheme());
    }

    @Override
    public LettuceConnectionFactory build(URI uri, KnoluxRedisProperties properties) {
        String masterName = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (masterName.isBlank()) {
            throw new IllegalArgumentException(
                    "Sentinel URI 必須包含 master name，例如：redis-sentinel://host:26379/mymaster");
        }
        String password = RedisUriUtils.parsePassword(uri);
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
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs())
                .readFrom(RedisUriUtils.parseReadFrom(properties.getReadFrom()))
                .build();

        return new LettuceConnectionFactory(config, clientConfig);
    }
}
