package com.knolux.redis.azure;

import com.knolux.redis.azure.KnoluxRedisEntraIdProperties.IdentityType;
import com.knolux.redis.azure.KnoluxRedisEntraIdProperties.UserAssignedIdType;
import org.junit.jupiter.api.Test;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.entraid.AzureIdentityProviderConfig;
import redis.clients.authentication.entraid.EntraIDIdentityProviderConfig;
import redis.clients.authentication.entraid.ManagedIdentityInfo.UserManagedIdentityType;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link EntraIdTokenAuthConfigFactory} 的單元測試。
 *
 * <p>建立 {@link TokenAuthConfig} 不會觸發任何網路行為：上游的
 * {@code IdentityProviderConfig} 只保存一個 {@code Supplier}，
 * 真正的 identity provider 要到 {@code getProvider()} 才建立，
 * 因此四種身分來源都能在沒有 Azure 環境的情況下完整驗證。
 */
class EntraIdTokenAuthConfigFactoryTest {

    private KnoluxRedisEntraIdProperties props() {
        KnoluxRedisEntraIdProperties p = new KnoluxRedisEntraIdProperties();
        p.setEnabled(true);
        return p;
    }

    private KnoluxRedisEntraIdProperties servicePrincipalProps() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.SERVICE_PRINCIPAL);
        p.setClientId("00000000-0000-0000-0000-000000000001");
        p.setClientSecret("s3cr3t");
        p.setAuthority("https://login.microsoftonline.com/tenant-id");
        return p;
    }

    // ── 四種身分來源 ───────────────────────────────────────────────────────────

    @Test
    void systemAssigned_buildsEntraIdProviderConfig() {
        TokenAuthConfig config = EntraIdTokenAuthConfigFactory.create(props());
        assertThat(config.getIdentityProviderConfig()).isInstanceOf(EntraIDIdentityProviderConfig.class);
    }

    @Test
    void userAssigned_buildsEntraIdProviderConfig() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.USER_ASSIGNED);
        p.setUserAssignedId("00000000-0000-0000-0000-000000000002");

        TokenAuthConfig config = EntraIdTokenAuthConfigFactory.create(p);
        assertThat(config.getIdentityProviderConfig()).isInstanceOf(EntraIDIdentityProviderConfig.class);
    }

    @Test
    void defaultChain_buildsAzureProviderConfig() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.DEFAULT_CHAIN);

        TokenAuthConfig config = EntraIdTokenAuthConfigFactory.create(p);
        // DEFAULT_CHAIN 走的是 azure-identity 的 DefaultAzureCredential，
        // 與其餘三者的 msal4j 路徑不同，型別差異即是最直接的證據
        assertThat(config.getIdentityProviderConfig()).isInstanceOf(AzureIdentityProviderConfig.class);
    }

    @Test
    void servicePrincipal_buildsEntraIdProviderConfig() {
        TokenAuthConfig config = EntraIdTokenAuthConfigFactory.create(servicePrincipalProps());
        // 上游 builder 在「同時設定 ServicePrincipal 與 ManagedIdentity」或「兩者皆未設定」時
        // 都會拋 RedisEntraIDException，因此建置成功本身即證明只走了 Service Principal 路徑
        assertThat(config.getIdentityProviderConfig()).isInstanceOf(EntraIDIdentityProviderConfig.class);
    }

    // ── 身分來源必填欄位（FR-044 / FR-045）─────────────────────────────────────

    @Test
    void userAssigned_missingId_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.USER_ASSIGNED);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("user-assigned-id")
                .withMessageContaining("CLIENT_ID")
                .withMessageContaining("OBJECT_ID")
                .withMessageContaining("RESOURCE_ID");
    }

    @Test
    void userAssigned_blankId_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.USER_ASSIGNED);
        p.setUserAssignedId("   ");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("user-assigned-id");
    }

    @Test
    void servicePrincipal_missingClientId_fails() {
        KnoluxRedisEntraIdProperties p = servicePrincipalProps();
        p.setClientId(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("client-id");
    }

    @Test
    void servicePrincipal_missingClientSecret_fails() {
        KnoluxRedisEntraIdProperties p = servicePrincipalProps();
        p.setClientSecret(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("client-secret");
    }

    @Test
    void servicePrincipal_missingAuthority_fails() {
        KnoluxRedisEntraIdProperties p = servicePrincipalProps();
        p.setAuthority("");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("authority");
    }

    @Test
    void servicePrincipal_missingEverything_namesAllThree() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.SERVICE_PRINCIPAL);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("client-id")
                .withMessageContaining("client-secret")
                .withMessageContaining("authority");
    }

    // ── token 更新參數對映（FR-036）────────────────────────────────────────────

    @Test
    void mapsTokenManagerConfigFromProperties() {
        KnoluxRedisEntraIdProperties p = props();
        p.setTokenRequestTimeout(Duration.ofMillis(1500));
        p.setExpirationRefreshRatio(0.5f);
        p.setLowerRefreshBound(Duration.ofSeconds(90));

        var tokenManagerConfig = EntraIdTokenAuthConfigFactory.create(p).getTokenManagerConfig();

        assertThat(tokenManagerConfig.getTokenRequestExecTimeoutInMs()).isEqualTo(1500);
        assertThat(tokenManagerConfig.getExpirationRefreshRatio()).isEqualTo(0.5f);
        assertThat(tokenManagerConfig.getLowerRefreshBoundMillis()).isEqualTo(90_000);
    }

    @Test
    void defaultsMatchDocumentedValues() {
        var tokenManagerConfig = EntraIdTokenAuthConfigFactory.create(props()).getTokenManagerConfig();

        assertThat(tokenManagerConfig.getTokenRequestExecTimeoutInMs()).isEqualTo(2_000);
        assertThat(tokenManagerConfig.getExpirationRefreshRatio()).isEqualTo(0.75f);
        assertThat(tokenManagerConfig.getLowerRefreshBoundMillis()).isEqualTo(120_000);
    }

    @Test
    void mapsTokenManagerConfigForDefaultChainToo() {
        KnoluxRedisEntraIdProperties p = props();
        p.setIdentity(IdentityType.DEFAULT_CHAIN);
        p.setTokenRequestTimeout(Duration.ofMillis(1500));
        p.setExpirationRefreshRatio(0.5f);
        p.setLowerRefreshBound(Duration.ofSeconds(90));

        var tokenManagerConfig = EntraIdTokenAuthConfigFactory.create(p).getTokenManagerConfig();

        assertThat(tokenManagerConfig.getTokenRequestExecTimeoutInMs()).isEqualTo(1500);
        assertThat(tokenManagerConfig.getExpirationRefreshRatio()).isEqualTo(0.5f);
        assertThat(tokenManagerConfig.getLowerRefreshBoundMillis()).isEqualTo(90_000);
    }

    // ── 共通參數驗證 ───────────────────────────────────────────────────────────

    @Test
    void emptyScopes_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setScopes(Set.of());

        // 上游取 scope 的方式是 scopes.iterator().next()，空集合會在背景執行緒
        // 以 NoSuchElementException 爆掉且訊息毫無線索，故在此提前擋下
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("scopes")
                .withMessageContaining(KnoluxRedisEntraIdProperties.DEFAULT_SCOPE);
    }

    @Test
    void nullScopes_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setScopes(null);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("scopes");
    }

    @Test
    void nonPositiveExpirationRefreshRatio_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setExpirationRefreshRatio(0f);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("expiration-refresh-ratio");
    }

    @Test
    void expirationRefreshRatioAboveOne_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setExpirationRefreshRatio(1.5f);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("expiration-refresh-ratio")
                .withMessageContaining("1.5");
    }

    @Test
    void expirationRefreshRatioOfOne_isAccepted() {
        KnoluxRedisEntraIdProperties p = props();
        p.setExpirationRefreshRatio(1.0f);

        // 1.0 代表「到期才更新」，安全邊界很窄但仍是合法設定
        assertThat(EntraIdTokenAuthConfigFactory.create(p)).isNotNull();
    }

    @Test
    void nonPositiveTokenRequestTimeout_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setTokenRequestTimeout(Duration.ZERO);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("token-request-timeout");
    }

    @Test
    void negativeLowerRefreshBound_fails() {
        KnoluxRedisEntraIdProperties p = props();
        p.setLowerRefreshBound(Duration.ofSeconds(-1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> EntraIdTokenAuthConfigFactory.create(p))
                .withMessageContaining("lower-refresh-bound");
    }

    @Test
    void zeroLowerRefreshBound_isAccepted() {
        KnoluxRedisEntraIdProperties p = props();
        p.setLowerRefreshBound(Duration.ZERO);

        // 0 是上游明載的「不設下限，只依 ratio 決定更新時機」
        assertThat(EntraIdTokenAuthConfigFactory.create(p)).isNotNull();
    }

    // ── 識別碼型別對映 ─────────────────────────────────────────────────────────

    @Test
    void mapsEveryUserAssignedIdType() {
        // 本 starter 刻意複製一份列舉以隔離選用依賴，兩邊的對映必須逐一對得上；
        // 上游新增值時此測試會因 switch 不完整而編譯失敗，正是預期的提醒
        assertThat(EntraIdTokenAuthConfigFactory.toUserManagedIdentityType(UserAssignedIdType.CLIENT_ID))
                .isEqualTo(UserManagedIdentityType.CLIENT_ID);
        assertThat(EntraIdTokenAuthConfigFactory.toUserManagedIdentityType(UserAssignedIdType.OBJECT_ID))
                .isEqualTo(UserManagedIdentityType.OBJECT_ID);
        assertThat(EntraIdTokenAuthConfigFactory.toUserManagedIdentityType(UserAssignedIdType.RESOURCE_ID))
                .isEqualTo(UserManagedIdentityType.RESOURCE_ID);
    }

    @Test
    void coversEveryUserAssignedIdType() {
        // 若日後在 KnoluxRedisEntraIdProperties 新增列舉值卻忘了對映，此測試會失敗
        for (UserAssignedIdType type : UserAssignedIdType.values()) {
            assertThat(EntraIdTokenAuthConfigFactory.toUserManagedIdentityType(type)).isNotNull();
        }
    }
}
