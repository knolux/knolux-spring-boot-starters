package com.knolux.redis;

import com.knolux.redis.azure.EntraIdCredentialsProviderFactory;
import com.knolux.redis.connection.ClusterConnectionFactoryBuilder;
import com.knolux.redis.connection.LettuceClientConfigurationFactory;
import com.knolux.redis.connection.LettuceConnectionFactoryBuilder;
import com.knolux.redis.connection.SentinelConnectionFactoryBuilder;
import com.knolux.redis.connection.StandaloneConnectionFactoryBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;
import java.util.List;

/**
 * Knolux Redis Spring Boot Starter 的自動設定類別。
 *
 * <p>此類別會在 Spring Boot 應用程式啟動時自動執行，根據 {@code knolux.redis.url}
 * 所指定的 URL scheme，自動建立以下 Spring Bean：
 *
 * <ul>
 *   <li>{@link LettuceConnectionFactory} — Redis 連線工廠，負責管理底層連線池</li>
 *   <li>{@link StringRedisTemplate} — 字串操作模板，key 與 value 均使用 UTF-8 字串序列化</li>
 *   <li>{@link RedisTemplate}{@code <String, Object>} — 通用物件操作模板，
 *       key 與 hash key 使用 {@link StringRedisSerializer}，value 使用預設的 JDK 序列化</li>
 * </ul>
 *
 * <h2>支援的連線模式</h2>
 * <ul>
 *   <li><strong>Standalone（直連）模式</strong> — URL scheme 為 {@code redis://}，
 *       直接連接單一 Redis 節點，適用於開發環境或不需高可用的場景</li>
 *   <li><strong>Sentinel（高可用）模式</strong> — URL scheme 為 {@code redis-sentinel://}，
 *       透過 Redis Sentinel 哨兵機制實現主從自動切換，適用於生產環境</li>
 *   <li><strong>Cluster（分片）模式</strong> — URL scheme 為 {@code redis-cluster://}，
 *       URL 中的節點為種子節點，客戶端連上後自行取得完整拓撲</li>
 * </ul>
 *
 * <p>三者各有對應的 TLS scheme：{@code rediss://}、{@code rediss-sentinel://}、
 * {@code rediss-cluster://}。是否加密<strong>僅由 scheme 決定</strong>，
 * 未列於上述清單的 scheme 一律 fail-fast，不會被任何模式靜默收下。
 *
 * <h2>Azure Managed Redis + Entra ID</h2>
 * <p>設定 {@code knolux.redis.azure.entra-id.enabled=true} 後，憑證改由 Microsoft Entra ID
 * 的 token 提供，設定檔中不需要任何密碼；token 會在到期前自動更新，並對既有連線重新驗證。
 * 此模式需要 classpath 上有 {@code redis.clients.authentication:redis-authx-entraid}
 * （本 starter 以 {@code compileOnly} 引入，未啟用者不必背負其傳遞依賴）。
 *
 * <h2>Bean 覆寫機制</h2>
 * <p>所有 Bean 均標注 {@link ConditionalOnMissingBean}，
 * 若使用者在自己的 {@code @Configuration} 類別中定義了相同型別的 Bean，
 * 自動設定的 Bean 將不會被建立，讓使用者得以完全自訂連線行為。
 * 憑證來源同樣可覆寫：自訂 {@link RedisCredentialsProviderFactory} Bean 會取代內建的
 * Entra ID 實作，並一樣享有憑證輪替時的自動重新驗證。
 *
 * <h2>設定範例</h2>
 * <pre>{@code
 * # application.yml
 * knolux:
 *   redis:
 *     url: redis://:password@localhost:6379
 *     timeout-ms: 1000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 *
 * <pre>{@code
 * # Azure Managed Redis（OSS clustering policy）+ 系統指派受控身分
 * knolux:
 *   redis:
 *     url: rediss-cluster://mycache.eastus.redis.azure.net:10000
 *     timeout-ms: 3000ms
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: SYSTEM_ASSIGNED
 * }</pre>
 *
 * @see KnoluxRedisProperties
 * @see KnoluxRedisHealthIndicator
 * @see EntraIdCredentialsProviderFactory
 */
@AutoConfiguration
@EnableConfigurationProperties(value = KnoluxRedisProperties.class)
public class KnoluxRedisAutoConfiguration {

    private final KnoluxRedisProperties properties;

    public KnoluxRedisAutoConfiguration(KnoluxRedisProperties properties) {
        this.properties = properties;
    }

    // ─────────────────────────────────────────────
    // 憑證來源（Azure Entra ID）
    // ─────────────────────────────────────────────

    /**
     * 建立以 Microsoft Entra ID token 為憑證來源的
     * {@link RedisCredentialsProviderFactory}，僅在
     * {@code knolux.redis.azure.entra-id.enabled=true} 時建立。
     *
     * <p>此 Bean 以 {@code destroyMethod = "close"} 註冊：底層的 token 提供者持有背景更新排程
     * 與非 daemon 執行緒池，其生命週期明載「由外部管理」，不關閉會讓 JVM 無法正常結束。
     *
     * <p>條件為 {@link ConditionalOnMissingBean}{@code (RedisCredentialsProviderFactory.class)}
     * 而非本型別，讓使用者能以任何自訂的憑證來源（例如其他 IdP）整個取代它——
     * {@link #redisConnectionFactory} 取用的是介面型別，替換後一樣會啟用憑證輪替。
     *
     * @return Entra ID 憑證提供者工廠
     * @throws IllegalArgumentException 連線 URI 或身分設定不符前置條件時（訊息帶實際值與修正方式）
     * @see EntraIdCredentialsProviderFactory#forConnection
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "knolux.redis.azure.entra-id", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(RedisCredentialsProviderFactory.class)
    public EntraIdCredentialsProviderFactory knoluxRedisEntraIdCredentialsProviderFactory() {
        return EntraIdCredentialsProviderFactory.forConnection(URI.create(requireUrl()), properties);
    }

    // ─────────────────────────────────────────────
    // 連線工廠
    // ─────────────────────────────────────────────

    /**
     * 建立並設定 {@link LettuceConnectionFactory}（Redis 連線工廠）。
     *
     * <p>此方法解析 {@code knolux.redis.url} 的 URI scheme，並委派給對應的
     * {@link LettuceConnectionFactoryBuilder} 實作（策略模式）建立連線工廠。
     *
     * @param credentialsProviderFactory 動態憑證來源；不存在時沿用 URL 中的靜態密碼
     * @return 設定完成的 {@link LettuceConnectionFactory}，可直接被 {@link RedisTemplate} 使用
     * @throws IllegalArgumentException 若 {@code knolux.redis.url} 未設定、為空白字串或 scheme 不受支援
     */
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory(
            ObjectProvider<RedisCredentialsProviderFactory> credentialsProviderFactory) {
        URI uri = URI.create(requireUrl());

        // 客戶端層級設定（TLS、逾時、readFrom、憑證輪替）三種模式共用，
        // 由 LettuceClientConfigurationFactory 集中組裝；各 builder 只負責自己的 RedisConfiguration
        var clientConfigurationFactory =
                new LettuceClientConfigurationFactory(credentialsProviderFactory.getIfAvailable());

        // 新增連線模式＝新增一個 builder 實作並加入此清單，不需改動下方的分派邏輯（OCP）。
        // supports() 以 base scheme 嚴格比對，彼此互斥，故順序不影響結果。
        List<LettuceConnectionFactoryBuilder> builders = List.of(
                new SentinelConnectionFactoryBuilder(clientConfigurationFactory),
                new ClusterConnectionFactoryBuilder(clientConfigurationFactory),
                new StandaloneConnectionFactoryBuilder(clientConfigurationFactory)
        );

        return builders.stream()
                .filter(b -> b.supports(uri))
                .findFirst()
                .map(b -> b.build(uri, properties))
                // 刻意不設 catch-all fallback：未知 scheme 若被某個 builder 收下，
                // 使用者可能在以為加密的情況下連上明文連線
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支援的 Redis URI scheme: " + uri.getScheme()
                                + "。支援的 scheme：redis:// 或 rediss://（Standalone）、"
                                + "redis-sentinel:// 或 rediss-sentinel://（Sentinel）、"
                                + "redis-cluster:// 或 rediss-cluster://（Cluster）。"
                                + "rediss 系列為 TLS 連線。"));
    }

    /**
     * 取得必填的連線 URL。
     *
     * <p>憑證提供者工廠與連線工廠都需要它，且兩者都可能是第一個被建立的 Bean，
     * 因此檢查集中於此，確保無論裝配順序如何都得到同一則訊息。
     */
    private String requireUrl() {
        String url = properties.getUrl();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("""
                    knolux.redis.url is required.
                      Standalone: knolux.redis.url=redis://:password@host:port
                      Sentinel:   knolux.redis.url=redis-sentinel://:password@host:port/mastername
                      Cluster:    knolux.redis.url=redis-cluster://:password@seed-host:port
                      TLS 連線請改用對應的 rediss:// / rediss-sentinel:// / rediss-cluster:// scheme。
                    """
            );
        }
        return url;
    }

    // ─────────────────────────────────────────────
    // Templates
    // ─────────────────────────────────────────────

    /**
     * 建立 {@link StringRedisTemplate}，key 與 value 均使用 UTF-8 字串序列化。
     *
     * <p>序列化結果可直接透過 Redis CLI 讀取，便於除錯。
     * 若應用程式已自訂 {@link StringRedisTemplate} Bean，此 Bean 不會被建立。
     *
     * @param factory Redis 連線工廠
     * @return 設定完成的 {@link StringRedisTemplate}
     */
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /**
     * 建立通用 {@link RedisTemplate}{@code <String, Object>}，key 與 hash key 使用
     * {@link StringRedisSerializer}，value 使用預設的 JDK 序列化。
     *
     * <p>使用 {@code name = "redisTemplate"} 條件而非型別匹配，
     * 是為了避免 {@link StringRedisTemplate}（{@link RedisTemplate} 子類別）干擾型別判斷。
     * 若需自訂序列化（如 Jackson JSON），可定義同名 Bean 覆寫此設定。
     *
     * @param factory Redis 連線工廠
     * @return 設定完成的 {@link RedisTemplate}{@code <String, Object>}
     */
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        return template;
    }

    // ─────────────────────────────────────────────
    // 健康指標（需要 Spring Boot Actuator）
    // ─────────────────────────────────────────────

    /**
     * 建立 Redis 連線健康指標 Bean，Bean 名稱為 {@code knoluxRedis}。
     *
     * <p>僅在 {@code spring-boot-starter-actuator} 存在於 classpath 時建立，
     * 不強制要求使用者引入 Actuator 依賴。使用者可定義同名 Bean 覆寫此行為。
     *
     * @param template 用於發送 PING 的 StringRedisTemplate
     * @return KnoluxRedisHealthIndicator 實例
     */
    @Bean(name = "knoluxRedis")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(name = "knoluxRedis")
    public KnoluxRedisHealthIndicator knoluxRedisHealthIndicator(StringRedisTemplate template) {
        return new KnoluxRedisHealthIndicator(template);
    }

}
