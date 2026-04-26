package com.knolux.s3;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

/**
 * Knolux S3 Spring Boot Starter 的自動設定類別。
 *
 * <p>根據 {@code knolux.s3.*} 設定建立以下 Bean：
 * <ul>
 *   <li>{@link KnoluxS3ClientFactory} — 管理並快取 {@code S3AsyncClient} 實例</li>
 *   <li>{@link KnoluxS3Template} — 提供靜態 / 動態 / 進階三層 S3 操作 API</li>
 * </ul>
 *
 * <p>動態模式需注入 {@link KnoluxS3Properties} 作為 fallback 預設值：
 * <pre>{@code
 * @Autowired KnoluxS3Properties s3Properties;
 * @Autowired KnoluxS3Template s3Template;
 *
 * KnoluxS3OperationSpec spec = KnoluxS3OperationSpec.builder()
 *     .endpoint(payload.getEndpoint())
 *     .bucket(payload.getBucket())
 *     .key(payload.getKey())
 *     .accessKey(payload.getSecretId())
 *     .secretKey(payload.getSecretKey())
 *     .build()
 *     .mergeDefaults(s3Properties);
 *
 * s3Template.download(spec, AsyncResponseTransformer.toBytes());
 * }</pre>
 *
 * @see KnoluxS3Properties
 * @see KnoluxS3OperationSpec
 */
@AutoConfiguration
@EnableConfigurationProperties(KnoluxS3Properties.class)
public class KnoluxS3AutoConfiguration {

    // destroyMethod = "close" 確保 Spring 容器關閉時釋放 Netty 執行緒與連線池
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(S3ClientProvider.class)
    public KnoluxS3ClientFactory knoluxS3ClientFactory(KnoluxS3Properties props) {
        return new KnoluxS3ClientFactory(KnoluxS3ConnectionDetails.of(props));
    }

    /**
     * 供 {@link KnoluxS3Template} 執行 CompletableFuture 回呼的 Executor。
     *
     * <p>當 {@code spring.threads.virtual.enabled=true} 時使用 Java 25 Virtual Thread per task executor，
     * 否則退回至 {@link ForkJoinPool#commonPool()}。可由使用者以相同 Bean 名稱覆寫。
     *
     * @param env Spring Environment（讀取 spring.threads.virtual.enabled 屬性）
     * @return Virtual Thread executor 或 common pool
     */
    @Bean(name = "knoluxS3Executor", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "knoluxS3Executor")
    public Executor knoluxS3Executor(Environment env) {
        boolean vtEnabled = env.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
        if (vtEnabled) {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
        return ForkJoinPool.commonPool();
    }

    @Bean
    @ConditionalOnMissingBean
    public KnoluxS3Template knoluxS3Template(
            S3ClientProvider clientProvider,
            @Qualifier("knoluxS3Executor") Executor knoluxS3Executor) {
        return new KnoluxS3Template(clientProvider, knoluxS3Executor);
    }
}
