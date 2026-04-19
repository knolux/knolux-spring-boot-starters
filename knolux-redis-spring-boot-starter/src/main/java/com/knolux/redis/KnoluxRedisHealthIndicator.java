package com.knolux.redis;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 連線健康狀態指標。
 *
 * <p>實作 Spring Boot Actuator 的 {@link HealthIndicator} 介面，
 * 透過向 Redis 發送 {@code PING} 指令來確認連線是否正常。
 * 健康狀態會整合至 {@code /actuator/health} 端點，Bean 名稱為 {@code knoluxRedis}。
 *
 * <p>此 Bean 僅在 {@code spring-boot-starter-actuator} 存在於 classpath 時才會啟用
 * （透過 {@link ConditionalOnClass} 條件判斷），不強制要求使用者引入 Actuator 依賴。
 *
 * <h2>健康端點回應範例</h2>
 *
 * <h3>Redis 正常（{@code GET /actuator/health}）</h3>
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "components": {
 *     "knoluxRedis": {
 *       "status": "UP",
 *       "details": {
 *         "ping": "PONG"
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h3>Redis 異常</h3>
 * <pre>{@code
 * {
 *   "status": "DOWN",
 *   "components": {
 *     "knoluxRedis": {
 *       "status": "DOWN",
 *       "details": {
 *         "error": "Connection refused: localhost/127.0.0.1:6379"
 *       }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <h2>啟用健康檢查</h2>
 * <p>在 {@code application.yml} 中加入以下設定可顯示詳細資訊：
 * <pre>{@code
 * management:
 *   endpoint:
 *     health:
 *       show-details: always
 * }</pre>
 *
 * @see HealthIndicator
 * @see KnoluxRedisAutoConfiguration
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
public class KnoluxRedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public KnoluxRedisHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 向 Redis 發送 {@code PING} 指令確認連線狀態。
     *
     * <p>收到 {@code PONG} 回傳 {@link Health#up()}，任何例外回傳 {@link Health#down()}。
     * 連線（{@link org.springframework.data.redis.connection.RedisConnection}）於使用後自動關閉。
     *
     * @return 包含狀態與診斷資訊的 {@link Health} 物件
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
            try (var conn = connectionFactory.getConnection()) {
                String pong = conn.ping();
                return Health.up()
                        .withDetail("ping", pong)
                        .build();
            }
        } catch (Exception ex) {
            return Health.down()
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
