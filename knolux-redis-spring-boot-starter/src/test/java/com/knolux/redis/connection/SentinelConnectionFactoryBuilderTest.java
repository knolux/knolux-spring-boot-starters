package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SentinelConnectionFactoryBuilderTest {
    private final SentinelConnectionFactoryBuilder builder =
            new SentinelConnectionFactoryBuilder(new LettuceClientConfigurationFactory(null));

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
    void supports_tls_sentinel_scheme() {
        assertThat(builder.supports(URI.create("rediss-sentinel://host:26379/master"))).isTrue();
    }

    @Test
    void plaintext_scheme_does_not_enable_ssl() {
        assertThat(builder.build(URI.create("redis-sentinel://host:26379/mymaster"), props())
                .getClientConfiguration().isUseSsl()).isFalse();
    }

    @Test
    void tls_scheme_enables_ssl() {
        assertThat(builder.build(URI.create("rediss-sentinel://host:26379/mymaster"), props())
                .getClientConfiguration().isUseSsl()).isTrue();
    }

    @Test
    void read_from_is_set_even_for_master() {
        // 既有行為必須保留：Sentinel 未設定 readFrom 就不會進入 MasterReplica 模式，
        // 與 Standalone 的「MASTER 時略過」刻意不同
        KnoluxRedisProperties p = props();
        p.setReadFrom("MASTER");
        assertThat(builder.build(URI.create("redis-sentinel://host:26379/mymaster"), p)
                .getClientConfiguration().getReadFrom()).isPresent();
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
    void builds_factory_default_sentinel_port_is_26379() {
        // 強化上面的斷言：確認 fallback port 確實為 26379（而非僅 isNotNull）
        var sentinel = builder.build(URI.create("redis-sentinel://:pass@host/mymaster"), props())
                .getSentinelConfiguration().getSentinels().iterator().next();
        assertThat(sentinel.getPort()).isEqualTo(26379);
    }

    @Test
    void throws_when_master_name_is_missing() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.build(URI.create("redis-sentinel://host:26379"), props()))
                .withMessageContaining("master name");
    }
}
