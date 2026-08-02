package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Standalone（直連）模式的 {@link LettuceConnectionFactoryBuilder} 實作。
 *
 * <p>支援 {@code redis://}（明文）與 {@code rediss://}（TLS）scheme，直接連接單一 Redis 節點。
 */
public class StandaloneConnectionFactoryBuilder implements LettuceConnectionFactoryBuilder {

    private final LettuceClientConfigurationFactory clientConfigurationFactory;

    /**
     * @param clientConfigurationFactory 客戶端設定工廠，承載 TLS、逾時與憑證來源等橫切設定
     */
    public StandaloneConnectionFactoryBuilder(LettuceClientConfigurationFactory clientConfigurationFactory) {
        this.clientConfigurationFactory = clientConfigurationFactory;
    }

    @Override
    public boolean supports(URI uri) {
        // 比對「去除 TLS 標記後的基礎 scheme」，因此 redis:// 與 rediss:// 都由本 builder 處理
        // ——兩者同屬 standalone 模式，差別僅在傳輸層是否加密。
        // 仍不採「非 sentinel 即 standalone」的 catch-all：未知 scheme（含錯字如
        // redissomething://）會在 Auto-Configuration 落入 orElseThrow 得到明確錯誤，
        // 而非被靜默當成明文 standalone 連線。
        return "redis".equals(RedisUriUtils.baseScheme(uri));
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

        return new LettuceConnectionFactory(config, clientConfigurationFactory.create(uri, properties));
    }
}
