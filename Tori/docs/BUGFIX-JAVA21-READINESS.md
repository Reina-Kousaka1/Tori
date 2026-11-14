# Tori bugfix and Java 21 readiness

## Scope and baseline

- Repository: `Reina-Kousaka1/Tori`
- Isolated branch: `codex/tori-java21-bugfix`
- Baseline: `main` at `9ef1d6b5f4f710aba2586c7c9818833c84984205`
- The checkout was clean before edits. No production configuration, token,
  database, Discord account, or service was used.
- The local runtime is Microsoft OpenJDK `21.0.12.1`; the Gradle wrapper is
  `9.8.2`. The test run explicitly clears opt-in PostgreSQL/economy test
  variables, so database tests cannot connect to an external database.

## Confirmed bug

**CONFIRMED BUG — Java compilation fails on `main`.** Reproduction:

```powershell
cd Tori
$env:TORI_TEST_DATABASE_URL = $null
$env:TORI_TEST_DATABASE_USER = $null
$env:TORI_TEST_DATABASE_PASSWORD = $null
$env:TORI_ECONOMY_TEST_API_URL = $null
$env:TORI_ECONOMY_TEST_API_SECRET = $null
$env:TORI_ECONOMY_TEST_WRITE_ENABLED = $null
$env:TORI_POSTGRES_LIVE_TEST = $null
.\gradlew.bat test --no-daemon
```

On the baseline commit, `:compileJava` fails in
`src/main/java/music/CommandListener.java`. The slash-command and prefix-command
callbacks each retained an outer `try` after the November 1 guild-queue refactor
removed the matching `finally`; the compiler reports an unclosed `try`, followed
by cascading parser errors. The fix removes those two orphan wrappers and keeps
the existing inner command-error handling intact. The existing
`CommandListenerTest.commandWaitsForAcknowledgementAndDeliversPublicReply`
exercises the slash path; the Java 21 workflow compiles and runs that suite and
the prefix tests on relevant pull requests, preventing this broken source from
being treated as validated.

No additional runtime bug was confirmed from the repository evidence. The open
issue list contains an automation request, not a reproducible bot failure;
there were no `TODO`, `FIXME`, or `BUG` markers in the Java runtime or tests.
Potential runtime issues that require a concrete symptom or live-environment
evidence remain unclassified rather than being changed speculatively.

## Java and JDA status

Java 21 was already the configured target before this work: Gradle's Java
toolchain requests 21, the Docker build stage is `gradle:9.8.2-jdk21`, and the
runtime image is `eclipse-temurin:21-jre`. The supported Java target therefore
remains 21; this change repairs its broken compilation and adds repeatable CI
qualification rather than changing the language target.

The actual Gradle dependency is JDA `6.7.0`. No JDA upgrade was needed: the
Java 21 Gradle compile and test suite validate the existing API combination.
The detailed Java/Docker guide incorrectly said JDA `6.5.0`; that text is
corrected to match the build. There is no custom GitHub Actions workflow on the
baseline, so `.github/workflows/java21.yml` adds Gradle test/distribution
verification and a no-push ARM64 image build.

## Qualification and limits

- The JVM and Gradle build use Java 21; no Java 21 language features were
  introduced unnecessarily.
- The Gradle CI job runs all test tasks and creates `installDist`.
- After the fix, local `./gradlew.bat --no-daemon test installDist` completed
  successfully on Windows with Java 21: 308 test cases total, 0 failures, 0
  errors, and 7 opt-in live database/API tests skipped because their explicit
  test connection settings were cleared. All 301 non-skipped tests passed.
- The ARM64 job uses QEMU/Buildx to build the existing image without pushing
  it. It does not run Tori against Discord or a database.
- No physical Raspberry Pi 4 / Debian 13 run, CPU or memory benchmark, service
  shutdown test, or native ARM64 runtime test was performed here.
- The Docker runtime currently specifies Java 21 but does not set a Tori JVM
  heap cap. Do not choose an arbitrary heap size for a Pi shared with other
  services; measure under the intended cgroup limits before staging.

## Controlled update plan

1. Build the distribution from a reviewed commit SHA and record the artifact
   SHA-256; use the same Java 21 toolchain and Gradle wrapper verified by CI.
2. For a future staging test, use an isolated Discord test application and
   synthetic/test database. Keep the production token and data out of the test.
3. Before a production change is separately authorized, record the currently
   deployed image or artifact digest, Java version, configuration version,
   and database backup/restore status. This branch makes no schema migration.
4. Start only the new application artifact using the operator-approved
   procedure. Check the clean startup message, Discord gateway readiness,
   slash-command registration result, database health, Lavalink connection,
   and graceful shutdown in staging.
5. If health checks fail, stop the candidate and restore the previous
   application image/artifact and configuration. Do not roll back or rewrite
   database state based only on an application rollback.

Production deployment, bot restart, command publication, and database actions
are outside this change and require separate authorization.
