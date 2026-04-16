package com.knolux.redis;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 健康狀態指標。
 *
 * <p>對 Redis 發送 {@code PING} 指令確認連線正常。
 * 僅在 {@code spring-boot-starter-actuator} 存在於 classpath 時才會啟用。
 *
 * <p>健康端點範例回應（{@code GET /actuator/health}）：
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "components": {
 *     "knoluxRedis": {
 *       "status": "UP",
 *       "details": { "ping": "PONG" }
 *     }
 *   }
 * }
 * }</pre>
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
public class KnoluxRedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public KnoluxRedisHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 執行 Redis 健康檢查。
     *
     * @return 健康狀態；PING 成功則為 {@code UP}（附 {@code ping: PONG}），
     *         否則為 {@code DOWN}（附錯誤訊息）
     */
    @Override
    public @Nullable Health health() {
        try {
            var connectionFactory = redis.getConnectionFactory();
            if (connectionFactory == null) {
                return Health.down()
                        .withDetail("error", "Connection factory is null")
                        .build();
            }
            var conn = connectionFactory.getConnection();
            String pong = conn.ping();
            return Health.up()
                    .withDetail("ping", pong)
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
