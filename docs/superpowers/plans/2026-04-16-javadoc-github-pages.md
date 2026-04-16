# Javadoc + GitHub Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate Traditional Chinese Javadoc for all public classes in `:knolux-redis-spring-boot-starter` and publish to GitHub Pages automatically on release tags.

**Architecture:** Gradle's built-in `javadoc` task produces static HTML; a new GitHub Actions workflow triggers on `*/v*` tags, generates Javadoc, and deploys via `actions/deploy-pages` to `https://knolux.github.io/knolux-spring-boot-starters/`.

**Tech Stack:** Java 25, Gradle (Kotlin DSL), GitHub Actions (`actions/configure-pages@v4`, `actions/upload-pages-artifact@v3`, `actions/deploy-pages@v4`)

---

### Task 1: Configure Gradle Javadoc task

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Add Javadoc task configuration to `subprojects {}` block**

In `build.gradle.kts`, locate the `tasks.withType<Test>` block (around line 33) and add the following **after** it, still inside `subprojects {}`:

```kotlin
    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            charSet("UTF-8")
            encoding("UTF-8")
            docEncoding("UTF-8")
            locale("zh_TW")
            addStringOption("Xdoclint:none", "-quiet")
        }
    }
```

The full `subprojects {}` block after the edit (tasks section only — don't change anything else):

```kotlin
    tasks.withType<Test> {
        useJUnitPlatform()
    }

    tasks.withType<Javadoc> {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            charSet("UTF-8")
            encoding("UTF-8")
            docEncoding("UTF-8")
            locale("zh_TW")
            addStringOption("Xdoclint:none", "-quiet")
        }
    }
```

- [ ] **Step 2: Verify Javadoc generates without errors**

```bash
./gradlew :knolux-redis-spring-boot-starter:javadoc
```

Expected: `BUILD SUCCESSFUL`. Output HTML will be at `knolux-redis-spring-boot-starter/build/docs/javadoc/index.html`.

If you see `error: cannot find symbol` or encoding errors, check that `options.encoding = "UTF-8"` is set correctly.

- [ ] **Step 3: Commit**

```bash
git add build.gradle.kts
git commit -m "build: configure Javadoc task for Traditional Chinese UTF-8 output"
```

---

### Task 2: Add class-level Javadoc to `KnoluxRedisProperties`

**Files:**
- Modify: `knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisProperties.java`

- [ ] **Step 1: Add class-level Javadoc**

The current file starts with `@ConfigurationProperties(prefix = "knolux.redis")` with no class-level comment. Insert the following Javadoc **immediately before** the `@ConfigurationProperties` annotation:

```java
/**
 * {@code knolux.redis.*} 設定屬性。
 *
 * <p>使用範例（{@code application.yml}）：
 * <pre>{@code
 * knolux:
 *   redis:
 *     url: redis://:password@localhost:6379
 *     timeout-ms: 1000ms
 *     read-from: REPLICA_PREFERRED
 * }</pre>
 */
@ConfigurationProperties(prefix = "knolux.redis")
public class KnoluxRedisProperties {
```

- [ ] **Step 2: Verify Javadoc still builds**

```bash
./gradlew :knolux-redis-spring-boot-starter:javadoc
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisProperties.java
git commit -m "docs: add class-level Javadoc to KnoluxRedisProperties"
```

---

### Task 3: Add Javadoc to `KnoluxRedisAutoConfiguration`

**Files:**
- Modify: `knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisAutoConfiguration.java`

- [ ] **Step 1: Add class-level Javadoc**

Insert the following immediately before the `@AutoConfiguration` annotation:

```java
/**
 * Redis 自動設定。
 *
 * <p>根據 {@code knolux.redis.url} 的 URL scheme 自動建立下列 Bean：
 * <ul>
 *   <li>{@link org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory} — 連線工廠</li>
 *   <li>{@link org.springframework.data.redis.core.StringRedisTemplate} — 字串操作模板</li>
 *   <li>{@link org.springframework.data.redis.core.RedisTemplate} — 物件操作模板（key 與 hash key 使用字串序列化）</li>
 * </ul>
 *
 * <p>支援兩種連線模式：
 * <ul>
 *   <li>{@code redis://} — Standalone 直連模式</li>
 *   <li>{@code redis-sentinel://} — Sentinel 高可用模式</li>
 * </ul>
 *
 * <p>所有 Bean 均標注 {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}，
 * 可由使用者定義同型別的 Bean 覆寫預設行為。
 */
@AutoConfiguration
@EnableConfigurationProperties(KnoluxRedisProperties.class)
public class KnoluxRedisAutoConfiguration {
```

- [ ] **Step 2: Add Javadoc to `redisConnectionFactory()`**

Find the method:
```java
    @Bean
    @ConditionalOnMissingBean(RedisConnectionFactory.class)
    public LettuceConnectionFactory redisConnectionFactory() {
```

Insert the following immediately before the `@Bean` annotation:

```java
    /**
     * 建立 {@link LettuceConnectionFactory}。
     *
     * <p>根據 {@code knolux.redis.url} 的 scheme 判斷模式：
     * {@code redis://} 使用 Standalone，{@code redis-sentinel://} 使用 Sentinel。
     *
     * @return 設定好的 {@link LettuceConnectionFactory}
     * @throws IllegalArgumentException 若 {@code knolux.redis.url} 未設定或為空
     */
```

- [ ] **Step 3: Add Javadoc to `stringRedisTemplate()`**

Find the method:
```java
    @Bean
    @ConditionalOnMissingBean(StringRedisTemplate.class)
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory factory) {
```

Insert immediately before the `@Bean` annotation:

```java
    /**
     * 建立 {@link StringRedisTemplate}，適用於純字串型 key/value 操作。
     *
     * @param factory Redis 連線工廠
     * @return 設定好的 {@link StringRedisTemplate}
     */
```

- [ ] **Step 4: Add Javadoc to `redisTemplate()`**

Find the method:
```java
    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory factory) {
```

Insert immediately before the `@Bean` annotation:

```java
    /**
     * 建立通用 {@link RedisTemplate}，key 與 hash key 使用 {@link org.springframework.data.redis.serializer.StringRedisSerializer}。
     *
     * @param factory Redis 連線工廠
     * @return 設定好的 {@link RedisTemplate}
     */
```

- [ ] **Step 5: Verify Javadoc builds**

```bash
./gradlew :knolux-redis-spring-boot-starter:javadoc
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisAutoConfiguration.java
git commit -m "docs: add Traditional Chinese Javadoc to KnoluxRedisAutoConfiguration"
```

---

### Task 4: Add Javadoc to `KnoluxRedisHealthIndicator`

**Files:**
- Modify: `knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisHealthIndicator.java`

- [ ] **Step 1: Add class-level Javadoc**

Insert immediately before the `@Component` annotation:

```java
/**
 * Redis 健康狀態指標。
 *
 * <p>對 Redis 發送 {@code PING} 指令確認連線正常。
 * 僅在 {@code spring-boot-starter-actuator} 存在於 classpath 時才會啟用。
 *
 * <p>健康端點範例回應（{@code GET /actuator/health}）：
 * <pre>{@code
 * {
 *   "status": "UP",
 *   "components": {
 *     "knoluxRedis": {
 *       "status": "UP",
 *       "details": { "ping": "PONG" }
 *     }
 *   }
 * }
 * }</pre>
 */
@Component
@ConditionalOnClass(HealthIndicator.class)
public class KnoluxRedisHealthIndicator implements HealthIndicator {
```

- [ ] **Step 2: Add Javadoc to `health()`**

Find the method:
```java
    @Override
    public @Nullable Health health() {
```

Insert immediately before the `@Override` annotation:

```java
    /**
     * 執行 Redis 健康檢查。
     *
     * @return 健康狀態；PING 成功則為 {@code UP}（附 {@code ping: PONG}），
     *         否則為 {@code DOWN}（附錯誤訊息）
     */
```

- [ ] **Step 3: Verify Javadoc builds**

```bash
./gradlew :knolux-redis-spring-boot-starter:javadoc
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify generated HTML looks correct**

Open `knolux-redis-spring-boot-starter/build/docs/javadoc/index.html` in a browser (or run `start knolux-redis-spring-boot-starter/build/docs/javadoc/index.html` on Windows). Confirm:
- Three classes are listed
- Chinese text renders correctly (not garbled)
- Each class shows its documentation

- [ ] **Step 5: Commit**

```bash
git add knolux-redis-spring-boot-starter/src/main/java/com/knolux/redis/KnoluxRedisHealthIndicator.java
git commit -m "docs: add Traditional Chinese Javadoc to KnoluxRedisHealthIndicator"
```

---

### Task 5: Create GitHub Actions Javadoc deployment workflow

**Files:**
- Create: `.github/workflows/javadoc.yml`

- [ ] **Step 1: Create the workflow file with the following exact content**

```yaml
name: Publish Javadoc

on:
  push:
    tags:
      - '*/v*'    # e.g. knolux-redis-spring-boot-starter/v1.0.1

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  build-and-deploy:
    name: Build & Deploy Javadoc
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 25
          distribution: temurin

      - uses: gradle/actions/setup-gradle@v4

      - name: Extract module from tag
        id: meta
        run: |
          TAG=${GITHUB_REF_NAME}          # knolux-redis-spring-boot-starter/v1.0.1
          MODULE=${TAG%/v*}               # knolux-redis-spring-boot-starter
          echo "module=$MODULE" >> $GITHUB_OUTPUT

      - name: Generate Javadoc
        run: ./gradlew :${{ steps.meta.outputs.module }}:javadoc

      - name: Configure Pages
        uses: actions/configure-pages@v4

      - name: Upload Pages artifact
        uses: actions/upload-pages-artifact@v3
        with:
          path: ${{ steps.meta.outputs.module }}/build/docs/javadoc

      - name: Deploy to GitHub Pages
        id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/javadoc.yml
git commit -m "ci: add GitHub Actions workflow to publish Javadoc to GitHub Pages"
```

---

### Task 6: Manual GitHub Pages setup (one-time)

This step cannot be automated — it must be done in the GitHub web interface.

- [ ] **Step 1: Enable GitHub Pages via GitHub Actions**

1. Go to `https://github.com/knolux/knolux-spring-boot-starters`
2. Click **Settings** → **Pages** (left sidebar)
3. Under **Build and deployment** → **Source**, select **GitHub Actions**
4. Save

Once this is done, the next time you push a tag (`git tag knolux-redis-spring-boot-starter/v1.0.1 && git push origin knolux-redis-spring-boot-starter/v1.0.1`), the workflow will run and publish the Javadoc to `https://knolux.github.io/knolux-spring-boot-starters/`.
