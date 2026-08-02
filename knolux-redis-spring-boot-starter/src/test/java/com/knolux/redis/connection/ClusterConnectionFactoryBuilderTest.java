package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisNode;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link ClusterConnectionFactoryBuilder} 的單元測試。
 */
class ClusterConnectionFactoryBuilderTest {

    private final ClusterConnectionFactoryBuilder builder =
            new ClusterConnectionFactoryBuilder(new LettuceClientConfigurationFactory(null));

    private KnoluxRedisProperties props() {
        KnoluxRedisProperties p = new KnoluxRedisProperties();
        p.setTimeoutMs(Duration.ofMillis(1000));
        p.setReadFrom("MASTER");
        return p;
    }

    private ClusterTopologyRefreshOptions topologyRefreshOptions(KnoluxRedisProperties p, String url) {
        ClusterClientOptions options = (ClusterClientOptions) builder.build(URI.create(url), p)
                .getClientConfiguration().getClientOptions().orElseThrow();
        return options.getTopologyRefreshOptions();
    }

    // ── scheme 比對 ───────────────────────────────────────────────────────────

    @Test
    void supports_cluster_scheme() {
        assertThat(builder.supports(URI.create("redis-cluster://node1:6379"))).isTrue();
    }

    @Test
    void supports_tls_cluster_scheme() {
        assertThat(builder.supports(URI.create("rediss-cluster://node1:10000"))).isTrue();
    }

    @Test
    void supports_cluster_scheme_caseInsensitive() {
        // RFC 3986：scheme 不分大小寫
        assertThat(builder.supports(URI.create("Redis-Cluster://node1:6379"))).isTrue();
    }

    @Test
    void does_not_support_standalone_scheme() {
        assertThat(builder.supports(URI.create("redis://localhost:6379"))).isFalse();
    }

    @Test
    void does_not_support_sentinel_scheme() {
        assertThat(builder.supports(URI.create("redis-sentinel://host:26379/master"))).isFalse();
    }

    // ── 節點座標 ──────────────────────────────────────────────────────────────

    @Test
    void builds_seed_node_from_uri() {
        var config = builder.build(URI.create("redis-cluster://node1:7000"), props()).getClusterConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.getClusterNodes())
                .extracting(RedisNode::getHost, RedisNode::getPort)
                .containsExactly(org.assertj.core.api.Assertions.tuple("node1", 7000));
    }

    @Test
    void uses_default_port_when_absent() {
        var config = builder.build(URI.create("redis-cluster://node1"), props()).getClusterConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.getClusterNodes()).first().extracting(RedisNode::getPort).isEqualTo(6379);
    }

    @Test
    void builds_with_password() {
        assertThat(builder.build(URI.create("redis-cluster://:secret@node1:6379"), props())).isNotNull();
    }

    // ── DB 限制 ───────────────────────────────────────────────────────────────

    @Test
    void accepts_explicit_db_zero() {
        // /0 與省略路徑等價，不應被誤判為「指定了非 0 DB」
        assertThat(builder.build(URI.create("redis-cluster://node1:6379/0"), props())).isNotNull();
    }

    @Test
    void rejects_non_zero_db() {
        // Cluster 模式只有 DB 0；靜默忽略會讓資料寫進非預期的位置
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder.build(URI.create("redis-cluster://node1:6379/3"), props()))
                .withMessageContaining("DB 0")
                .withMessageContaining("3");
    }

    // ── maxRedirects ──────────────────────────────────────────────────────────

    @Test
    void propagates_max_redirects() {
        KnoluxRedisProperties p = props();
        p.getCluster().setMaxRedirects(9);
        var config = builder.build(URI.create("redis-cluster://node1:6379"), p).getClusterConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.getMaxRedirects()).isEqualTo(9);
    }

    // ── TLS ───────────────────────────────────────────────────────────────────

    @Test
    void plaintext_scheme_does_not_enable_ssl() {
        assertThat(builder.build(URI.create("redis-cluster://node1:6379"), props())
                .getClientConfiguration().isUseSsl()).isFalse();
    }

    @Test
    void tls_scheme_enables_ssl() {
        assertThat(builder.build(URI.create("rediss-cluster://node1:10000"), props())
                .getClientConfiguration().isUseSsl()).isTrue();
    }

    // ── topology refresh ──────────────────────────────────────────────────────

    @Test
    void client_options_are_cluster_aware() {
        // 必須是 ClusterClientOptions，否則 topology refresh 設定會被 Spring Data 丟棄
        assertThat(builder.build(URI.create("redis-cluster://node1:6379"), props())
                .getClientConfiguration().getClientOptions())
                .get().isInstanceOf(ClusterClientOptions.class);
    }

    @Test
    void topology_refresh_is_enabled_by_default() {
        // 與 Lettuce 本身的預設（停用）相反：AMR 的分片節點埠會隨 failover 變動
        var refresh = topologyRefreshOptions(props(), "redis-cluster://node1:6379");
        assertThat(refresh.isPeriodicRefreshEnabled()).isTrue();
        assertThat(refresh.getRefreshPeriod()).isEqualTo(Duration.ofSeconds(60));
        assertThat(refresh.getAdaptiveRefreshTriggers()).isNotEmpty();
    }

    @Test
    void topology_refresh_period_is_configurable() {
        KnoluxRedisProperties p = props();
        p.getCluster().getTopologyRefresh().setPeriod(Duration.ofSeconds(5));
        assertThat(topologyRefreshOptions(p, "redis-cluster://node1:6379").getRefreshPeriod())
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void adaptive_refresh_can_be_disabled_independently() {
        KnoluxRedisProperties p = props();
        p.getCluster().getTopologyRefresh().setAdaptive(false);
        var refresh = topologyRefreshOptions(p, "redis-cluster://node1:6379");
        assertThat(refresh.isPeriodicRefreshEnabled()).isTrue();
        assertThat(refresh.getAdaptiveRefreshTriggers()).isEmpty();
    }

    @Test
    void topology_refresh_can_be_disabled_entirely() {
        KnoluxRedisProperties p = props();
        p.getCluster().getTopologyRefresh().setEnabled(false);
        var refresh = topologyRefreshOptions(p, "redis-cluster://node1:6379");
        assertThat(refresh.isPeriodicRefreshEnabled()).isFalse();
        assertThat(refresh.getAdaptiveRefreshTriggers()).isEmpty();
    }
}
