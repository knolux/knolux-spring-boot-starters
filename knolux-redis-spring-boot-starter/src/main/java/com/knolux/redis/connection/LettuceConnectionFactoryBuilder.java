package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Lettuce 連線工廠建構器策略介面。
 *
 * <p>每個實作對應一種 Redis 連線模式（Standalone / Sentinel）。
 * 新增連線模式時，只需新增實作類別並加入建構器清單，不須修改 Auto-Configuration（符合 OCP）。
 */
public interface LettuceConnectionFactoryBuilder {
    /** 判斷此建構器是否支援給定 URI 的連線模式。 */
    boolean supports(URI uri);
    /** 依 URI 與屬性建立並回傳 LettuceConnectionFactory。 */
    LettuceConnectionFactory build(URI uri, KnoluxRedisProperties properties);
}
