package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SentinelConnectionFactoryBuilderTest {
    private final SentinelConnectionFactoryBuilder builder = new SentinelConnectionFactoryBuilder();

    private KnoluxRedisProperties props() {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofMillis(1000));
        p.setReadFrom("REPLICA_PREFERRED");
        return p;
    }

    @Test
    void supports_sentinel_scheme() {
        assertThat(builder.supports(URI.create("redis-sentinel://host:26379/master"))).isTrue();
    }

    @Test
    void does_not_support_redis_scheme() {
        assertThat(builder.supports(URI.create("redis://localhost:6379"))).isFalse();
    }

    @Test
    void supports_sentinel_scheme_caseInsensitive() {
        // RFC 3986：scheme 不分大小寫，Redis-Sentinel:// 不應被誤判為 Standalone
        assertThat(builder.supports(URI.create("Redis-Sentinel://host:26379/master"))).isTrue();
    }

    @Test
    void builds_factory_with_master_name() {
        assertThat(builder.build(URI.create("redis-sentinel://:pass@host:26379/mymaster"), props())).isNotNull();
    }

    @Test
    void builds_factory_default_sentinel_port_fallback() {
        // URI 不含 port，應使用 26379
        assertThat(builder.build(URI.create("redis-sentinel://:pass@host/mymaster"), props())).isNotNull();
    }

    @Test
    void throws_when_master_name_is_missing() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.build(URI.create("redis-sentinel://host:26379"), props()))
                .withMessageContaining("master name");
    }
}
