package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Sentinel（高可用）模式的 {@link LettuceConnectionFactoryBuilder} 實作。
 *
 * <p>支援 {@code redis-sentinel://}（明文）與 {@code rediss-sentinel://}（TLS）scheme，
 * 透過 Redis Sentinel 哨兵機制實現主從自動切換。
 */
public class SentinelConnectionFactoryBuilder implements LettuceConnectionFactoryBuilder {

    private final LettuceClientConfigurationFactory clientConfigurationFactory;

    /**
     * @param clientConfigurationFactory 客戶端設定工廠，承載 TLS、逾時與憑證來源等橫切設定
     */
    public SentinelConnectionFactoryBuilder(LettuceClientConfigurationFactory clientConfigurationFactory) {
        this.clientConfigurationFactory = clientConfigurationFactory;
    }

    @Override
    public boolean supports(URI uri) {
        // 比對基礎 scheme：涵蓋 redis-sentinel:// 與 rediss-sentinel://，且大小寫不敏感
        // （RFC 3986）。Redis-Sentinel:// 等寫法亦正確識別為 Sentinel，
        // 不會誤落入 Standalone 而靜默停用主從 failover。
        return "redis-sentinel".equals(RedisUriUtils.baseScheme(uri));
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

        // Sentinel 模式永遠需要 readFrom（REPLICA_PREFERRED 為預設），
        // 因此不套用 Standalone 的「MASTER 時略過」最佳化
        return new LettuceConnectionFactory(config, clientConfigurationFactory.create(
                uri, properties, LettuceClientConfigurationFactory.ReadFromPolicy.ALWAYS));
    }
}
