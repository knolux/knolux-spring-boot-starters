/**
 * Redis 連線工廠建構器策略套件。
 *
 * <p>本套件包含 {@link com.knolux.redis.connection.LettuceConnectionFactoryBuilder}
 * 策略介面及其兩個內建實作，負責依 URI scheme 選擇對應的 Lettuce 連線工廠建立策略。
 *
 * <h2>內建實作</h2>
 * <ul>
 *   <li>{@link com.knolux.redis.connection.StandaloneConnectionFactoryBuilder}
 *       — 處理 {@code redis://} scheme</li>
 *   <li>{@link com.knolux.redis.connection.SentinelConnectionFactoryBuilder}
 *       — 處理 {@code redis-sentinel://} scheme</li>
 * </ul>
 *
 * <h2>擴充新模式</h2>
 * <p>新增連線模式（例如 Redis Cluster）時，僅須：
 * <ol>
 *   <li>實作 {@link com.knolux.redis.connection.LettuceConnectionFactoryBuilder} 介面</li>
 *   <li>將實例加入 {@link com.knolux.redis.KnoluxRedisAutoConfiguration} 的
 *       {@code BUILDERS} 清單</li>
 * </ol>
 * 不須修改 Auto-Configuration 的 Bean 邏輯（符合開閉原則 OCP）。
 *
 * @see com.knolux.redis.KnoluxRedisAutoConfiguration
 */
package com.knolux.redis.connection;
