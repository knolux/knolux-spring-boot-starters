package com.knolux.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.signer.Signer;
import software.amazon.awssdk.http.SdkHttpFullRequest;

/**
 * 自訂 AWS 簽章器，專為「Nginx 反向代理 + SeaweedFS」架構設計。
 *
 * <h2>問題背景</h2>
 * <p>當 SeaweedFS S3 API 前方掛有 Nginx 反向代理，且 Nginx 以路徑前綴（例如 {@code /cluster/s3}）
 * 進行路由時，AWS SDK 計算簽章用的路徑（含前綴）與 SeaweedFS 實際收到的路徑（不含前綴）不一致，
 * 導致簽章驗證失敗（HTTP 403）。
 *
 * <h2>解決方式</h2>
 * <ol>
 *   <li>對移除前綴後的短路徑計算簽章（SeaweedFS 看到的路徑）</li>
 *   <li>將計算好的 {@code Authorization} / {@code X-Amz-*} Header 複製回原始長路徑請求</li>
 *   <li>實際 HTTP 請求仍攜帶完整路徑，讓 Nginx 正確路由</li>
 * </ol>
 *
 * <h2>關於 Deprecated SPI</h2>
 * <p>本類別實作已標記為 {@code @Deprecated} 的 {@link Signer} 介面（同步簽章 SPI）。
 * 在 AWS SDK v2 的 async client pipeline 中，簽章步驟（{@code SigningStage}）
 * 在非同步 I/O 前以同步方式執行，因此此介面對 {@code S3AsyncClient} 依然有效。
 * AWS SDK v2.21+ 已引入 {@code HttpSigner} SPI 作為替代，但
 * 「簽短路徑、傳長路徑」的情境尚無標準的 {@code ExecutionInterceptor} 實作方式，
 * 遷移工作已列入待辦。
 *
 * <p>此類別僅在 {@code knolux.s3.remove-path-prefix=true} 時被注入。
 *
 * @see KnoluxS3ClientFactory
 */
@Slf4j
@SuppressWarnings("deprecation")
@RequiredArgsConstructor
public class KnoluxNoPathPrefixSigner implements Signer {

    private final Aws4Signer delegate = Aws4Signer.create();

    /**
     * 要從 URL 路徑中移除的前綴，例如 {@code /cluster/s3}。
     * 不可為 {@code null} 或空白（由 {@link KnoluxS3ClientFactory} 於注入前驗證）。
     */
    private final String prefixToRemove;

    /**
     * 以移除路徑前綴後的短路徑計算簽章，並將簽章 Header 複製回原始長路徑請求。
     *
     * <p>若路徑不以 {@code prefixToRemove} 為開頭（例如未套用 Nginx 前綴的路徑），
     * 則直接以原始路徑計算簽章，行為與標準簽章器相同。
     *
     * @param request             原始 HTTP 請求（含完整長路徑）
     * @param executionAttributes 包含 AWS 憑證、Region、服務名稱等簽章所需屬性
     * @return 含正確 {@code Authorization} / {@code X-Amz-*} Header 且保留原始長路徑的請求
     */
    @Override
    public SdkHttpFullRequest sign(SdkHttpFullRequest request, ExecutionAttributes executionAttributes) {
        // 前綴為空時直接委派，避免 startsWith("") 恆為 true 的誤判
        if (prefixToRemove == null || prefixToRemove.isBlank()) {
            return delegate.sign(request, executionAttributes);
        }

        String originalPath = request.encodedPath();

        // 計算簽章用的短路徑（移除 Nginx 路由前綴）
        String pathForSigning = originalPath;
        if (originalPath.startsWith(prefixToRemove)) {
            pathForSigning = originalPath.substring(prefixToRemove.length());
            if (!pathForSigning.startsWith("/")) {
                pathForSigning = "/" + pathForSigning;
            }
        }

        // 以短路徑計算簽章
        SdkHttpFullRequest signedShortPathRequest = delegate.sign(
                request.toBuilder().encodedPath(pathForSigning).build(),
                executionAttributes
        );

        // 將簽章 Header 複製回原始長路徑請求（保留 Nginx 路由所需的完整路徑）
        SdkHttpFullRequest.Builder result = request.toBuilder();
        signedShortPathRequest.headers().forEach((name, values) -> {
            if (name.equalsIgnoreCase("Authorization") || name.toLowerCase().startsWith("x-amz-")) {
                result.putHeader(name, values);
            }
        });

        return result.build();
    }
}
