package com.knolux.redis.azure;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Azure Microsoft Entra ID token 驗證的設定屬性，前綴為 {@code knolux.redis.azure.entra-id}。
 *
 * <p>啟用後，本 starter 不再從連線 URL 讀取密碼，而是向 Entra ID 取得 access token
 * 作為 Redis 的認證憑證。token 會過期，因此底層會在背景更新並對**現存連線**重新 AUTH
 * （由 {@code ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS} 驅動）。
 *
 * <p>使用者名稱由函式庫自 JWT 的 {@code oid} claim 取出，
 * <strong>不需要也無法</strong>在此設定 username。
 *
 * <h2>前置條件</h2>
 * <ol>
 *   <li>Azure Managed Redis 已啟用 Microsoft Entra 驗證</li>
 *   <li>目標身分（受控身分 / Service Principal）已在 Redis 上被指派資料存取權限</li>
 *   <li>連線 URL 使用 TLS scheme（{@code rediss://} 或 {@code rediss-cluster://}）——
 *       bearer token 不得走明文連線</li>
 *   <li>classpath 上有 {@code redis.clients.authentication:redis-authx-entraid}
 *       （本 starter 以 {@code compileOnly} 引入，需自行加入）</li>
 * </ol>
 *
 * <h2>設定範例</h2>
 *
 * <h3>AKS 上的 system-assigned 受控身分</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: rediss-cluster://mycache.eastus.redis.azure.net:10000
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: SYSTEM_ASSIGNED
 * }</pre>
 *
 * <h3>user-assigned 受控身分</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: USER_ASSIGNED
 *         user-assigned-id-type: CLIENT_ID
 *         user-assigned-id: 00000000-0000-0000-0000-000000000000
 * }</pre>
 *
 * <h3>本機開發（沿用 az login 的憑證鏈）</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: DEFAULT_CHAIN
 * }</pre>
 *
 * <h3>非 Azure 環境的 Service Principal</h3>
 * <pre>{@code
 * knolux:
 *   redis:
 *     azure:
 *       entra-id:
 *         enabled: true
 *         identity: SERVICE_PRINCIPAL
 *         client-id: ${AZURE_CLIENT_ID}
 *         client-secret: ${AZURE_CLIENT_SECRET}
 *         authority: https://login.microsoftonline.com/${AZURE_TENANT_ID}
 * }</pre>
 *
 * @see EntraIdCredentialsProviderFactory
 * @see EntraIdTokenAuthConfigFactory
 */
@Getter
@Setter
public class KnoluxRedisEntraIdProperties {

    /**
     * Entra ID 預設的 Redis 資源 scope。
     *
     * <p>等價別名為 {@code acca5fbb-b7e4-4009-81f1-37e38fd66d78/.default}
     * （Azure Redis 的第一方應用程式 ID）。
     */
    public static final String DEFAULT_SCOPE = "https://redis.azure.com/.default";

    /**
     * 是否啟用 Entra ID token 驗證，預設 {@code false}。
     *
     * <p>啟用時會進行一連串啟動階段檢查（scheme 必須為 TLS、URL 不得同時帶密碼、
     * 選用依賴必須在 classpath 上……），任一不符即拋出例外而非靜默降級為無驗證連線。
     */
    private boolean enabled = false;

    /**
     * 身分來源，預設 {@link IdentityType#SYSTEM_ASSIGNED}。
     */
    private IdentityType identity = IdentityType.SYSTEM_ASSIGNED;

    /**
     * 指認 user-assigned 受控身分所用的識別碼型別，
     * 僅在 {@link IdentityType#USER_ASSIGNED} 時生效，預設 {@link UserAssignedIdType#CLIENT_ID}。
     */
    private UserAssignedIdType userAssignedIdType = UserAssignedIdType.CLIENT_ID;

    /**
     * user-assigned 受控身分的識別碼，型別由 {@link #userAssignedIdType} 決定。
     * 僅在 {@link IdentityType#USER_ASSIGNED} 時為必填。
     */
    private String userAssignedId;

    /**
     * Service Principal 的應用程式（client）ID，僅在 {@link IdentityType#SERVICE_PRINCIPAL} 時為必填。
     *
     * <p><strong>注意：</strong>受控身分（{@code SYSTEM_ASSIGNED} / {@code USER_ASSIGNED}）
     * <em>不需要</em>設定此欄位。上游文件範例在受控身分路徑也一併呼叫 {@code clientId()}，
     * 但其 {@code build()} 驗證邏輯只在 Service Principal 路徑用到此值。
     */
    private String clientId;

    /**
     * Service Principal 的用戶端密碼，僅在 {@link IdentityType#SERVICE_PRINCIPAL} 時為必填。
     *
     * <p>建議以環境變數注入而非寫入設定檔。
     */
    private String clientSecret;

    /**
     * Entra ID 的授權端點，格式為 {@code https://login.microsoftonline.com/<tenant-id>}，
     * 僅在 {@link IdentityType#SERVICE_PRINCIPAL} 時為必填。
     */
    private String authority;

    /**
     * 要求 token 時使用的 scope 集合，預設為 {@link #DEFAULT_SCOPE}。
     *
     * <p>除非使用主權雲（如 Azure China / Government）需改用其對應資源 URI，
     * 否則不需要覆寫。
     */
    private Set<String> scopes = new LinkedHashSet<>(Set.of(DEFAULT_SCOPE));

    /**
     * 單次 token 請求的逾時時間，預設 {@code 2s}。
     *
     * <p>此值必須小於 {@code knolux.redis.timeout-ms}：Lettuce 在連線建立後才要求憑證，
     * 憑證取得若超出連線建立逾時，連線本身就會失敗。違反時記錄 {@code WARN}。
     */
    private Duration tokenRequestTimeout = Duration.ofSeconds(2);

    /**
     * token 更新時機相對於其存活時間的比例，預設 {@code 0.75}
     * （即在 token 生命週期走完 75% 時開始更新）。
     *
     * <p>調低可提高安全邊界但增加 Entra ID 的請求量；有效範圍為 {@code (0, 1]}。
     */
    private float expirationRefreshRatio = 0.75f;

    /**
     * token 更新的時間下限，預設 {@code 2m}。
     *
     * <p>即使 {@link #expirationRefreshRatio} 算出的時點更晚，
     * 也會確保在 token 到期前至少此段時間啟動更新，避免短效 token 來不及輪替。
     */
    private Duration lowerRefreshBound = Duration.ofMinutes(2);

    /**
     * Entra ID 的身分來源類型。
     */
    public enum IdentityType {

        /**
         * 執行環境（VM / App Service / AKS Pod）所繫結的系統指派受控身分。
         * 無須任何額外設定，是 Azure 內部署的建議選項。
         */
        SYSTEM_ASSIGNED,

        /**
         * 使用者指派的受控身分，需搭配 {@link #userAssignedIdType} 與 {@code userAssignedId}。
         * 適用多個服務共用同一身分的情境。
         */
        USER_ASSIGNED,

        /**
         * Azure SDK 的 {@code DefaultAzureCredential} 憑證鏈
         * （依序嘗試環境變數、受控身分、Azure CLI 登入……）。
         * 適合讓本機開發與正式環境共用同一份設定。
         */
        DEFAULT_CHAIN,

        /**
         * Service Principal，需設定 {@code clientId}、{@code clientSecret} 與 {@code authority}。
         * 適用於非 Azure 環境（地端 / 其他雲）。
         */
        SERVICE_PRINCIPAL
    }

    /**
     * 指認 user-assigned 受控身分的識別碼型別。
     *
     * <p>刻意複製一份而非直接引用上游列舉：所有
     * {@code redis.clients.authentication.entraid.*} 的型別參考必須集中在
     * {@link EntraIdTokenAuthConfigFactory}，才能讓未啟用此功能的使用者
     * 不必把選用依賴放上 classpath。
     */
    public enum UserAssignedIdType {

        /** 受控身分的應用程式（client）ID。 */
        CLIENT_ID,

        /** 受控身分在 Entra ID 目錄中的物件（principal）ID。 */
        OBJECT_ID,

        /** 受控身分的 Azure 資源完整 ID（{@code /subscriptions/.../userAssignedIdentities/...}）。 */
        RESOURCE_ID
    }
}
