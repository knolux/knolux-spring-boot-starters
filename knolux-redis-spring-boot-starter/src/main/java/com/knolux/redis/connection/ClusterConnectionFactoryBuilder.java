package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;

/**
 * Cluster（分片）模式的 {@link LettuceConnectionFactoryBuilder} 實作。
 *
 * <p>支援 {@code redis-cluster://}（明文）與 {@code rediss-cluster://}（TLS）scheme。
 * URI 中的節點是 <strong>種子節點</strong>：客戶端連上後會執行 {@code CLUSTER SHARDS}
 * 取得完整拓撲，其餘節點無須逐一列出。
 *
 * <p>Azure Managed Redis 預設採 OSS clustering policy，必須以此模式連線
 * （初始連接埠 10000，各分片的實際連接埠由拓撲決定且會變動）。
 *
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: rediss-cluster://mycache.eastus.redis.azure.net:10000
 *     cluster:
 *       max-redirects: 5
 *       topology-refresh:
 *         enabled: true
 *         period: 60s
 *         adaptive: true
 * }</pre>
 */
public class ClusterConnectionFactoryBuilder implements LettuceConnectionFactoryBuilder {

    /**
     * 種子節點未指定連接埠時採用的預設值。
     */
    private static final int DEFAULT_PORT = 6379;

    private final LettuceClientConfigurationFactory clientConfigurationFactory;

    /**
     * @param clientConfigurationFactory 客戶端設定工廠，承載 TLS、逾時與憑證來源等橫切設定
     */
    public ClusterConnectionFactoryBuilder(LettuceClientConfigurationFactory clientConfigurationFactory) {
        this.clientConfigurationFactory = clientConfigurationFactory;
    }

    @Override
    public boolean supports(URI uri) {
        // 比對基礎 scheme：涵蓋 redis-cluster:// 與 rediss-cluster://，且大小寫不敏感
        return "redis-cluster".equals(RedisUriUtils.baseScheme(uri));
    }

    @Override
    public LettuceConnectionFactory build(URI uri, KnoluxRedisProperties properties) {
        int db = RedisUriUtils.parseDb(uri.getPath());
        if (db != 0) {
            // Cluster 協定本身只有 DB 0（SELECT 在 cluster 模式下被拒絕）。
            // 靜默忽略會讓使用者以為資料寫進了指定的 DB，實際卻全部落在 DB 0。
            throw new IllegalArgumentException(
                    "Cluster 模式僅支援 DB 0，實際指定為: " + db
                            + "。請將 URL 的 DB 區段移除或改為 /0（例如 redis-cluster://node1:6379）。");
        }

        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;

        RedisClusterConfiguration config = new RedisClusterConfiguration().clusterNode(host, port);
        // maxRedirects 只設在此處：Spring Data 會在建立 client 時把它覆寫到
        // ClusterClientOptions 上（見 LettuceConnectionFactory#getClusterClientOptions），
        // 因此在 ClientOptions 另外設一次是無效的死碼。
        config.setMaxRedirects(properties.getCluster().getMaxRedirects());

        String password = RedisUriUtils.parsePassword(uri);
        if (password != null && !password.isBlank()) {
            config.setPassword(RedisPassword.of(password));
        }

        var clientConfig = clientConfigurationFactory.create(
                uri,
                properties,
                LettuceClientConfigurationFactory.ReadFromPolicy.SKIP_FOR_MASTER,
                base -> ClusterClientOptions.builder(base)
                        .topologyRefreshOptions(topologyRefreshOptions(properties))
                        .build());

        return new LettuceConnectionFactory(config, clientConfig);
    }

    /**
     * 依設定組裝拓撲更新選項。
     *
     * <p>週期性更新處理緩慢的拓撲漂移，適應性更新（{@code MOVED} / {@code ASK} /
     * 連線異常時立即觸發）處理 failover 當下的即時收斂，兩者互補；
     * {@code adaptive} 可單獨關閉，用於連線事件頻繁而不希望放大更新流量的環境。
     *
     * <p>適應性更新採「反向停用」而非「正向啟用」：Lettuce 7 起所有 trigger 預設即為開啟，
     * {@code enableAllAdaptiveRefreshTriggers()} 已被標記 deprecated 且不再有任何作用，
     * 只有 {@code disableAllAdaptiveRefreshTriggers()} 能真正改變行為。
     *
     * <p>{@code enabled=false} 時一併關閉適應性更新——使用者的意圖是「不要更新拓撲」，
     * 若只停掉週期性更新，適應性 trigger 仍會在背景送出 {@code CLUSTER SHARDS}。
     */
    private ClusterTopologyRefreshOptions topologyRefreshOptions(KnoluxRedisProperties properties) {
        KnoluxRedisProperties.TopologyRefresh refresh = properties.getCluster().getTopologyRefresh();
        var options = ClusterTopologyRefreshOptions.builder();
        if (refresh.isEnabled()) {
            options.enablePeriodicRefresh(refresh.getPeriod());
        }
        if (!refresh.isEnabled() || !refresh.isAdaptive()) {
            options.disableAllAdaptiveRefreshTriggers();
        }
        return options.build();
    }
}
