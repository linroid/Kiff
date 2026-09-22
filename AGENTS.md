# Repository Guidelines

## Project Structure & Module Organization

Kiff is a Kotlin Multiplatform file-diffing library with a JVM command-line application.

- `library/src/commonMain/kotlin/com/linroid/kiff/` contains patchers and shared delta,
  container, format, I/O, and text code. Keep portable logic here.
- `library/src/{jvmMain,jsMain,nativeMain}/` contains platform-specific filesystem adapters.
- `library/src/commonTest/` contains shared tests, synthetic archive builders, and embedded
  compatibility vectors; there is no separate application asset directory.
- `cli/src/main/kotlin/com/linroid/kiff/cli/` contains Clikt commands and output formatting.
- `FORMAT.md` specifies the patch format; `gradle/libs.versions.toml` centralizes dependencies.

## Build, Test, and Development Commands

Use the Gradle wrapper and JDK 17, matching CI.

- `./gradlew :cli:installDist` builds the CLI distribution and its library dependency.
- `./cli/build/install/kiff/bin/kiff --help` shows commands for local use.
- `./gradlew :library:jvmTest` runs the shared suite on JVM.
- `./gradlew :library:jsNodeTest` runs the suite on Node.js.
- `./gradlew :library:iosSimulatorArm64Test` runs iOS simulator tests on macOS with Xcode.
- `./gradlew :library:linuxX64Test` runs native tests on Linux.
- `./gradlew publishToMavenLocal` publishes artifacts locally for integration testing.

## Coding Style & Naming Conventions

Follow `.editorconfig`: two-space indentation, UTF-8, LF endings, a final newline, no trailing
whitespace, and a 100-character line limit. Kotlin uses official style with trailing commas
disabled. No Gradle lint or formatter task is currently configured. Use `PascalCase` for types,
`camelCase` for functions and properties, and lowercase packages under `com.linroid.kiff`.

## Testing Guidelines

Use `kotlin.test`, name test classes `*Test`, and give test methods descriptive camelCase names
such as `restoresEmptyInputs`. No numeric coverage threshold is configured. Add regression tests
for changed behavior, including byte-exact restoration and malformed-input rejection. Reuse
existing fixture builders and round-trip helpers. Container children must tile their parent
exactly; verify this with `ContainerFormatTiling.describe`.

Preserve `FormatVectorsTest` compatibility vectors. Intentional format changes require updating
`FORMAT.md` and considering versioning and support for previously released patches.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects, following history such as “Refuse a malformed patch rather
than trusting it.” Conventional Commit prefixes are not required. Choose descriptive branch names
without a `codex/` prefix. Keep PRs focused; describe the problem, resulting behavior, related
issues, and validation performed. Include CLI output examples when output changes, and flag
format compatibility or performance implications. Run relevant tests before requesting review.
