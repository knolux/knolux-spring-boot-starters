package com.knolux.redis.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import redis.clients.authentication.core.TokenAuthConfig;
import redis.clients.authentication.entraid.AzureTokenAuthConfigBuilder;
import redis.clients.authentication.entraid.EntraIDTokenAuthConfigBuilder;
import redis.clients.authentication.entraid.ManagedIdentityInfo.UserManagedIdentityType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 把 {@link KnoluxRedisEntraIdProperties} 轉換為上游的 {@link TokenAuthConfig}。
 *
 * <p><strong>本類別是整個 starter 中唯一參考
 * {@code redis.clients.authentication.entraid.*} 與 {@code com.azure.identity.*} 的地方。</strong>
 * 這是刻意的隔離：該依賴以 {@code compileOnly} 引入（不傳遞給下游），
 * 未啟用 Entra ID 的使用者 classpath 上不會有這些型別，
 * 唯有觸碰本類別才會需要它們。是否具備該依賴的檢查由
 * {@link EntraIdCredentialsProviderFactory} 在載入本類別<em>之前</em>完成。
 *
 * <p>建立 {@link TokenAuthConfig} <strong>不會產生任何網路流量</strong>：
 * 上游的 {@code IdentityProviderConfig} 只保存一個 {@code Supplier}，
 * 真正的 identity provider 要到取得第一顆 token 時才建立。設定錯誤因此能在
 * 應用程式啟動階段就被攔下，而不是等到第一次連線失敗。
 *
 * @see EntraIdCredentialsProviderFactory
 */
public final class EntraIdTokenAuthConfigFactory {

    private EntraIdTokenAuthConfigFactory() {
    }

    /**
     * 依身分來源組裝 {@link TokenAuthConfig}。
     *
     * @param properties Entra ID 設定
     * @return 可交給 {@code TokenBasedRedisCredentialsProvider} 的 token 驗證設定
     * @throws IllegalArgumentException 設定不完整或超出有效範圍時（訊息帶實際值與修正方式）
     */
    public static TokenAuthConfig create(KnoluxRedisEntraIdProperties properties) {
        validateCommon(properties);

        return switch (properties.getIdentity()) {
            case SYSTEM_ASSIGNED -> entraIdBuilder(properties)
                    .systemAssignedManagedIdentity()
                    .build();
            case USER_ASSIGNED -> entraIdBuilder(properties)
                    .userAssignedManagedIdentity(
                            toUserManagedIdentityType(properties.getUserAssignedIdType()),
                            requireUserAssignedId(properties))
                    .build();
            case SERVICE_PRINCIPAL -> {
                validateServicePrincipal(properties);
                yield entraIdBuilder(properties)
                        .clientId(properties.getClientId())
                        .secret(properties.getClientSecret())
                        .authority(properties.getAuthority())
                        .build();
            }
            case DEFAULT_CHAIN -> AzureTokenAuthConfigBuilder.builder()
                    .defaultAzureCredential(new DefaultAzureCredentialBuilder().build())
                    .scopes(properties.getScopes())
                    .tokenRequestExecTimeoutInMs(millis(properties.getTokenRequestTimeout()))
                    .expirationRefreshRatio(properties.getExpirationRefreshRatio())
                    .lowerRefreshBoundMillis(millis(properties.getLowerRefreshBound()))
                    .build();
        };
    }

    /**
     * 把本 starter 的識別碼型別對映到上游列舉。
     *
     * <p>兩份列舉刻意分開（見 {@link KnoluxRedisEntraIdProperties.UserAssignedIdType} 的說明），
     * 因此需要這道對映。switch 不寫 {@code default}：任一側新增值時會編譯失敗，
     * 強迫開發者正面處理，而不是靜默落到某個預設分支。
     *
     * @param type 本 starter 的識別碼型別
     * @return 上游對應的 {@link UserManagedIdentityType}
     */
    static UserManagedIdentityType toUserManagedIdentityType(KnoluxRedisEntraIdProperties.UserAssignedIdType type) {
        return switch (type) {
            case CLIENT_ID -> UserManagedIdentityType.CLIENT_ID;
            case OBJECT_ID -> UserManagedIdentityType.OBJECT_ID;
            case RESOURCE_ID -> UserManagedIdentityType.RESOURCE_ID;
        };
    }

    /**
     * 建立三種 msal4j 身分來源（受控身分／Service Principal）共用的 builder 前半段。
     */
    private static EntraIDTokenAuthConfigBuilder entraIdBuilder(KnoluxRedisEntraIdProperties properties) {
        return EntraIDTokenAuthConfigBuilder.builder()
                .scopes(properties.getScopes())
                .tokenRequestExecTimeoutInMs(millis(properties.getTokenRequestTimeout()))
                .expirationRefreshRatio(properties.getExpirationRefreshRatio())
                .lowerRefreshBoundMillis(millis(properties.getLowerRefreshBound()));
    }

    // ─────────────────────────────────────────────
    // 設定驗證
    // ─────────────────────────────────────────────

    private static void validateCommon(KnoluxRedisEntraIdProperties properties) {
        if (properties.getIdentity() == null) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.identity 不得為空。可用值："
                            + List.of(KnoluxRedisEntraIdProperties.IdentityType.values()));
        }
        if (properties.getUserAssignedIdType() == null) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.user-assigned-id-type 不得為空。可用值："
                            + List.of(KnoluxRedisEntraIdProperties.UserAssignedIdType.values()));
        }

        Set<String> scopes = properties.getScopes();
        if (scopes == null || scopes.isEmpty()) {
            // 上游取 scope 的方式是 scopes.iterator().next()，空集合會在背景更新執行緒
            // 以毫無線索的 NoSuchElementException 爆掉，因此在此提前擋下
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.scopes 不得為空。一般情況維持預設值即可："
                            + KnoluxRedisEntraIdProperties.DEFAULT_SCOPE
                            + "（僅主權雲需改為其對應的資源 URI）。");
        }

        float ratio = properties.getExpirationRefreshRatio();
        if (!(ratio > 0f) || ratio > 1f) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.expiration-refresh-ratio 必須落在 (0, 1] 區間，實際為: " + ratio
                            + "。此值代表「token 生命週期走完多少比例時開始更新」，預設 0.75。");
        }

        Duration tokenRequestTimeout = properties.getTokenRequestTimeout();
        if (tokenRequestTimeout == null || tokenRequestTimeout.isZero() || tokenRequestTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.token-request-timeout 必須為正值，實際為: " + tokenRequestTimeout
                            + "（例如 2s）。");
        }

        Duration lowerRefreshBound = properties.getLowerRefreshBound();
        if (lowerRefreshBound == null || lowerRefreshBound.isNegative()) {
            throw new IllegalArgumentException(
                    "knolux.redis.azure.entra-id.lower-refresh-bound 不得為負值，實際為: " + lowerRefreshBound
                            + "（例如 2m；0 代表不設下限，只依 expiration-refresh-ratio 決定更新時機）。");
        }
    }

    private static String requireUserAssignedId(KnoluxRedisEntraIdProperties properties) {
        String id = properties.getUserAssignedId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "identity=USER_ASSIGNED 需設定 knolux.redis.azure.entra-id.user-assigned-id，"
                            + "並以 user-assigned-id-type 指明其型別（CLIENT_ID / OBJECT_ID / RESOURCE_ID，"
                            + "目前為 " + properties.getUserAssignedIdType() + "）。"
                            + "若要改用執行環境繫結的身分，請設定 identity=SYSTEM_ASSIGNED。");
        }
        return id;
    }

    private static void validateServicePrincipal(KnoluxRedisEntraIdProperties properties) {
        List<String> missing = new ArrayList<>();
        if (isBlank(properties.getClientId())) {
            missing.add("client-id");
        }
        if (isBlank(properties.getClientSecret())) {
            missing.add("client-secret");
        }
        if (isBlank(properties.getAuthority())) {
            missing.add("authority");
        }
        if (!missing.isEmpty()) {
            // 逐項指名而非只說「設定不完整」：Service Principal 的三個欄位常分散在
            // 不同的環境變數來源，只報一個缺項會讓使用者反覆重啟才補齊
            throw new IllegalArgumentException(
                    "identity=SERVICE_PRINCIPAL 尚缺下列設定: " + missing
                            + "。請補上 knolux.redis.azure.entra-id.client-id（應用程式 ID）、"
                            + "client-secret（用戶端密碼）與 "
                            + "authority（https://login.microsoftonline.com/<tenant-id>）。"
                            + "若執行環境在 Azure 內，改用 identity=SYSTEM_ASSIGNED 可完全免除這些密碼。");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 轉換為上游 API 所需的毫秒整數，逾 {@code int} 範圍時 fail-fast。
     */
    private static int millis(Duration duration) {
        long value = duration.toMillis();
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Entra ID 的時間設定換算為毫秒後超出 int 範圍: " + duration
                            + "。上游 API 以 int 毫秒表示，請改用較小的值。");
        }
        return (int) value;
    }
}
