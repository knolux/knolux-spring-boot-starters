package com.knolux.redis;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnClass(HealthIndicator.class)
public class KnoluxRedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;

    public KnoluxRedisHealthIndicator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public @Nullable Health health() {
        try {
            assert redis.getConnectionFactory() != null;
            var conn = redis.getConnectionFactory().getConnection();
            String pong = conn.ping();
            return Health.up()
                    .withDetail("ping", pong)
                    .build();
        } catch (Exception ex) {
            return Health.down()
                    .withException(ex)
                    .build();
        }
    }
}
