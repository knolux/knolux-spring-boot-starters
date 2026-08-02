package com.knolux.redis.connection;

import com.knolux.redis.KnoluxRedisProperties;
import com.knolux.redis.RedisUriUtils;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.RedisCredentialsProviderFactory;

import java.net.URI;
import java.util.function.UnaryOperator;

/**
 * 組裝 {@link LettuceClientConfiguration} 的共用工廠。
 *
 * <p>Standalone / Sentinel / Cluster 三種連線模式的「客戶端層級」設定完全相同——
 * TLS、指令逾時、讀取策略、{@link ClientOptions}、憑證提供者——差異只在於各自的
 * {@code RedisConfiguration}（節點座標、master name、slot 重導……）。
 * 將客戶端層級的組裝集中於此，可讓 TLS 與 token 驗證這類橫切設定只實作與測試一次，
 * 也避免日後新增模式時漏掉其中一項（SRP）。
 *
 * <p>本類別是 Entra ID token 驗證接上 Spring Data Redis 的唯一接縫：
 * 建構時若拿到 {@link RedisCredentialsProviderFactory}，除了註冊該工廠之外，
 * 還會把 {@link ClientOptions.ReauthenticateBehavior#ON_NEW_CREDENTIALS} 一併打開。
 *
 * @see LettuceConnectionFactoryBuilder
 */
@Slf4j
public class LettuceClientConfigurationFactory {

    /**
     * 憑證提供者工廠，{@code null} 代表使用連線 URL 中的靜態密碼（或不需驗證）。
     */
    private final RedisCredentialsProviderFactory credentialsProviderFactory;

    /**
     * @param credentialsProviderFactory 動態憑證提供者工廠，可為 {@code null}
     */
    public LettuceClientConfigurationFactory(RedisCredentialsProviderFactory credentialsProviderFactory) {
        this.credentialsProviderFactory = credentialsProviderFactory;
    }

    /**
     * 以「純 MASTER / UPSTREAM 時不設定 readFrom」的策略建立客戶端設定。
     *
     * @param uri        連線 URI，僅用於判斷是否啟用 TLS
     * @param properties 外部化設定
     * @return 組裝完成的 {@link LettuceClientConfiguration}
     */
    public LettuceClientConfiguration create(URI uri, KnoluxRedisProperties properties) {
        return create(uri, properties, ReadFromPolicy.SKIP_FOR_MASTER);
    }

    /**
     * 建立客戶端設定。
     *
     * @param uri        連線 URI，僅用於判斷是否啟用 TLS
     * @param properties 外部化設定
     * @param readFromPolicy 讀取策略的套用方式
     * @return 組裝完成的 {@link LettuceClientConfiguration}
     */
    public LettuceClientConfiguration create(URI uri,
                                             KnoluxRedisProperties properties,
                                             ReadFromPolicy readFromPolicy) {
        return create(uri, properties, readFromPolicy, UnaryOperator.identity());
    }

    /**
     * 建立客戶端設定，並允許呼叫端改寫組裝好的 {@link ClientOptions}。
     *
     * <p>{@code clientOptionsCustomizer} 存在的理由是 Cluster 模式：它必須是
     * {@link io.lettuce.core.cluster.ClusterClientOptions} 才能承載 topology refresh 設定，
     * 但 TLS 與憑證輪替等設定又必須與其他模式共用。讓呼叫端「加工」而非「另外組一份」，
     * 可避免 Cluster 漏掉日後新增的共用設定。
     *
     * @param uri            連線 URI，僅用於判斷是否啟用 TLS
     * @param properties     外部化設定
     * @param readFromPolicy 讀取策略的套用方式
     * @param clientOptionsCustomizer 對共用 {@link ClientOptions} 的加工函式
     * @return 組裝完成的 {@link LettuceClientConfiguration}
     */
    public LettuceClientConfiguration create(URI uri,
                                             KnoluxRedisProperties properties,
                                             ReadFromPolicy readFromPolicy,
                                             UnaryOperator<ClientOptions> clientOptionsCustomizer) {
        var builder = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeoutMs())
                .clientOptions(clientOptionsCustomizer.apply(clientOptions()));

        // TLS 完全由 scheme 決定：明文 scheme 下即使設了 ssl.*，也不會意外開啟加密，
        // 反之亦然。單一來源避免 scheme 與旗標互相矛盾。
        if (RedisUriUtils.isTls(uri)) {
            applySsl(builder, properties.getSsl());
        }

        String readFrom = properties.getReadFrom();
        // 純 MASTER / UPSTREAM 時不設定 readFrom，Lettuce 不會啟動 topology refresh；
        // 其他策略（含 LOWEST_LATENCY、ANY、ANY_REPLICA、subnet:、regex: 等）啟用讀寫分離。
        // Sentinel 模式例外：不設定 readFrom 就不會進入 MasterReplica 模式，故一律套用。
        if (readFromPolicy == ReadFromPolicy.ALWAYS || !RedisUriUtils.isMasterOnly(readFrom)) {
            builder.readFrom(RedisUriUtils.parseReadFrom(readFrom));
        }

        if (credentialsProviderFactory != null) {
            builder.redisCredentialsProviderFactory(credentialsProviderFactory);
        }

        return builder.build();
    }

    /**
     * 套用 TLS 設定。
     */
    private void applySsl(LettuceClientConfiguration.LettuceClientConfigurationBuilder builder,
                          KnoluxRedisProperties.Ssl ssl) {
        SslVerifyMode verifyMode = ssl.getVerifyPeer();
        if (verifyMode == SslVerifyMode.NONE) {
            log.warn("knolux.redis.ssl.verify-peer=NONE：已停用伺服器憑證驗證，連線可被中間人攔截。" +
                    "此設定僅適用於自簽憑證的測試環境，正式環境請改用 FULL（或在憑證主機名不符時暫用 CA）。");
        }

        var sslBuilder = builder.useSsl().verifyPeer(verifyMode);
        if (ssl.isStartTls()) {
            sslBuilder.startTls();
        }
        sslBuilder.and();
    }

    /**
     * 建立共用的 {@link ClientOptions}。
     *
     * <p>{@code timeoutOptions(TimeoutOptions.enabled())} 是複製 Spring Data Redis 的
     * builder 預設值——本方法的結果一律會被顯式設回 builder，而顯式設定會整個覆蓋掉該預設，
     * 不補回來會連帶關閉指令逾時。
     *
     * <p>有憑證提供者時才打開
     * {@link ClientOptions.ReauthenticateBehavior#ON_NEW_CREDENTIALS}：
     * 它是 token 驗證能否長期運作的關鍵——少了它，背景更新取得的新 token 只會套用到
     * 「之後新建」的連線，連線池中既有的連線會在 token 到期時集體被 Redis 拒絕。
     * 反過來說，靜態密碼的使用者不需要也不應被改變重新驗證的行為。
     */
    private ClientOptions clientOptions() {
        var builder = ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled());
        if (credentialsProviderFactory != null) {
            builder.reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS);
        }
        return builder.build();
    }

    /**
     * 讀取策略的套用方式。
     */
    public enum ReadFromPolicy {

        /**
         * 讀取策略為 {@code MASTER} / {@code UPSTREAM} 時不設定 {@code readFrom}，
         * 讓 Lettuce 省下 topology refresh 的背景連線。適用 Standalone 與 Cluster。
         */
        SKIP_FOR_MASTER,

        /**
         * 一律設定 {@code readFrom}。Sentinel 模式必須如此：
         * 未設定時 Lettuce 不會建立 MasterReplica 連線，讀寫分離會靜默失效。
         */
        ALWAYS
    }
}
