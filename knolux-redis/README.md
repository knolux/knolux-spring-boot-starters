# knolux-redis

Spring Boot Starter for Redis — supports both **Sentinel (HA)** and **Standalone** modes via a single `REDIS_URL` environment variable.

## Features

- Auto-detects mode from `REDIS_URL` scheme (`redis://` or `redis-sentinel://`)
- Sentinel mode — automatic Master discovery and failover
- Standalone mode — direct connection (ideal for local development)
- Configurable read strategy (`REPLICA_PREFERRED`, `MASTER`, `REPLICA`, `ANY`)
- Health indicator via Spring Boot Actuator
- Zero-code change between development and production environments

---

## Requirements

- Java 17+
- Spring Boot 3.x / 4.x

---

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.knolux:knolux-redis:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-redis</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## Configuration

Set the `REDIS_URL` environment variable. The starter auto-selects the connection mode based on the URL scheme.

### Standalone mode (`redis://`)

Used for local development or when connecting through a load balancer (e.g., HAProxy).

```
REDIS_URL=redis://:your-password@redis.example.com:6379
```

### Sentinel mode (`redis-sentinel://`)

Used inside Kubernetes clusters for high availability.

```
REDIS_URL=redis-sentinel://:your-password@redis.redis-cache.svc.cluster.local:26379/mymaster
```

### `application.yml`

```yaml
knolux:
  redis:
    url: ${REDIS_URL}
    timeout-ms: 1000ms         # optional, default: 1000ms
    read-from: REPLICA_PREFERRED  # optional, default: REPLICA_PREFERRED
```

### Configuration Properties

| Property                  | Type       | Default             | Description               |
|---------------------------|------------|---------------------|---------------------------|
| `knolux.redis.url`        | `String`   | *(required)*        | Redis connection URL      |
| `knolux.redis.timeout-ms` | `Duration` | `1000ms`            | Command timeout           |
| `knolux.redis.read-from`  | `String`   | `REPLICA_PREFERRED` | Read strategy (see below) |

### Read Strategies

| Value               | Description                                              |
|---------------------|----------------------------------------------------------|
| `REPLICA_PREFERRED` | Prefer Replica nodes; fall back to Master if unavailable |
| `MASTER`            | Always read from Master                                  |
| `REPLICA`           | Always read from Replica                                 |
| `ANY`               | Any available node                                       |

---

## Usage

The starter auto-configures `StringRedisTemplate` and `RedisTemplate<String, Object>`. Inject them directly into your services.

### StringRedisTemplate (String values)

```java
@Service
public class CacheService {

    private final StringRedisTemplate redis;

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void set(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    public String get(String key) {
        return redis.opsForValue().get(key);
    }

    public void delete(String key) {
        redis.delete(key);
    }
}
```

### RedisTemplate (Object values)

```java
@Service
public class SessionService {

    private final RedisTemplate<String, Object> redis;

    public SessionService(RedisTemplate<String, Object> redis) {
        this.redis = redis;
    }

    public void saveSession(String userId, Object session) {
        redis.opsForValue().set("session:" + userId, session, Duration.ofHours(1));
    }

    public Object getSession(String userId) {
        return redis.opsForValue().get("session:" + userId);
    }
}
```

### Key Prefix Convention

Redis has no built-in namespace isolation. Use prefixes to separate data by service:

```java
// Recommended prefix pattern: <service>:<entity>:<id>
redis.opsForValue().set("backend-prod:session:user123", token);
redis.opsForValue().set("backend-prod:cache:product456", data);
```

---

## Kubernetes Deployment

### Create a Secret

```bash
kubectl create secret generic app-redis-secret \
  --from-literal=REDIS_URL='redis-sentinel://:your-password@redis.redis-cache.svc.cluster.local:26379/mymaster' \
  -n your-namespace
```

### Deployment YAML

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: your-app
spec:
  template:
    spec:
      containers:
        - name: your-app
          image: your-image
          env:
            - name: REDIS_URL
              valueFrom:
                secretKeyRef:
                  name: app-redis-secret
                  key: REDIS_URL
```

---

## Local Development

Create a `.env` file in your project root (add to `.gitignore`):

```bash
# .env
REDIS_URL=redis://:your-password@redis.example.com:6379
```

`.env.example` (commit this to version control):

```bash
# .env.example — copy to .env and fill in your password
REDIS_URL=redis://:your-password@redis.example.com:6379
```

---

## Health Check

When `spring-boot-starter-actuator` is present, the health endpoint automatically includes Redis status:

```bash
GET /actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "knoluxRedis": {
      "status": "UP",
      "details": {
        "ping": "PONG"
      }
    }
  }
}
```

---

## Overriding Auto-Configuration

Use `@ConditionalOnMissingBean` — define your own bean to override the defaults:

```java
@Configuration
public class CustomRedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // your custom configuration
        return new LettuceConnectionFactory(...);
    }
}
```

---

## Connection URL Reference

| Environment       | URL Format                                       |
|-------------------|--------------------------------------------------|
| Local development | `redis://:password@redis.example.com:6379`       |
| K8s (Sentinel)    | `redis-sentinel://:password@host:26379/mymaster` |
| No password       | `redis://localhost:6379`                         |
| Specific database | `redis://:password@localhost:6379/3`             |

---

## Running Tests

```bash
# From repo root
./gradlew :knolux-redis:test
```

| Test Class                                  | Coverage                             |
|---------------------------------------------|--------------------------------------|
| `KnoluxRedisPropertiesTest`                 | Properties binding                   |
| `KnoluxRedisAutoConfigurationTest`          | Auto-configuration logic, all modes  |
| `KnoluxRedisHealthIndicatorTest`            | Health indicator UP / DOWN           |
| `KnoluxRedisStandaloneIntegrationTest`      | Standalone mode end-to-end (Docker)  |
| `KnoluxRedisSentinelIntegrationTest`        | Sentinel mode end-to-end (Docker)    |
| `KnoluxRedisHealthIndicatorIntegrationTest` | Health indicator with real Redis     |

---

## License

MIT
