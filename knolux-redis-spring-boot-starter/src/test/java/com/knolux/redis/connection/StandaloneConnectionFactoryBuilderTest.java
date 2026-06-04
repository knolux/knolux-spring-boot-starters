package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StandaloneConnectionFactoryBuilderTest {
    private final StandaloneConnectionFactoryBuilder builder = new StandaloneConnectionFactoryBuilder();

    private KnoluxRedisProperties props(String readFrom) {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofMillis(1000));
        p.setReadFrom(readFrom);
        return p;
    }

    @Test
    void supports_redis_scheme() {
        assertThat(builder.supports(URI.create("redis://localhost:6379"))).isTrue();
    }

    @Test
    void does_not_support_sentinel_scheme() {
        assertThat(builder.supports(URI.create("redis-sentinel://localhost:26379/master"))).isFalse();
    }

    @Test
    void does_not_support_rediss_scheme() {
        // rediss://（TLS）尚未支援，不應被 standalone 接受並靜默以明文連線；
        // 應由 Auto-Configuration 的 orElseThrow 明確拒絕
        assertThat(builder.supports(URI.create("rediss://localhost:6379"))).isFalse();
    }

    @Test
    void supports_redis_scheme_caseInsensitive() {
        // RFC 3986：scheme 不分大小寫
        assertThat(builder.supports(URI.create("REDIS://localhost:6379"))).isTrue();
    }

    @Test
    void builds_factory_with_default_port() {
        assertThat(builder.build(URI.create("redis://localhost"), props("MASTER")).getPort()).isEqualTo(6379);
    }

    @Test
    void builds_factory_with_explicit_port() {
        assertThat(builder.build(URI.create("redis://localhost:6380"), props("MASTER")).getPort()).isEqualTo(6380);
    }

    @Test
    void builds_factory_with_db() {
        assertThat(builder.build(URI.create("redis://localhost:6379/3"), props("MASTER")).getDatabase()).isEqualTo(3);
    }

    @Test
    void builds_factory_without_password() {
        assertThat(builder.build(URI.create("redis://localhost:6379"), props("MASTER"))).isNotNull();
    }

    @Test
    void builds_factory_with_password() {
        assertThat(builder.build(URI.create("redis://:secret@localhost:6379"), props("MASTER"))).isNotNull();
    }

    @Test
    void builds_factory_with_replica_preferred_read_from() {
        assertThat(builder.build(URI.create("redis://localhost:6379"), props("REPLICA_PREFERRED"))).isNotNull();
    }

    @Test
    void builds_factory_with_upstream_read_from() {
        // UPSTREAM 為 Lettuce 6+ 的 MASTER 別名，應與 MASTER 行為一致（不啟動 topology refresh）
        assertThat(builder.build(URI.create("redis://localhost:6379"), props("UPSTREAM"))).isNotNull();
    }

    @Test
    void builds_factory_with_lowest_latency_read_from() {
        assertThat(builder.build(URI.create("redis://localhost:6379"), props("LOWEST_LATENCY"))).isNotNull();
    }

    @Test
    void builds_factory_with_subnet_read_from() {
        // 複合語法 — Lettuce ReadFrom.valueOf 直接解析
        assertThat(builder.build(URI.create("redis://localhost:6379"), props("subnet:192.168.0.0/16"))).isNotNull();
    }
}
