# Redis 模組架構圖

本文件包含 `knolux-redis-spring-boot-starter` 模組的 UML 類別圖與關鍵流程時序圖。

---

## 類別圖

```mermaid
classDiagram
    class KnoluxRedisAutoConfiguration {
        -KnoluxRedisProperties properties
        +redisConnectionFactory() LettuceConnectionFactory
        +stringRedisTemplate(RedisConnectionFactory) StringRedisTemplate
        +redisTemplate(RedisConnectionFactory) RedisTemplate
        -buildSentinelFactory(URI) LettuceConnectionFactory
        -buildStandaloneFactory(URI) LettuceConnectionFactory
    }

    class KnoluxRedisProperties {
        +String url
        +Duration timeoutMs
        +String readFrom
    }

    class RedisUriUtils {
        +parsePassword(URI) String
        +parseDb(String) int
        +parseReadFrom(String) ReadFrom
    }

    class KnoluxRedisHealthIndicator {
        -StringRedisTemplate stringRedisTemplate
        +health() Health
    }

    class LettuceConnectionFactory {
    }

    class RedisConnectionFactory {
        <<interface>>
    }

    class StringRedisTemplate {
    }

    class RedisTemplate {
    }

    KnoluxRedisAutoConfiguration --> KnoluxRedisProperties : depends on
    KnoluxRedisAutoConfiguration --> RedisUriUtils : uses
    KnoluxRedisAutoConfiguration ..> LettuceConnectionFactory : creates
    KnoluxRedisAutoConfiguration ..> StringRedisTemplate : creates
    KnoluxRedisAutoConfiguration ..> RedisTemplate : creates
    KnoluxRedisAutoConfiguration ..> KnoluxRedisHealthIndicator : creates
    KnoluxRedisHealthIndicator --> StringRedisTemplate : uses
    LettuceConnectionFactory ..|> RedisConnectionFactory : implements
```

---

## 時序圖 1 — 應用程式啟動（Standalone 模式）

```mermaid
sequenceDiagram
    participant Spring as Spring Context
    participant AutoConf as KnoluxRedisAutoConfiguration
    participant Props as KnoluxRedisProperties
    participant Utils as RedisUriUtils
    participant Factory as LettuceConnectionFactory
    participant SRT as StringRedisTemplate
    participant RT as RedisTemplate

    Spring ->> AutoConf: trigger auto-configuration
    AutoConf ->> Props: read url / timeoutMs / readFrom
    Props -->> AutoConf: configuration values

    AutoConf ->> Utils: parsePassword(uri)
    Utils -->> AutoConf: password
    AutoConf ->> Utils: parseDb(path)
    Utils -->> AutoConf: dbIndex
    AutoConf ->> Utils: parseReadFrom(readFrom)
    Utils -->> AutoConf: ReadFrom enum

    AutoConf ->> Factory: buildStandaloneFactory(uri)
    Factory -->> AutoConf: LettuceConnectionFactory (standalone)

    AutoConf ->> SRT: new StringRedisTemplate(factory)
    SRT -->> AutoConf: StringRedisTemplate bean

    AutoConf ->> RT: new RedisTemplate(factory)
    RT -->> AutoConf: RedisTemplate bean

    AutoConf -->> Spring: beans registered
```

---

## 時序圖 2 — 健康檢查（GET /actuator/health）

```mermaid
sequenceDiagram
    participant Client as HTTP Client
    participant Actuator as Spring Actuator
    participant HI as KnoluxRedisHealthIndicator
    participant SRT as StringRedisTemplate
    participant Conn as RedisConnection
    participant Redis as Redis Server

    Client ->> Actuator: GET /actuator/health
    Actuator ->> HI: health()

    HI ->> SRT: getConnectionFactory()
    SRT -->> HI: RedisConnectionFactory

    HI ->> Conn: getConnection()
    Conn -->> HI: RedisConnection

    HI ->> Redis: PING
    Redis -->> Conn: PONG
    Conn -->> HI: "PONG"

    HI -->> Actuator: Health.up() with detail ping=PONG
    Actuator -->> Client: 200 OK { "status": "UP", "details": { "ping": "PONG" } }
```

> **注意**：`KnoluxRedisHealthIndicator` 目前使用 `@Component` 註解，在 Spring Boot Starter 函式庫情境中，此元件可能因
> component scan 路徑未涵蓋函式庫套件而無法被自動偵測，導致上述健康檢查流程靜默失效。建議改由
`KnoluxRedisAutoConfiguration`
> 以條件式 `@Bean` 管理此元件。詳見代碼審查報告 R2。
