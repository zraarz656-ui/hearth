# Development progress

Authoritative record of what is implemented, what is verified and what is not.

## Phase 0 — Project foundation — COMPLETE

### Implemented

- Single-module Android application, application id `com.tradequest.app`.
- Gradle version catalog with resolved, published dependency versions.
- Kotlin and Jetpack Compose with Material 3.
- Package boundaries for every `core/*` layer and every feature area.
- `MainActivity` with edge-to-edge layout.
- Top-level navigation: Home, Market Library, Trading, Challenge, Journal,
  News, History, Settings.
- Adaptive navigation: bottom bar below 600dp, navigation rail at or above it.
- Themes: Dark, Light, True-black OLED and Color-blind.
- Centralised colour tokens, including chart tokens separate from Material roles.
- Manual dependency-injection composition root (`AppContainer`).
- Unit-test infrastructure with real assertions.

### Deliberate decisions

- **Manual DI instead of Hilt.** `AGENTS.md` section 4 requires avoiding
  unnecessary third-party dependencies. Revisit if the graph outgrows manual
  wiring.
- **`targetSdk` 36 / `compileSdk` 36.** The development plan does not specify a
  value. 36 is the highest SDK for which the whole dependency stack is
  compatible with AGP 8.x.
- **AGP 8.13.2, not AGP 9.x.** The newest AndroidX releases (core-ktx 1.19.1,
  compose 1.12.x, navigation 2.10.x) declare
  `minAndroidGradlePluginVersion=9.1.0` and `minCompileSdk=37` in their AAR
  metadata. AGP 9.0 also removes the `org.jetbrains.kotlin.android` plugin in
  favour of built-in Kotlin, which is a build-DSL change. Phase 0 pins the
  proven AGP 8.13.2 line and the newest dependency set that supports it.
- **Dependency versions verified against published AAR metadata**, not chosen by
  assumption. See the table below.
- **JUnit 4** rather than JUnit 5: it is the default for Android unit tests and
  needs no extra runner configuration.
- **JDK 21** in this build environment. The Debian image has no JDK 17 package;
  AGP accepts 17 or newer, and the source/target compatibility stays at 17.

### Verified dependency stack

Each version below was confirmed against the published `aar-metadata.properties`
in Google Maven. `minCompileSdk` / `minAGP` are the values the artifact itself
declares.

| Artifact | Version | minCompileSdk | minAGP |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.13.2 | — | — |
| Gradle | 8.14.3 | — | — |
| Kotlin | 2.2.21 | — | — |
| KSP | 2.2.21-2.0.5 | — | — |
| compose-bom | 2026.06.01 | — | — |
| compose ui | 1.11.4 | 35 | 8.6.0 |
| material3 | 1.4.0 | 34 | 1.0.0 |
| material-icons-core | 1.7.8 | 34 | 1.0.0 |
| androidx.core:core-ktx | 1.18.0 | 36 | 8.9.1 |
| activity-compose | 1.13.0 | 36 | 8.9.1 |
| lifecycle-runtime-ktx | 2.10.0 | 34 | 8.1.1 |
| lifecycle-runtime-compose | 2.10.0 | 35 | 8.6.0 |
| lifecycle-viewmodel-compose | 2.10.0 | 35 | 8.6.0 |
| navigation-compose | 2.9.8 | 35 | 8.6.0 |
| room-runtime | 2.8.5 | 34 | 8.1.1 |
| work-runtime-ktx | 2.11.2 | 35 | 8.6.0 |

Versions deliberately *not* taken: `core-ktx` 1.19.1, `compose-bom` 2026.08.00
and 2026.09.00, `navigation-compose` 2.10.x. Each requires AGP 9.1+ and
compileSdk 37.

### Verification performed

| Command | Result |
| --- | --- |
| `./gradlew clean assembleDebug` | BUILD SUCCESSFUL |
| `./gradlew testDebugUnitTest` | 17 tests, 0 failures, 0 errors |
| `./gradlew lintDebug` | 0 errors, 20 warnings |

All 20 lint warnings are version-currency notices
(`GradleDependency`, `AndroidGradlePluginVersion`, `OldTargetApi`) and follow
directly from the deliberate pins above.

APK produced: `app/build/outputs/apk/debug/app-debug.apk`.

### Unverified requirements

These are **not** verified and must not be reported as working:

- **Rendering, gestures and frame rate.** No emulator or device is available in
  the build environment. Any 60 FPS or allocation claim requires profiling on
  hardware.
- **Instrumentation tests.** None are configured in Phase 0.
- **Theme appearance.** Colour tokens are unit-tested for identity and
  distinctness; visual appearance is not verified.
- **KSP processing.** KSP is applied and version-aligned with Kotlin, but no
  processor runs until Room entities exist in Phase 2. The pairing is therefore
  configured but not yet exercised.
- **Adaptive navigation on a real device.** Breakpoint logic is untested on
  hardware.

### Not started

Phases 1 to 14. No historical data, simulation, trading, chart, challenge,
news or journal logic exists. Every feature screen renders an explicitly
labelled placeholder.
