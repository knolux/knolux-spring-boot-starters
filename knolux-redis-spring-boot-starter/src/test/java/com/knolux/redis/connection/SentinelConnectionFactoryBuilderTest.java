package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelConnectionFactoryBuilderTest {
    private final SentinelConnectionFactoryBuilder builder = new SentinelConnectionFactoryBuilder();

    private KnoluxRedisProperties props() {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofMillis(1000));
        p.setReadFrom("REPLICA_PREFERRED");
        return p;
    }

    @Test void supports_sentinel_scheme() {
        assertThat(builder.supports(URI.create("redis-sentinel://host:26379/master"))).isTrue();
    }
    @Test void does_not_support_redis_scheme() {
        assertThat(builder.supports(URI.create("redis://localhost:6379"))).isFalse();
    }
    @Test void builds_factory_with_master_name() {
        assertThat(builder.build(URI.create("redis-sentinel://:pass@host:26379/mymaster"), props())).isNotNull();
    }
    @Test void builds_factory_default_sentinel_port_fallback() {
        // URI 不含 port，應使用 26379
        assertThat(builder.build(URI.create("redis-sentinel://:pass@host/mymaster"), props())).isNotNull();
    }
}
