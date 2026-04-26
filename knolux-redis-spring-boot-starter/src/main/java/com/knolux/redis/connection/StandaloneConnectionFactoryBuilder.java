package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Standalone（直連）模式的 {@link LettuceConnectionFactoryBuilder} 實作。
 *
 * <p>支援 {@code redis://} scheme，直接連接單一 Redis 節點。
 */
public class StandaloneConnectionFactoryBuilder implements LettuceConnectionFactoryBuilder {

    @Override
    public boolean supports(URI uri) {
        return !"redis-sentinel".equals(uri.getScheme());
    }

    @Override
    public LettuceConnectionFactory build(URI uri, KnoluxRedisProperties properties) {
        String password = RedisUriUtils.parsePassword(uri);
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        int db = RedisUriUtils.parseDb(uri.getPath());

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setDatabase(db);
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }

        String readFrom = properties.getReadFrom();
        var builder = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs());

        // 純 MASTER / UPSTREAM 時不設定 readFrom，Lettuce 不會啟動 topology refresh；
        // 其他策略（含 LOWEST_LATENCY、ANY、ANY_REPLICA、subnet:、regex: 等）啟用讀寫分離
        if (!RedisUriUtils.isMasterOnly(readFrom)) {
            builder.readFrom(RedisUriUtils.parseReadFrom(readFrom));
        }

        return new LettuceConnectionFactory(config, builder.build());
    }
}
