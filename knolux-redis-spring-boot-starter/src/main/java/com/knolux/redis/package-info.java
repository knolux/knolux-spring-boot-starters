/**
 * Knolux Redis Spring Boot Starter 核心套件。
 *
 * <h2>模組概覽</h2>
 * <p>本 Starter 提供 Redis 連線的零配置自動設定，支援以下連線模式：
 * <ul>
 *   <li><strong>Standalone（直連）</strong> — URL scheme {@code redis://}，
 *       適用開發與單節點部署</li>
 *   <li><strong>Sentinel（高可用）</strong> — URL scheme {@code redis-sentinel://}，
 *       適用生產環境主從自動切換</li>
 * </ul>
 *
 * <h2>核心類別</h2>
 * <ul>
 *   <li>{@link com.knolux.redis.KnoluxRedisAutoConfiguration} — Spring Boot 自動設定進入點，
 *       使用 {@link com.knolux.redis.connection.LettuceConnectionFactoryBuilder}
 *       策略模式選擇連線工廠（符合 OCP）</li>
 *   <li>{@link com.knolux.redis.KnoluxRedisProperties} — 外部化設定屬性，
 *       前綴 {@code knolux.redis}</li>
 *   <li>{@link com.knolux.redis.KnoluxRedisHealthIndicator} — Spring Boot Actuator
 *       健康端點整合，Bean 名稱 {@code knoluxRedis}（需要 Actuator 在 classpath）</li>
 *   <li>{@link com.knolux.redis.RedisUriUtils} — URI 解析工具</li>
 * </ul>
 *
 * <h2>Bean 依賴關係</h2>
 * <pre>
 * KnoluxRedisProperties (knolux.redis.*)
 *     └─► KnoluxRedisAutoConfiguration
 *             ├─► LettuceConnectionFactory (RedisConnectionFactory)
 *             ├─► StringRedisTemplate
 *             ├─► RedisTemplate&lt;String, Object&gt;
 *             └─► KnoluxRedisHealthIndicator [需要 spring-boot-starter-actuator]
 * </pre>
 *
 * <h2>快速開始</h2>
 * <pre>{@code
 * # application.yml — Standalone 模式
 * knolux:
 *   redis:
 *     url: redis://:password@localhost:6379
 *     timeout-ms: 1000ms
 *     read-from: MASTER
 *
 * # Sentinel 模式
 * knolux:
 *   redis:
 *     url: redis-sentinel://:password@sentinel-host:26379/mymaster
 *     timeout-ms: 3000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 *
 * <h2>支援的 readFrom 策略</h2>
 * <ul>
 *   <li>{@code MASTER} — 全部讀寫走 Master（純單節點時不啟動 topology refresh）</li>
 *   <li>{@code REPLICA} — 強制只從 Replica 讀取</li>
 *   <li>{@code REPLICA_PREFERRED}（預設）— 優先 Replica，失敗回退至 Master</li>
 *   <li>{@code ANY} — 任意可用節點</li>
 * </ul>
 *
 * @see com.knolux.redis.connection
 */
package com.knolux.redis;
