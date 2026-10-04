# Build environment

How to build, test and run Shotgun!. The short version lives in
[`README.md`](../README.md); the working rules live in
[`.claude/CLAUDE.md`](https://github.com/drehtuer/ShotgunApp/blob/main/.claude/CLAUDE.md).

## Toolchain

| | Version | Note |
| --- | --- | --- |
| AGP | 9.4.0 | |
| Gradle | 9.7.1 | via the wrapper |
| Kotlin / Compose plugin | 2.4.10 | |
| JDK | 21 | |
| compileSdk / targetSdk | 37 (Android 17) | matches the target phone exactly |
| minSdk | 26 | |
| Compose BOM | 2026.08.00 | |

**AGP 9 has built-in Kotlin support, so there is deliberately no
`kotlin-android` plugin.** Adding it fails the build with *"no longer required
since AGP 9.0"*. This is the single most likely thing to trip someone up.

The move to AGP 9 was not cosmetic: current AndroidX (`navigation-compose`
2.10.0) requires compileSdk 37 and AGP 9.1+, so AGP 8.x cannot build this
project at all.

Versions are pinned in [`gradle/libs.versions.toml`](https://github.com/drehtuer/ShotgunApp/blob/main/gradle/libs.versions.toml).
Keep `.devcontainer/post-create.sh` in step with it - it installs the matching
SDK packages.

## Devcontainer (recommended)

The repo ships a devcontainer with the SDK, an emulator and `platform-tools`.
Open the folder in VS Code and **Reopen in Container**.

The first create downloads ~1–2 GB of SDK packages. They land in named volumes,
so later rebuilds are fast:

| Volume | Holds | Why it is a volume |
| --- | --- | --- |
| `shotgun-android-sdk` | SDK packages + AVDs | avoids re-downloading |
| `shotgun-gradle` | Gradle cache | avoids re-resolving |
| `shotgun-dotandroid` | `~/.android` | **the debug keystore** |

That last one matters more than it looks: a regenerated debug keystore makes
`installDebug` fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` until you
uninstall the app from the device by hand.

`/dev/kvm` is passed through for emulator acceleration, and `post-create.sh`
opens its permissions - it arrives owned by a host group that does not exist
inside the container, and without write access the emulator refuses to start
x86_64 images at all.

Networking is left on the default **bridge**, not host. `adb connect` is an
outbound connection, so Wi-Fi debugging to a phone on the LAN works through NAT
without the complications host networking brings.

### Building without the devcontainer

You need JDK 21 and an Android SDK with `platforms;android-37.0` and
`build-tools;37.0.0`. Point Gradle at it with either `ANDROID_HOME` or a
`local.properties` containing `sdk.dir=/path/to/sdk`. `local.properties` is
gitignored.

**A JRE is not enough - it must be a JDK.** Gradle needs the compiler, and a
runtime-only install fails every task with:

```
Failed to calculate the value of task ':app:compileDebugJavaWithJavac'
  property 'javaCompiler'.
> Toolchain installation '/usr/lib/jvm/java-21-openjdk-amd64' does not provide
  the required capabilities: [JAVA_COMPILER]
```

The give-away is that `java -version` works while `javac -version` is missing.
On Debian/Ubuntu the `openjdk-21-jre` package alone does this; install
`openjdk-21-jdk`. Inside the devcontainer this cannot happen - the base image
ships a full JDK at `/usr/lib/jvm/msopenjdk-current`, and the Dockerfile asserts
`javac` is present so a base-image change that dropped it would fail the image
build rather than the first Gradle run.

#### Building in the devcontainer image, from outside the devcontainer

A host with no JDK can still build by running Gradle inside the image the
devcontainer is built from, against the same named volumes, so nothing is
downloaded twice:

```bash
docker run --rm --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/home/vscode/.gradle \
  -v "$PWD":/work -w /work \
  -v playerpicker-android-sdk:/opt/android-sdk \
  -v playerpicker-gradle:/home/vscode/.gradle \
  shotgun-dev:latest bash -lc 'mkdir -p /tmp/home && ./gradlew assembleDebug lint'
```

Two details, both of which fail confusingly if missed:

- **Run as the host uid, not the image's `vscode`.** The volumes were created by
  the devcontainer bind, so their contents belong to the *host* user. Running as
  `vscode` (uid 1000) dies with `Could not create parent directory for lock file
  /home/vscode/.gradle/wrapper/...` before Gradle starts.
- **`HOME` must point somewhere writable** for that uid, and
  `GRADLE_USER_HOME` at the volume - otherwise Gradle re-downloads its
  distribution and every dependency into a home directory it does not own.

It builds and runs the unit tests; it cannot install or talk to a device.

## Building

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK
./gradlew bundleRelease          # release AAB
./gradlew installDebug           # build and install on the connected device
```

Output lands in `app/build/outputs/apk/` and `app/build/outputs/bundle/`,
named for people rather than for Gradle:

| Build | File |
| --- | --- |
| release | `Shotgun-<version>.apk` |
| debug | `Shotgun-debug-<version>.apk` |

The version comes from `appVersion` in `app/build.gradle.kts`, which is also
what `versionName` is set from - one value, so the file name and the manifest
cannot disagree.

**`versionCode` is derived from it too, never written by hand.** It is what
Android compares to decide whether an APK is an update, and a release that
repeats or lowers it cannot be installed over the one before - which an
immutable release cannot then correct. `major * 10000 + minor * 100 + patch`,
so `0.1.1` is `101`. To cut a release, change `appVersion` and tag; there is no
second number to remember. The release workflow **fails if the tag and `appVersion`
disagree**: the artifacts are named from the build file, so a `v0.2.0` tag on
an unchanged `appVersion` would publish files called `Shotgun-0.1.0.apk`, and a
published release cannot be corrected.

The bundle keeps Gradle's own name until the release workflow renames it to
`Shotgun-<version>.aab` alongside the APK.

### The two variants

| | `debug` | `release` |
| --- | --- | --- |
| Debuggable | yes | no |
| Minified (R8) | no | yes |
| Resource shrinking | no | yes |
| Debug symbols | kept | stripped (`debugSymbolLevel = NONE`) |
| Version name | `0.1.0-debug` | `0.1.0` |
| Signed with | dev key | release key, when present |

`debug` is the build to test on a device: unminified, with symbols, so a stack
trace means something.

Two deliberate omissions in `debug`:

- **No `applicationIdSuffix`.** It would rename the package for debug builds
  and break every `adb` command in this document.
- **No `enableAndroidTestCoverage`.** It instruments the APK itself, and this
  app is judged on touch and countdown timing - a slowed build would misreport
  how it feels. Unit-test coverage is on; it does not touch the APK.

## Signing

**This repository is public. No signing material is ever committed to it.**
Everything local lives in one directory, `keystore/` - both keystores and the
`keystore.properties` that points at them - so there is a single rule to get
right. `.gitignore` covers `*.keystore`, `*.jks`, `keystore/` and
`keystore.properties`; the last is kept although `keystore/` already covers the
file, because a stray copy at the repository root is exactly the mistake worth
catching twice.

There are two keys:

| Key | Lives | Used for |
| --- | --- | --- |
| **dev** | your machine, and a GitHub Actions secret | debug builds, CI |
| **release** | your machine, and a GitHub Actions secret | release builds |

Both keys are held as **encrypted Actions secrets**, never as files in the
repository. That distinction is the whole point: this repository is public, so a
committed key would be world-readable, while a secret is encrypted at rest and
is not exposed to pull requests from forks.

### Local setup

Gradle reads `keystore/keystore.properties`:

```properties
debug.storeFile=keystore/debug.keystore
debug.storePassword=…
debug.keyAlias=shotgun-debug
debug.keyPassword=…

release.storeFile=keystore/release.keystore
release.storePassword=…
release.keyAlias=shotgun-release
release.keyPassword=…
```

The `storeFile` paths are relative to the **repository root**, not to the file
itself - that is what Gradle resolves them against, and what the CI environment
variables use. Everything in `keystore/` is gitignored. If it is missing the
build still works: `debug` falls back to the SDK's own debug key, and `release` is
produced **unsigned**.

### Generating a key

```bash
keytool -genkeypair -v \
  -keystore keystore/release.keystore -alias shotgun-release \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Shotgun!, OU=Release, O=drehtuer, C=DE"
```

**Back the release key up somewhere off this machine.** Losing it means no
future build can update an installed app - Android identifies an app by its
signature, and there is no recovery.

### CI

The dev key reaches CI as base64 in a secret:

```bash
base64 -w0 keystore/debug.keystore    # paste into the DEBUG_KEYSTORE_BASE64 secret
```

| Secret | Used by |
| --- | --- |
| `DEBUG_KEYSTORE_BASE64`, `DEBUG_KEYSTORE_PASSWORD`, `DEBUG_KEY_ALIAS`, `DEBUG_KEY_PASSWORD` | `pr.yml` |
| `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` | `release.yml` |

All eight are configured, so CI signs both variants end to end, and **nothing
further needs to be added**. If the release secrets are ever removed,
`release.yml` fails on the spot rather than publishing unsigned artifacts: a
published release is immutable, so an unsigned APK would burn that version
number permanently. It also runs `apksigner verify` on the built APK, because
the Gradle config falls back to an unsigned build when the keystore does not
load - the signature is confirmed on the artifact, not inferred from the
secret being present.

Every workflow deletes the restored keystore in an `always()` step, so it never
survives into a later step or an uploaded artifact.

### Backing the release key up

**Both keystores are password-protected, so the file on its own is not a
backup.** A backup has to hold two things, and both are now off this machine:

| Piece | Where it lives |
| --- | --- |
| `keystore/release.keystore` | the build machine, and a password manager |
| The password | `keystore/keystore.properties` on the build machine, and the same password manager |

Within each keystore the store password and the key password are the same
value; the debug and release passwords differ from each other.

A GitHub secret is **write-only** - it can be read back by no one, including
you - so it is not a copy of either piece, and never counts towards this.

**Keep the two together.** With the file but not the password the key is
unusable, and then no future build can ever update an installed app: Android
identifies an app by its signature and there is no recovery. If the key is ever
rotated, the password manager has to be updated in the same pass as
`keystore/keystore.properties` and the GitHub secrets.

The release key is 4096-bit RSA, `SHA384withRSA`, valid until **2056-08-29**, so
the expiry is not the thing to worry about. Losing the password was.

## Persistence

Two stores, both under `app/src/main/java/de/drehtuer/shotgun/data/`:

| Store | Backed by | Holds |
| --- | --- | --- |
| `DrawHistory` | Room (`shotgun.db`) | every completed draw, for the fairness field |
| `SettingsRepository` | DataStore (`settings.preferences_pb`) | theme, haptics, dim, countdown, reveal timing |

**Room schemas are committed** to `app/schemas/`. Bump `version` in
`ShotgunDatabase` and add a `Migration` for every schema change; the exported
JSON gives a real before-and-after to write it against.

`fallbackToDestructiveMigration` is deliberately not set. History is what makes
the fairness field evidence rather than decoration, so a schema change must
migrate it, not discard it.

**Neither store is backed up.** `android:allowBackup="false"` keeps both on the
device, out of cloud backup and out of reach of `adb backup` - see
[`design.md`](design.md#what-gets-recorded) for what that costs. It is also why
the two `run-as` commands below are the only way to look at them.

Inspecting either on a device:

```bash
adb shell run-as de.drehtuer.shotgun ls -l files/datastore databases
adb exec-out run-as de.drehtuer.shotgun cat databases/shotgun.db > shotgun.db
```

## Testing

Two layers run without a device, one needs one.

```bash
./gradlew lint                   # static analysis
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedDebugAndroidTest   # instrumented, needs emulator or phone
```

Unit tests live in `app/src/test/`. Reports:

- Unit tests — `app/build/reports/tests/testDebugUnitTest/index.html`
- Lint — `app/build/reports/lint-results-debug.html`

Lint must pass clean. Suppress a genuine false positive *narrowly*, with
`tools:ignore` or `@Suppress` plus a comment - never disable a check globally.

There is one standing false positive: `R.mipmap.ic_launcher_round` is reported
unused, but it is referenced from the manifest's `android:roundIcon`.

### Coverage

`enableUnitTestCoverage` is on for the debug build type, so the unit tests are
instrumented by JaCoCo. The report is a separate task:

```bash
./gradlew createDebugUnitTestCoverageReport
# app/build/reports/coverage/test/debug/index.html   (report.xml beside it)
```

It depends on `testDebugUnitTest`, so asking for both does not run the tests
twice. CI does exactly that, and uploads the directory as the `coverage-report`
artifact.

That report covers the **JVM tests only**. The instrumented half is measured
separately - see below.

**Read the figure per layer, not overall.** The app is a Compose UI over a small
pure core, and one blended percentage describes neither half:

| Layer | Tested by | Where |
| --- | --- | --- |
| `draw/`, `result/`, `data/` | plain JVM unit tests | `app/src/test/` |
| `ui/` | Robolectric, also on the JVM | `app/src/test/` |

The two are not reported as separate numbers. The split is read by drilling
into the directories in
[*Measures > Coverage*](https://sonarcloud.io/component_measures?id=drehtuer_ShotgunApp&metric=coverage)
on the
[SonarQube project](https://sonarcloud.io/project/overview?id=drehtuer_ShotgunApp).

Nothing is excluded, and nothing needs to be. Room's generated sources - written
from the DAO and database declarations, and not in the repository - live under
`app/build/generated`, while the scanner analyses `app/src/main/` only, so
generated code is outside the analysis rather than excluded from it.

### Robolectric

The screens are covered by **Robolectric**, which runs the Android framework on
the JVM. `./gradlew testDebugUnitTest` therefore covers the rules *and* the
screens, in CI, with no emulator.

Three pieces of configuration make it work, and each fails in its own quiet way:

- `testOptions { unitTests { isIncludeAndroidResources = true } }`. Robolectric
  renders real composables, so it needs the merged resources - the theme, the
  bundled fonts. Without it every screen test fails on resource lookup.
- `app/src/test/resources/robolectric.properties` pins `sdk=34`. Robolectric
  ships framework jars below this module's `targetSdk` of 37, so it cannot run
  at 37 at all. These tests do not prove behaviour on Android 17 - the phone
  does that - only that the screens compose, lay out and respond.
- The **`jacoco` plugin, applied explicitly**, and
  `isIncludeNoLocationClasses = true` on the test task. This one is the trap:
  Robolectric loads application classes through its own sandbox classloader and
  they arrive with no source location, which JaCoCo skips by default. Without
  it the screen tests pass and every screen still reports **zero** coverage -
  the tests run, the report cannot see them. AGP's `enableUnitTestCoverage`
  runs JaCoCo but does not apply the plugin, so `JacocoTaskExtension` does not
  exist until the plugin is named in `plugins { }`.

**Robolectric does not replace `app/src/androidTest/`.** The device set covers
what neither it nor an emulator can: multi-touch, real haptics, and how the
countdown feels. It is run by hand on the phone - see *Real device over Wi-Fi* -
and deliberately not in CI. A hosted runner has no device, so such a job could
only be skipped, fail, or boot an emulator, and an emulator agreeing with
Robolectric would confirm two simulations at once rather than the thing itself.

`DrawScreen` is the multi-touch surface, so its *driving* is covered only on the
phone. Its decisions were moved out of the composable and are unit-tested:
`ringSpec` decides what a ring looks like, and `routePointers` in
`draw/SurfaceInput.kt` decides which pointer changes land, move or lift a
finger. `edgeGlowAlpha` and `frameAlpha` in the same file give the strength of
the edge glow and the frame. `DrawScreen` builds the engine and hands it to
`internal DrawSurface(engine, …)`, so `DrawSurfaceTest` can give the surface an
engine that already has fingers down. The countdown, the staged reveal and the
refusal timeout then run by themselves on the fake clock. The surface's pieces -
the rings, the hint, the refusal bar, the reveal bar - are `internal` too, so
each can be rendered with the state it would have had mid-draw. The only parts
the JVM cannot reach are the pointer loop itself (turning Compose's events into
`routePointers` calls) and the finger-tick haptic, which only a real press
produces.

**Robolectric does not draw unless asked.** In its default graphics mode the
draw phase never runs, so a `Canvas` lambda shows as uncovered even in a test
that renders it. A test that annotates itself `@GraphicsMode(NATIVE)` and calls
`captureToImage()` gets real pixels: `EdgeGlow`, the mark and the fairness field
are all checked this way, by the colour at chosen points.

**Pointer injection does not work here.** `performTouchInput { down(...) }`
drives the draw surface on the phone and lands *nothing* under Robolectric - no
rings, no countdown - with the clock frozen or auto-advancing, and advancing
`System.currentTimeMillis` with `ShadowSystemClock` does not change it. Do not
spend an afternoon on it a second time: those paths are covered in
`app/src/androidTest/`, on hardware.

#### The settings store is a singleton, so tests are given their own

`preferencesDataStore` caches **one store per process** - the delegate holds it,
not the `Context` - which is right for an app and wrong for a test suite. Every
test in the JVM otherwise shares one store and one internal scope, and
Robolectric's fresh filesystem does not help: the cached store keeps serving
what the last test wrote. A probe proved it, writing `haptics = false` in one
test and reading it back in the next.

It cost a CI failure before it was understood. `ShotgunViewModelTest` hung for a
full minute - `UncompletedCoroutinesError: the test body did not run to
completion` - because the shared store also runs on `Dispatchers.IO`, so its
work lands on real threads while `runTest` drives a virtual clock.

`SettingsRepository` therefore takes a `DataStore<Preferences>`; the `Context`
constructor is kept and is what the app uses. Tests build one through
`IsolatedSettingsStore`, which gives each test its own file **and** puts the
store on the test's own dispatcher, so a write and the read waiting for it share
a clock. `SettingsStoreIsolationTest` holds the invariant: what one test writes,
the next must not see.

Two more limits worth knowing before writing a test that cannot pass:

- **Robolectric lays out composables but does not rasterise them**, so a
  `Canvas` draw lambda never runs and cannot be covered. `ShotgunMark` is the
  example.
- **A test in `de.drehtuer.shotgun.ui.components` cannot use `@Rule`.** The app
  has a `Rule()` composable, and the clash crashes the Kotlin backend rather
  than reporting an error - *"Backend Internal error: Exception during IR
  lowering"*, an NPE in `JvmAnnotationImplementationTransformer`, naming only
  the file. Put the test in another package, or alias the import
  (`import org.junit.Rule as JUnitRule`). `ComponentRenderTest` does both.

#### SonarQube Cloud

Coverage and static analysis go to
[SonarQube Cloud](https://sonarcloud.io/project/overview?id=drehtuer_ShotgunApp),
which hosts the two README coverage badges and decorates pull requests.

The scan runs from Gradle, not from a scanner action:

```bash
./gradlew testDebugUnitTest createDebugUnitTestCoverageReport sonar
```

`sonar` needs the compiled classes and the JaCoCo XML, so it goes *after* the
two tasks that produce them - in CI they are separate steps in the same job and
the same workspace. The keys live in the root `build.gradle.kts`
(`drehtuer_ShotgunApp` in organisation `drehtuer`, against `sonarcloud.io`); the
token does not, and comes from the `SONAR_TOKEN` repository secret.

Four things are easy to get wrong, and three of them fail quietly:

- **The JaCoCo report path is set by hand.** AGP's
  `createDebugUnitTestCoverageReport` is not a `JacocoReport` task, so the
  scanner's auto-detection does not find it. Without
  `sonar.coverage.jacoco.xmlReportPaths` in `app/build.gradle.kts`, the analysis
  succeeds and reports **0%**.
- **The checkout needs `fetch-depth: 0`.** SonarQube dates each line from git
  blame, and the default shallow clone leaves every line looking new - which is
  what decides what counts as *new code* on a pull request.
- **Automatic Analysis must be off.** SonarCloud enables it when a repository is
  imported, and it refuses a CI analysis while it is on: *"You are running CI
  analysis while Automatic Analysis is enabled"*. Turn it off under
  *Administration > Analysis Method*. It was on here and failed the first run -
  while reporting a green check of its own that measured no coverage, because
  Automatic Analysis does not run the tests.
- **Without the token the step is skipped, not failed** - the workflow checks
  for it in a preceding step, because a step's own `env:` is not in scope for
  its own `if:`, and because a fork's pull request cannot read secrets at all.

The badges are served by shields.io rather than by SonarQube's own badge API:
that API accepts only `coverage`, and the two numbers worth showing separately -
`line_coverage` and `branch_coverage` - are not in its list.

## Emulator

```bash
./.devcontainer/emulator-start.sh        # boots pixel9a-api37 headless
adb devices                              # confirm it attached
```

The AVD is a **`pixel_9a` profile on an API 37 image**. The target phone is a
Pixel 10a, which has no profile in the emulator's device catalogue yet, so
`pixel_9a` is the closest hardware match - the API level matches exactly.

Useful while checking a change:

```bash
adb shell am start -n de.drehtuer.shotgun/.MainActivity
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
adb logcat -d -b crash | tail -20

adb shell cmd uimode night yes    # dark palette
adb shell cmd uimode night no     # light palette
```

Always check both palettes. Theme bugs routinely show in only one - the app can
override the system theme from Settings, so the two can disagree.

## Real device over Wi-Fi

**The emulator cannot test multi-touch**, which is the entire draw surface. Any
change to touch handling, haptics or countdown timing has to be checked on the
phone.

```bash
# Phone: Developer options > Wireless debugging > Pair device with pairing code
./.devcontainer/connect-device.sh pair <ip>:<pairingPort> <code>
./.devcontainer/connect-device.sh connect <ip>:<port>
./gradlew installDebug
```

The pairing port and the connect port are **different** - the pairing dialog
shows one, the Wireless debugging screen shows the other. On Android 11+ the
connect port is a **random ephemeral port, not 5555**; 5555 only applies to the
older `adb tcpip` route for pre-Android-11 devices.

If the phone is not to hand, find the port:

```bash
./.devcontainer/connect-device.sh discover <ip>
```

It scans 30000-50000 and tries each open port until one accepts an adb
connection. That is crude, but `adb mdns services` finds nothing here: mDNS is
link-local and does not cross the container's NAT.

The pairing port stays open after pairing and looks like a candidate, but
connecting to it leaves the device `offline` - `adb disconnect <ip>:<pairingPort>`
and use the other one. `discover` already skips it.

Run the script with no arguments for the full notes. The
[`connect-android-device`](https://github.com/drehtuer/ShotgunApp/blob/main/.claude/skills/connect-android-device/SKILL.md)
skill wraps this whole flow, including reading a draw back out of the database.

With both an emulator and a phone attached, target one explicitly:

```bash
adb devices
adb -s <serial> install -r app/build/outputs/apk/debug/Shotgun-debug-*.apk
```

## CI

| Workflow | Trigger | Does |
| --- | --- | --- |
| [`pr.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/workflows/pr.yml) | **any** pull request, push to `main` | builds debug, then unit tests and lint |
| [`release.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/workflows/release.yml) | **a `v*` tag only** | builds and signs the release APK and AAB, drafts the release with them, then publishes it |
| [`docs.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/workflows/docs.yml) | **a `v*` tag only**, manual | builds the Pages site with Jekyll plus Dokka, and deploys it |
| [`codeql.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/workflows/codeql.yml) | any PR, push to `main`, weekly | CodeQL over the Kotlin sources and the workflows |

Releases are cut by tagging. Nothing in `release.yml` runs for ordinary pushes
or pull requests:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

### Why the release is drafted first

**A published release is immutable: its assets cannot be added to, replaced or
removed.** So the workflow attaches the APK and AAB to a *draft*, and publishes
only once the upload has finished. Attaching to an already-published release
would fail, and a release published empty could never be corrected - the tag
would have to be abandoned.

### Why the docs workflow watches the tag, not the release

It would read better as `release: published` - the site describes a release, so
it should follow one. It would also never run. **A release published by a
workflow using the default `GITHUB_TOKEN` does not trigger further workflow
runs**, which is GitHub's guard against workflows setting each other off in a
loop. The documentation would simply never publish, with nothing in any log to
say why.

So both workflows watch the same `v*` tag and run independently. The tag is
pushed by a person, so it triggers as expected.

Building in CI rather than locally is what makes the immutability worth
anything. The artifacts come from a clean checkout of the tag, so what is
published is exactly what the tag contains - a local build cannot promise that,
and with an immutable release there is no way to correct one after the fact.

Test and lint reports are uploaded as artifacts, including on failure, so a red
run can be diagnosed without reproducing it locally.

**CI deliberately does not run the instrumented tests.** They need the phone:
an emulator exposes one input device per contact and cannot inject a genuine
multi-pointer gesture, so a green emulator run would say nothing about the one
thing the draw surface exists to do. They are run locally against the Pixel 10a
before a PR that touches multi-touch, haptics or timing.

`pr.yml` deliberately has **no base-branch filter**. Stacked pull requests are
based on the branch below them rather than on `main`, and a filter of
`branches: [main]` would leave every PR in a stack unverified.

### The documentation site

The site is built by `actions/jekyll-build-pages` - **GitHub's own Pages
toolchain** - from `_config.yml` at the repository root. It renders the Markdown
that already lives here, `README.md` and `docs/`, rather than a copy, so the
published site cannot drift from what a reader sees in the repository.

| Piece | Where |
| --- | --- |
| Site configuration | [`_config.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/_config.yml) |
| Page layout, navigation, Mermaid | [`_layouts/default.html`](https://github.com/drehtuer/ShotgunApp/blob/main/_layouts/default.html) |
| Stylesheet, from the app palette | [`assets/css/site.css`](https://github.com/drehtuer/ShotgunApp/blob/main/assets/css/site.css) |
| API documentation | Dokka, copied to `/api/` after the Jekyll build |

Four things about it are easy to get wrong:

- **`baseurl` must be `/ShotgunApp`.** It is a project site, served from a
  subdirectory, so every internal link goes through Jekyll's `relative_url`
  filter. Without it the stylesheet and navigation 404.
- **Links between documents work because of `jekyll-relative-links`**, which
  turns `docs/design.md` into the rendered page. It only knows about files
  Jekyll publishes, so links to source, workflows or the design export are
  written as absolute GitHub URLs in the Markdown - they resolve both in the
  repository and on the site.
- **Mermaid is not rendered by Pages.** GitHub renders Mermaid in its *repository*
  Markdown view, not on Pages, so the layout loads Mermaid from a CDN and
  unwraps Kramdown's `<pre><code class="language-mermaid">` into the element
  Mermaid expects. Diagrams are hidden until it has run, so a reader never sees
  raw diagram source.
- **Dokka's output is copied in after Jekyll**, not before: it contains
  underscore-prefixed files, which Jekyll would silently drop.

Two repository settings the workflow cannot supply for itself:

- **Pages Source must be GitHub Actions**, or the deploy step fails.
- **The `github-pages` environment must allow the tag to deploy.** It ships
  allowing the default branch only, and this workflow runs on a `v*` tag, so
  the deploy is rejected with *"Tag v0.1.1 is not allowed to deploy to
  github-pages due to environment protection rules"* - after the site has built
  successfully, in a separate job, which makes it look like a deployment problem
  rather than a settings one. The environment now carries two policies:

  | Type | Pattern | For |
  | --- | --- | --- |
  | branch | `main` | `workflow_dispatch`, republishing without a release |
  | tag | `v*` | the release itself |

To preview the site exactly as CI builds it, run the same container image:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD":/w -e GITHUB_WORKSPACE=/w \
  -e INPUT_SOURCE=./ -e INPUT_DESTINATION=./_site \
  ghcr.io/actions/jekyll-build-pages:latest
```

`_site/` is gitignored. Note that links are absolute under `/ShotgunApp/`, so
serve it from that path rather than opening the files directly.

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `The 'org.jetbrains.kotlin.android' plugin is no longer required` | Someone re-added the plugin. Remove it. |
| `NoClassDefFoundError: ProjectTypeBinding` on any task | Gradle is older than 9. Use the wrapper. |
| `requires ... compile against version 37 or later` | `compileSdk` was lowered, or AndroidX was bumped past the toolchain. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Debug keystore changed. `adb uninstall de.drehtuer.shotgun`, then reinstall. |
| `x86_64 emulation currently requires hardware acceleration` | `/dev/kvm` missing or not writable. |
| Emulator says `Unknown AVD name` | `ANDROID_AVD_HOME` is not set; the AVD lives in the SDK volume. |
| `does not provide the required capabilities: [JAVA_COMPILER]` | A JRE, not a JDK. Install `openjdk-21-jdk`, or build inside the devcontainer. |
| `failed to connect to '<ip>:5555'` | Android 11+ picks a random connect port. `connect-device.sh discover <ip>`. |
| Device stuck `offline` after pairing | Connected to the pairing port. Disconnect it and use the Wireless debugging port. |
| `adb mdns services` lists nothing | Expected under bridge networking; mDNS does not cross the NAT. Use `discover`. |
| CI: `Warning: Failed to find package 'tools'`, then `sdkmanager` exits 1 | `android-actions/setup-android` older than v4.0.2. Google removed the obsolete `tools` package from the SDK repository; the action still asked for it. Merge Dependabot's bump. |

## The design export

`design/Shotgun.dc.html` is regenerated from Claude Design, so **it is never
hand-edited**: an edit made here is reverted by the next re-export, which has
happened twice. Visual changes belong upstream in the Design project; behaviour
belongs in [`design.md`](design.md), which no export touches.

`DesignTokenTest` holds the visual half of that to account. It reads the nine
`--pp-*` tokens out of the export at test time and asserts they match
`PPColors`, in both palettes - so a re-export that changes a colour **fails the
build** rather than leaving the app quietly disagreeing with its own design. It
also fails on a token the app does not map, because a new token is a decision
that has not reached the app yet.

The export is declared as an input of the test task. Without that, changing
*only* the export - exactly what a re-export does - leaves `testDebugUnitTest`
`UP-TO-DATE` and the test never runs.

### Taking a re-export

A re-export replaces these files, and nothing else in the repository:

```
design/Shotgun.dc.html          the app design - the spec
design/Shotgun Logo.dc.html     the identity spec
design/_ds/modernist-…/         the bound design system, if it moved
design/support.js               export scaffolding
design/android-frame.jsx
```

Then run the unit tests. `DesignTokenTest` is the gate, and **a failure there is
a decision, not a defect**:

- The design changed a colour on purpose → update `PPColors` to match.
- The export reverted something decided here → re-apply it **in the Claude
  Design project**, not in the file. Editing the file is how it gets lost again.
- A new token appeared → decide whether the app adopts it, then map it or take
  it out upstream.

### What is not part of this

Claude Design's `/design-sync` does **not** apply to this repository, and no
design authorization is needed to work on it. `/design-sync` syncs
design-*system* projects, pushing a local component library up to one. This repo
*consumes* a design system - Modernist, bound as
`design/_ds/modernist-f7022762-…/` - and the app is Kotlin, not a component
library, so there is nothing here to push.

Everything above works against the files already in the repository:
`DesignTokenTest` reads `design/Shotgun.dc.html` off disk and needs no account,
no login and no network.

## Security

| Piece | Where |
| --- | --- |
| Policy, scope, how to report | [`SECURITY.md`](https://github.com/drehtuer/ShotgunApp/blob/main/SECURITY.md) |
| Dependency updates | [`.github/dependabot.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/dependabot.yml) |
| Code scanning | [`.github/workflows/codeql.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/workflows/codeql.yml) |

Repository settings, all on: private vulnerability reporting, Dependabot alerts
and security updates, secret scanning with push protection.

### Workflow hardening

**Third-party actions are pinned to a full commit SHA**, with the version in a
trailing comment:

```yaml
uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0
```

A tag is a moving pointer that the action's owner can repoint at any commit, so
`@v6` is a standing instruction to run whatever that account publishes next. A
SHA cannot move. Dependabot updates these in place - it rewrites both the SHA
and the comment - so pinning costs nothing in maintenance. `actions/*` and
`github/*` are left on tags: they are GitHub's own, published from the same
platform that runs them.

Pinning does have one cost, and it was paid on 2026-09-19: **a pin holds a
broken version just as firmly as a good one.** Google removed the obsolete
`tools` package from the SDK repository, `setup-android` v4.0.1 still asked
`sdkmanager` for it, and every workflow that sets up the SDK began failing at
that step. Upstream fixed it in v4.0.2 within two days, and Dependabot raised
the bump - but the pin meant the repository stayed broken until that pull
request was merged, where `@v4` would have healed itself. The lesson is not to
stop pinning; it is that **a red Dependabot PR against a pinned action is the
fix, not noise**, and leaving it open leaves CI broken.

**Write permissions are granted per job, not per workflow.** `docs.yml` is the
only workflow that needs any: it is `contents: read` at the top, and `pages:
write` plus `id-token: write` on the `deploy` job alone. The job that builds the
site runs Gradle, Jekyll and a third-party action, and none of that should be
able to publish.

**Dependency verification is deliberately not used.** Gradle can checksum every
artifact through `gradle/verification-metadata.xml`, and SonarQube asks for it.
Generating it here produces 3,745 lines covering 536 components - and any
dependency change invalidates it. Dependabot cannot regenerate it, so every
weekly Gradle pull request would fail until someone regenerated the file by
hand and pushed it, which is exactly the automation `dependabot.yml` is built
around. Recorded as a decision on [`TODO.md`](TODO.md) rather than taken
quietly.

**Dependabot deliberately does not raise AGP or the Gradle wrapper.** They move
together with `compileSdk` and the Kotlin version - and AGP 9 removed the
`kotlin-android` plugin - so an automated bump breaks the build rather than
updating it. Those are raised by hand, against the notes above. AndroidX and
Compose artifacts are **grouped** into one PR each, because they are released in
step and a per-artifact PR could not pass CI on its own.

**CodeQL scans the workflows as well as the Kotlin.** The workflows hold the
signing-key handling and the release path, which is where a mistake is least
visible and, with immutable releases, least recoverable. It runs weekly as well
as per PR, so new queries reach existing code and not only changed code.

The two languages need different treatment. **Kotlin is extracted by the
compiler as it runs, so it needs a real build** - `build-mode: manual` with
`assembleDebug`. `build-mode: none` is Java-only: it was tried first, completed
without error and produced an empty database, failing at the finalize step with
"CodeQL could not process any code written in Java/Kotlin". Workflows are YAML,
so `actions` uses `build-mode: none` and skips the toolchain entirely.

It builds `debug` rather than `release`: the same sources, without R8 rewriting
them into something the analysis has to see through.

**Caching is disabled for that build, deliberately.** CodeQL extracts Kotlin by
tracing the compiler as it runs, so a task served from the build cache
contributes nothing. The second attempt failed exactly this way: `39 actionable
tasks: 22 executed, 17 from cache`, `BUILD SUCCESSFUL`, and an empty database -
the same "could not process any code" error as a wrong build mode, from a
completely different cause.

**Code scanning is not a required check**: its findings are advisory, and a
required one would block unrelated merges on an alert nobody has triaged yet.
