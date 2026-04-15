# knolux-starters

A collection of Spring Boot auto-configuration starters for common infrastructure components.
Each module is independently versioned and published to GitHub Packages.

## Modules

| Module | Artifact | Description |
|--------|----------|-------------|
| [knolux-redis](./knolux-redis/README.md) | `com.knolux:knolux-redis` | Redis starter — Sentinel & Standalone via `REDIS_URL` |

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/knolux/knolux-starters")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.knolux:knolux-redis:1.0.0")
}
```

### Maven

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/knolux/knolux-starters</url>
</repository>

<dependency>
    <groupId>com.knolux</groupId>
    <artifactId>knolux-redis</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Requirements

- Java 17+
- Spring Boot 3.x / 4.x

## Publishing a Release

Releases are triggered by module-scoped git tags:

```bash
git tag knolux-redis/v1.0.1
git push origin knolux-redis/v1.0.1
```

The CI workflow tests and publishes only the tagged module.

## License

MIT
