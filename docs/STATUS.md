# Status

What has been done and how it turned out. Newest first. Entries keep the parts
that went wrong - a bug someone hit and fixed is worth more to the next person
than a clean summary that hides it.

See [`TODO.md`](TODO.md) for what is still open.

## Current state

**Built, verified on the phone, and released.** All four screens work, the draw
history and settings persist, and seven rounds of device feedback settled the
timing, the reveal, the dim level and the haptics. `v0.1.4` is published and the
documentation site is live. What is left is a fourth draw mode and polish.

| Area | State |
| --- | --- |
| Gradle project, Compose setup, Modernist theme | done |
| Navigation across all four screens | done |
| Home, Draw surface, Result, Settings | done, verified on the phone |
| Draw history database, settings persistence | done |
| Identity: name, logo, launcher icon | done |
| Devcontainer: SDK, emulator, adb over Wi-Fi | done |
| CI: build, unit tests, lint, coverage to SonarQube | done |
| Release: signed artifacts on a `v*` tag | `v0.1.4` published |
| Documentation site | live at [drehtuer.github.io/ShotgunApp](https://drehtuer.github.io/ShotgunApp/) |
| COLOURS draw mode | not started - blocked on one decision |

---

## 2026-10-05 — Release 0.1.4

`appVersion` 0.1.3 → 0.1.4, so `versionCode` derives to **104**.

Nothing new for the user to see. This is a maintenance release, so the
published build matches what `main` has been running since 0.1.3:

- **Dependencies brought up to date.** Kotlin 2.4.20, Compose BOM 2026.09.00,
  Navigation 2.10.2, Room 2.8.5, core-ktx 1.19.1, KSP 2.3.12 and Gradle 9.8.0.
- **The draw surface's input handling moved into `draw/SurfaceInput.kt`.** It
  was meant as a pure refactor, and on the Pixel 10a every finger still gets a
  ring, the rings follow moving fingers, the result outlives the hand, and
  tapping ← MODES lands no ring.
- **The intermittent test hang recorded at 0.1.3 is fixed.** Each test now gets
  its own settings store.
- **CI is green again** after `setup-android` asked for the SDK package that
  Google removed. `setup-gradle` also moved to 6.4.0.
- **Unit tests: 224, covering 97.1% of lines and 83.4% of branches.**

---

## 2026-10-04 — Tests pruned, and the draw surface reached from the JVM

The unit suite was audited for duplicates and the coverage gaps were closed
where the uncovered code was real behaviour. **209 → 224 tests. Coverage went
from 93.1% to 97.1% of lines and from 76.3% to 83.4% of branches.**

### What was removed

Fourteen tests were removed. Coverage is the same without them, line for line
and file for file. Most asserted the same path a second time: three settings
fallback and clamp tests that had twins in the same file, two
`ShotgunViewModelTest` clamps that only re-tested `normalise` (already pinned
in `DrawRecordTest`), and three `NavHostRenderTest` cases that `NavBackTest`
already walks. Two are worth naming:

- "stepping the countdown three times moves it three half seconds" never called
  app code. It stepped a lambda defined inside the test. The bug its comment
  cites is pinned by `SettingsRepositoryTest`'s "steps in quick succession all
  land".
- "moving does not buzz" asserted that `onMove` returns no effect, but `onMove`
  returns `Unit`, so the compiler already guarantees it.

### The draw surface, which "nothing here can drive"

`DrawScreen.kt` was the largest gap: 66 lines and 67 branches. The documented
conclusion was that pointer injection lands nothing under Robolectric, and that
still holds. Instead of injecting pointers, the logic was moved out to where
tests can reach it:

- `draw/SurfaceInput.kt` holds `routePointers`, which filters press, move and
  release. It also holds the glow and frame arithmetic. The filtering is
  unchanged. A release is the one case not filtered on consumed changes, and
  that now has a comment saying so.
- `DrawScreen` now builds the engine and hands it to
  `internal DrawSurface(engine, …)`. `DrawSurfaceTest` passes in an engine that
  already has fingers down and a 1ms countdown. From there the countdown,
  staged reveal, instant reveal and refusal timeout all run on the fake clock,
  with no pointers involved.

Five lines are still uncovered: the pointer loop's mapping of event types, and
the finger-tick haptic. **This touches the multi-touch path, and it has not been
on the phone yet.** It is meant as a pure refactor, but CLAUDE.md requires a
phone check for multi-touch changes before they count as done.

### A second blind spot: Robolectric does not draw

`EdgeGlow`, the mark in the wordmark and the fairness field all showed their
`Canvas` bodies as uncovered, even in tests that rendered them. Robolectric's
default graphics mode never runs the draw phase. Under `@GraphicsMode(NATIVE)`
with `captureToImage()` it does. The new tests check real pixels: the claimed
dot is accent and the other three are hollow rings, and a cluster of winners in
the top left heats the top left but none of the mirrored corners, which catches
a transposed or flipped field.

### Other gaps closed

- The system bar icons follow the app's theme setting, not the phone's, except
  under SYSTEM.
- Haptics with no vibrator, and with haptics off, where the vibrator is not
  even looked up.
- `navigateOnce`'s double-tap guard. The test was checked against the guard
  disabled, and it fails then.
- A last draw whose fingers have no team assignment.

Not covered, on purpose: Kotlin-generated default-argument and null-check
branches, a `ShotgunNavHost` fallback that the route type makes unreachable,
and the pre-Android-12 vibrator path. That one is real behaviour, but it needs
a second Robolectric SDK jar, so it is in [`TODO.md`](TODO.md).

### How the work was done, and what got in the way

Three agents worked in parallel in separate git worktrees: one removed
duplicates, one worked on the draw surface, and one closed the remaining gaps.
The results were merged locally and verified together. The host had no Android
SDK (only `platform-tools`), so Gradle ran in the devcontainer image against
the existing `playerpicker-*` SDK and Gradle volumes. Only about 3 GB of RAM was
free, so builds were serialised behind a file lock rather than run in parallel.
That made the agents wait for each other, but none ran out of memory. The three
branches merged without conflicts.

---

## 2026-09-28 — Code scanning was red for nine days, and the fix was already open

Every workflow that sets up the Android SDK had been failing since
2026-09-19. The fix was sitting in an unmerged Dependabot pull request the
whole time.

### What broke

`android-actions/setup-android` runs `sdkmanager tools` as part of its setup.
Google removed `tools` - obsoleted years ago - from the SDK repository, so the
call started failing:

```
Warning: Failed to find package 'tools'
Error: The process '.../sdkmanager' failed with exit code 1
```

Nothing in this repository changed. The step that broke is third-party setup,
not anything the build does, which is why the failure looked inexplicable from
the commit history.

### Why only *some* checks went red

This is the part worth remembering. `pr.yml` **does not use
`setup-android`** - it relies on the SDK baked into the runner image - so
Build, Unit tests and Static analysis all stayed green. Only Code scanning
failed, and code scanning is deliberately not a required check, so pull
requests still looked mergeable. Two Dependabot PRs were merged into `main`
during the nine days with a red CodeQL run on them.

`docs.yml` and `release.yml` do use the action and were **also** broken -
silently, because both are tag-only and neither had run since 2026-09-12. Had
this not been caught, the next `v*` tag would have failed at the release step:
the worst place to discover it, since a release is the one thing that cannot be
quietly retried.

### The fix, and the awkward part

Upstream fixed it in v4.0.2 on 2026-09-17 - release note: *"Fix for removed
tools package."* Dependabot raised the bump to v4.0.4 as PR #56 on 2026-09-21,
CI went green on it, and it then sat open for a week while the weekly scheduled
scan kept failing.

So the repair here is the bump Dependabot already wrote, applied to all three
workflows, plus the documentation that pull request could not add. Pinning
actions to a commit SHA is still right - but it means an upstream fix does not
arrive until someone merges it, and a red Dependabot PR against a pinned action
is the fix rather than noise. That is now written down in
[`build-environment.md`](build-environment.md#workflow-hardening).

### What was verified, and what was not

The Gradle gates were **not** run for this change, and could not be: the work
happened on the WSL host rather than in the devcontainer, which has no Android
SDK and only a JRE, so `assembleDebug` fails at SDK setup before it compiles
anything. They would also prove nothing here - the change touches workflow YAML
and Markdown, and no Kotlin, Gradle configuration or resource. What was checked
locally is that all four workflow files still parse as YAML and that the new SHA
is the commit `android-actions/setup-android` v4.0.4 actually points at.

The verification that counts is CI on the pull request: Build, Unit tests and
Static analysis run the suite, and the CodeQL run exercises the fixed
`codeql.yml` end to end. `docs.yml` and `release.yml` carry the identical
one-line change but are tag-only, so they are fixed by inspection rather than by
a run - noted on [`TODO.md`](TODO.md) so the next tag gets watched.

---

## 2026-09-12 — The flaky test, and what was actually wrong

The intermittent hang recorded at 0.1.3 is fixed, and the cause was not where
the failure pointed.

### It was reproduced, which changed the fix

The CI failure said `UncompletedCoroutinesError: After waiting for 1m, the test
body did not run to completion` in `ShotgunViewModelTest`, and that test would
not fail again - six runs of the class, three of the whole suite on two CPUs.
Guessing from the stack trace would have produced a `withTimeout` and a shrug.

So the *suspicion* was tested instead of the symptom. A throwaway probe wrote
`haptics = false` in one test method and read the setting in the next:

```
PROBE-A: wrote haptics=false
PROBE-B: haptics=false   (true = isolated, false = LEAKED from test a)
```

That is not something a fresh install could ever do, and Robolectric had reset
the filesystem in between. **`preferencesDataStore` caches one store per
process** - the delegate holds it, not the `Context` - so every test in the JVM
was sharing one store and one internal scope, `ShotgunViewModelTest` and
`SettingsRepositoryTest` included.

The evidence was in the repository all along, written as a habit rather than
read as a defect: `SettingsRepositoryTest` carried the line *"Every test writes
what it needs before reading it, because the store outlives a single test
method."* Someone met this, worked around it, and wrote the workaround down.

### The hang, then

Two halves. The shared store is one; the other is that it runs on
`Dispatchers.IO`, so its work lands on real threads while `runTest` drives a
virtual clock. A resumption crossing between the two is exactly what a test body
that never completes looks like - and why it needed a loaded CI runner to show
up.

### The fix

`SettingsRepository` takes a `DataStore<Preferences>`. The `Context` constructor
is kept, so the app is unchanged and still gets the singleton it wants. Tests
build one through `IsolatedSettingsStore`, which gives each test its own file
*and* runs the store on the test's own dispatcher, so a write and the read
waiting for it are on one clock.

`SettingsStoreIsolationTest` keeps it honest: the probe, kept as a test. It is a
test about the tests, and it earns its place - it fails against the shared store
with `expected:<true> but was:<false>`, which is how the defect was found.

### Verified

209 tests, up from 207, zero failures. Five full runs pinned to two CPUs, all
green; `lint`, `assembleDebug` and `assembleRelease` clean.

What cannot be claimed: that the original hang is gone, because it was never
reproducible to begin with. What can be claimed is that the coupling underneath
it was real, was demonstrated, and is gone - and that if it returns, a named
test fails on it rather than a different test hanging for a minute.

---

## 2026-09-12 — Release 0.1.3

`appVersion` 0.1.2 → 0.1.3, so `versionCode` derives to **103**. One value
changed; the tag has to match it or `release.yml` refuses the build.

Everything since 0.1.2, in the order it landed:

- **The starter buzz was never played.** Android classified it as touch
  feedback and dropped it on any phone with *Touch feedback* switched off - so
  the one buzz that announces the result was silent for those users. Cut into
  steps so the classifier no longer recognises it, identical to the hand.
- **HAPTICS is the only switch.** The per-finger tick went the same way, after
  the first fix deliberately left it to the system setting. Two phones were
  behaving differently with nothing on screen to explain why; now HAPTICS on
  gives ticks *and* buzzes, off gives neither.
- **The app no longer backs itself up.** `allowBackup="false"`, from
  SonarQube's security findings. Nothing recorded carries a name or an
  identifier, so this is not about a secret - it is that backup copies the
  draw history into a cloud account and into reach of `adb backup`. It costs
  what it costs: **replacing the phone starts the fairness field from empty.**
- **Clear-text traffic is explicitly refused**, which only states what was
  already true - the app asks for no INTERNET permission.
- **Supply chain tightened.** Third-party actions pinned to commit SHAs rather
  than moving tags, and the Pages write permissions scoped to the single job
  that publishes instead of the whole workflow.
- **Coverage went 68% to 94.6% line, 76.2% branch**, and moved from Codecov to
  SonarQube Cloud, which reports the two figures separately.
- **Back no longer empties the graph.** Backing out twice from a stack one
  deep used to pop the last destination and leave a live, blank window;
  `popSafely` guards it, and seven navigation tests hold it there.
- The lockup now opens the README, and `keystore.properties` moved in beside
  the keystores it names.

### A flaky test, not a broken release

The first CI run on the release PR went red on
`ShotgunViewModelTest > settings written through the view model are read back
through it`: `UncompletedCoroutinesError: After waiting for 1m, the test body
did not run to completion`. The body hung, so `viewModel.settings.first { … }`
never saw a value matching all four writes.

It was checked rather than waved through. The branch changes `appVersion`,
`README.md` and this file - no test references the version, and no code
changed. `main` was green on the commit underneath. The test would not
reproduce: six runs of the class and three of the whole suite pinned to two
CPUs, all green. So: a genuine intermittent, unrelated to the release, and
on [`TODO.md`](TODO.md) with the suspected mechanism rather than quietly
re-run until green.

### What a user actually gets

Two haptics fixes and one behaviour change. The rest is tests, tooling and
documentation - worth a patch release because the haptics bugs were silent
ones, and a phone that never buzzed gave no sign that anything was wrong.

---

## 2026-09-12 — SonarQube's security findings: 14 of 16 fixed

SonarQube reported 16 security findings and no hotspots. Fourteen are fixed
here; the remaining two are one decision, recorded rather than taken quietly.

### Third-party actions pinned to a commit SHA (10 findings)

`gradle/actions/setup-gradle`, `android-actions/setup-android` and
`softprops/action-gh-release` were on `@v6` / `@v4` / `@v3`. A tag is a moving
pointer the action's owner can repoint at any commit, so that is a standing
instruction to run whatever that account publishes next. Now pinned to the SHA
each tag pointed at, with the version in a trailing comment so Dependabot keeps
updating them - it rewrites the SHA and the comment together.

Worth noting what the rule does **not** flag: `actions/*` and `github/*` stay on
tags, because they are GitHub's own, published from the platform that runs them.
The rule draws the line at the supply chain that is actually third-party, and
that is the right line.

### Pages write permissions scoped to the job that publishes (2 findings)

`docs.yml` granted `pages: write` and `id-token: write` at workflow level, so
the *build* job held them too - a job that runs Gradle, Jekyll and a third-party
action. Now `contents: read` at the top, with the two write scopes on `deploy`
alone.

### The manifest: no backup, no clear text (2 findings)

`allowBackup` is `false` and `usesCleartextTraffic` is explicitly `false`. The
second is free: the app asks for no INTERNET permission, so this only states
what is already true and makes a future accidental `http://` call fail loudly.

The first is a real behaviour change and is written down as one. No row carries
a name or an identifier - a draw is a mode, a timestamp and finger positions -
so it is not about protecting a secret; it is that backup copies them into a
cloud account and into reach of `adb backup`. **The cost is that replacing the
phone now starts the fairness field from empty**, and normalising positions was
exactly what would have let a restored history stay meaningful. In
[`design.md`](design.md#what-gets-recorded) now, because that is behaviour.

**A rationale that had to be corrected.** The first version of the manifest
comment justified the change by claiming the history has "no value off the
phone". `design.md` says the opposite in as many words - positions are
normalised so records "stay comparable across devices and orientations" - so the
justification was false and was rewritten to state the trade honestly instead. A
tidy reason for a change is worth less than a true one.

### The two left open, and why

`kotlin:S6474` and `text:S8569` ask for Gradle dependency verification.
Generating `gradle/verification-metadata.xml` was measured rather than guessed
at: **3,745 lines, 536 components, 42 seconds**. The file is not the problem.
The problem is that any dependency change invalidates it and Dependabot cannot
regenerate it, so every weekly Gradle pull request would fail until someone
regenerated it by hand - against the grouping in `dependabot.yml` that exists so
those PRs pass CI on their own. On [`TODO.md`](TODO.md) as a decision, with the
numbers.

### Verified

`assembleDebug`, `lint`, `testDebugUnitTest` and `assembleRelease` green; all
four workflow files parse as YAML. The manifest was checked where it counts
rather than in the source - `aapt2 dump xmltree` on the release APK reports
`allowBackup=false` and `usesCleartextTraffic=false` as shipped.

One self-inflicted bug on the way: rewriting the manifest comment left the
`-->` in the middle of it, putting the second half outside the comment and the
XML in an invalid state. Caught by parsing the file rather than reading it.

---

## 2026-09-12 — `keystore.properties` moved into `keystore/`

A one-line change to where a gitignored file lives, for one reason: everything
secret is now under a single directory. The keystores were already in
`keystore/`; the file naming them and carrying their passwords sat in the
repository root, so the rule for "do not commit this" had two halves, and the
half at the root looked like every other root config file.

`app/build.gradle.kts` reads `keystore/keystore.properties` now. The `storeFile`
values inside it are unchanged and stay relative to the **repository root** -
that is what Gradle resolves them against, and the same paths the CI environment
variables use, so nothing about the workflows changed.

`.gitignore` already covered it twice over: `keystore/` matches the directory,
and the bare `keystore.properties` pattern matches the name at any depth. Both
kept. The second is redundant for the new path and deliberately so - a stray
copy at the repository root is exactly the mistake worth catching twice, and
this is the one failure the project cannot recover from.

### Verified by what the APKs are actually signed with

The failure mode here is silent by design: when the properties file is not
found, the build does not fail - debug falls back to the SDK's own debug key and
release comes out unsigned. So a build succeeding proves nothing. `apksigner`
was asked instead:

```
debug:   CN=Shotgun! Debug, OU=Development, O=drehtuer, C=DE
release: CN=Shotgun!, OU=Release, O=drehtuer, C=DE
```

Both are the project's own keys, read from the new location. `lint` and
`testDebugUnitTest` green alongside.

**A checking mistake worth keeping:** the first pass grepped apksigner's output
for `Signer #1 certificate DN`, which it does not print - the line reads
`V2 Signer: certificate DN`. Nothing matched, and the release APK was written
down as unsigned. It was correctly signed all along; the check was looking for a
string that never existed. A grep that finds nothing looks exactly like the bug
it was written to catch.

---

## 2026-09-12 — The lockup at the top of the README

`assets/logo/shotgun-lockup-dark.png` now opens `README.md`, above the `#
Shotgun!` heading. The exported lockup is used as it is: the dark plate carries
its own clear space, so the brand don'ts - no rounded corners, no third colour,
no drop shadow - are respected by not touching it. Nothing new was drawn, and
nothing was recoloured; there is one lockup in the export and this is it.

**The heading stays.** It is tempting to drop it, since the lockup already reads
SHOTGUN! - but `jekyll-titles-from-headings` takes the page title from the first
heading, and without the `<h1>` the site title would become *Status*, the next
heading down. It is also the only text form of the name for a screen reader, so
the image carries an `alt` and the heading stays where it is.

An `<img>` tag rather than `![]()`, for the `width="560"` alone: the export is
1668px wide and would otherwise fill the column. `assets/css/site.css` already
has `img { max-width: 100% }`, so it still scales down on a phone.

### Verified against the real toolchain, not by eye

The path is relative, which had to work in two places at once - GitHub resolves
it from the repository root, the site from `/ShotgunApp/`. Rather than reason
about it, the site was built with the same `ghcr.io/actions/jekyll-build-pages`
image CI uses:

- `<title>Shotgun!</title>` - titles-from-headings unaffected
- the `<img>` rendered with the relative `src` intact
- `_site/assets/logo/shotgun-lockup-dark.png` published, 62 KB
- `<h1 id="shotgun">Shotgun!</h1>` still present

`assets/` is not in the `_config.yml` exclude list, and the live site already
served this exact file - the favicon and `site.css` come from the same tree - so
the relative link is the correct one here rather than an absolute GitHub URL.

---

## 2026-09-12 — Coverage moved from Codecov to SonarQube Cloud

Same JaCoCo report, a different service reading it. Codecov is gone: no
`codecov.yml`, no upload step, no badge. SonarQube Cloud analyses the project
instead, and the README now carries **two** coverage badges - line and branch -
rather than one blended figure.

The documentation was swept for the old service afterwards, which turned up a
**dead link**: an entry below pointed at `build-environment.md#codecov`, and
that section is *SonarQube Cloud* now, so the anchor resolved to nothing. The
dated entries still name Codecov where they describe what happened on the day -
this log is a record, and one that hid the tool actually in use would be a
falsified one - but anything written in the present tense was put into the past,
so nothing reads as though Codecov is still in play.

### How it is wired

The scan runs from Gradle (`org.sonarqube` 7.5.0.8588, applied at the root), not
from a scanner action, because the plugin already knows where an Android
variant's sources, classes and test sources are. The PR workflow's test job
gained one guarded step, `./gradlew sonar`, after the step that produces the
classes and the coverage XML.

Keys are in `build.gradle.kts` - project `drehtuer_ShotgunApp`, organisation
`drehtuer` - and the token is the `SONAR_TOKEN` repository secret. Absent it the
step is skipped rather than failed, the same shape the Codecov upload had, so a
fork's pull request still goes green.

### Three things that would have failed quietly

- **The JaCoCo path has to be given by hand.** AGP's
  `createDebugUnitTestCoverageReport` is not a `JacocoReport` task, so the
  scanner's auto-detection finds nothing and the analysis reports 0% while
  succeeding. `sonar.coverage.jacoco.xmlReportPaths` is set explicitly in
  `app/build.gradle.kts`.
- **The checkout was shallow.** SonarQube dates lines from git blame, and that
  is what decides what counts as *new code* on a pull request; with the default
  single-commit clone every line looks new. `fetch-depth: 0` now.
- **The exclusions were written, then deleted.** `codecov.yml` ignored Room's
  generated `*_Impl` sources, so the same list went into the Sonar config - and
  then a property dump (`-Dsonar.scanner.internal.dumpToFile`) showed
  `sonar.sources` is `src/main/{java,res,AndroidManifest.xml}` and nothing else.
  Generated code was never in the analysis to exclude. Config that does nothing
  is worse than no config: it claims a problem is handled.

### Automatic Analysis had to be switched off, and it failed the first run

SonarCloud turns Automatic Analysis on when it imports a repository, and it
**refuses a CI analysis while that is on**: *"You are running CI analysis while
Automatic Analysis is enabled."* The first run failed on exactly that, after the
tests had passed.

It is worth knowing what the automatic one was reporting in the meantime,
because it looked like it was working: a green *SonarCloud Code Analysis* check
on the pull request, `ncloc` counted - and **no coverage metric at all**, since
it never runs the tests. A green check that measures nothing is the failure mode
to watch for here.

Switched off under *Administration > Analysis Method*, the CI analysis went
through on a re-run.

### The numbers, and how they were checked

**Line 94.6%, branch 76.2%**, overall 89.3%. The line figure is the same 94.6%
the last coverage pass recorded through Codecov, which is the check that
matters: both services are reading the same JaCoCo report, and they agree.

Before CI, in the dev container: `assembleDebug`, `lint`, `testDebugUnitTest`,
`createDebugUnitTestCoverageReport` and `assembleRelease` all green, and
`./gradlew sonar` resolving against AGP 9 with the right variant, sources and
binaries. On the pull request: all seven checks green, coverage on the dashboard.

The badges are served by shields.io, not by SonarQube's own badge API, which
accepts only `coverage` - neither `line_coverage` nor `branch_coverage` is in
its list. They read *metric not found* until this merges, because they are
pinned to `main` and only the pull request has been analysed so far.

---

## 2026-09-08 — HAPTICS is the only switch

A decision, made against what the last change recorded as deliberate.

Fixing [#39](https://github.com/drehtuer/ShotgunApp/issues/39) established that
Android takes a short, simple vibration for touch feedback and drops it on a
phone with *Touch feedback* switched off. The result buzzes were cut into steps
to get past that; **the tick was deliberately left to be dropped**, on the
argument that a per-finger tick really is feedback for touching and belongs to
the system's setting.

Overruled, and rightly: it made the app's own toggle mean two different things
on two phones - buzzes but no ticks on one, both on the other - with nothing on
screen to explain the difference. HAPTICS on now gives ticks **and** buzzes;
HAPTICS off gives neither.

The tick goes through the same `spread`, so it leaves as `[3, 0, 3, 0, 3, 0, 3]`:
seven steps, 12 ms of buzz, gaps of nothing between the pieces. Identical to the
hand, invisible to the classifier.

### Verified on the phone

A finger landed on the draw surface by `adb shell input tap`, and the history
agrees:

```
23:20:15 | finished | usage: UNKNOWN |
played: [3ms@1.00, 0ms, 3ms@1.00, 0ms, 3ms@1.00, 0ms, 3ms@1.00]
```

Before this it read `ignored_for_settings | usage: TOUCH`, which is the same
line the starter buzz used to have.

**A checking mistake worth keeping:** the first two attempts looked like the
tick had not fired at all, because `grep shotgun | tail -1` returns the last
line *in the file*, and `dumpsys` groups vibrations by usage - `UNKNOWN` is
printed before `TOUCH`, so a working tick lands in the middle of the dump and a
dropped one at the end. The check was wrong, not the app, and it looked exactly
like the bug it was meant to catch.

---

## 2026-09-08 — Coverage, second pass: the draw surface, piece by piece

**Line coverage 86.6% → 93.0%, branch 70.5% → 76.3%, 181 tests → 207.**
Excluding generated Room sources, as Codecov counted it then: **87.3% → 94.6%**
line.
`DrawScreen` alone went **35% → 84%**.

### The surface's pieces are `internal` now, and that is the whole trick

The first pass concluded the draw surface could not be covered on the JVM
because pointer injection does not reach it. That was true and beside the point:
the surface cannot be *driven*, but its pieces can be *rendered*, each with the
state it would have had mid-draw.

`EdgeGlow`, `FingerRing`, `Hint`, `Refusal`, `RevealBar` and `DrawMode.label`
changed from `private` to `internal` - the only production change in this pass,
and no behaviour with it. Eleven tests then render a ring for every role a draw
can give a finger: an order ring showing its rank, a teams ring showing its
letter and the word TEAM, a ring whose turn has not come showing nothing at all,
and a lifted ring still carrying its answer - the rule that a label sits beside
the ring while the finger is down and slides into it when the finger lifts.

Two Robolectric traps cost a cycle each here, and both are the kind that read as
a broken assertion rather than a broken test:

- **A `Box` that wraps its content clips every ring out of view.** The rings
  place themselves by absolute offset, so the host has to fill the screen or the
  nodes exist and report "not displayed".
- **Robolectric's default screen is 320x470dp.** A ring at phone-sized
  coordinates lands off it, and a label drawn *beside* a ring near the top edge
  goes off it upwards.

### Back, which is where the worst bug lived

Seven tests on the navigation graph, all of which end by asserting a screen is
still on display - "did not crash" is not the property, "is not blank" is. They
cover back from every destination, back twice from a stack one deep (the pop
that used to empty the graph and leave a live, blank window), and `popSafely`
directly: false at the start destination, true behind it.

The one test that could not be written is the double tap. Compose's test
framework settles between clicks, so the first tap navigates and the second
finds its node gone - "the node is no longer in the tree", which is the guard
working and not something an assertion can say. Testing `popSafely` directly is
what replaced it.

### The rest

The stepper at its floor (the minus greyed rather than live and inert - a bug
this shipped with once), the mode motifs for all three modes, the reveal-timing
pills both ways, and a teams record with **no** team count, which is a row from
a build that did not store one and must read as something rather than crash the
screen that plots history.

### Verified on the phone

The visibility change is behaviour-neutral, but it is in the draw surface's
file, so the build went on the Pixel: **all three modes still track touch and
draw**. Cheap to check, and the one change nobody should take on trust.

### Still uncovered, and now the list is short

- **The pointer loop and the countdown's frame loop** in `DrawScreen` - 42
  lines. Nothing on the JVM can drive them; `app/src/androidTest/` does, on the
  phone.
- **`ShotgunMark`'s canvas.** Robolectric lays out but does not rasterise, so a
  `Canvas` draw lambda never runs.
- **Room's generated open delegate**, excluded from the coverage figure as
  generated code.

---

## 2026-09-08 — Coverage: the data layer, the view model and the window

**Line coverage 64.3% → 86.6%, branch 58.8% → 70.5%, 134 tests → 181.** As
Codecov reported it then - generated Room sources excluded - **73.2% → 87.3%**
line. Both figures are given because they differ, and the second is the one the
badge showed.

No production code changed. Everything below was already shipped and working;
none of it was checked by anything.

### What had no tests at all

| Area | Was | Why it mattered |
| --- | --- | --- |
| `RoomDrawHistory` + the DAO | 0% | The winners query joins two tables and orders by a column in the other one. No compiler checks SQL. |
| `ShotgunDatabase` | 0% | The real, file-backed open path - and there is deliberately no destructive-migration fallback, so an open that goes wrong takes the history with it. |
| `SettingsRepository` | 0% | `SettingsTest` covered what a stored preference *means*, never that a write lands. |
| `ShotgunViewModel` | 0% | `recordDraw` decides who won and where the fingers were, as a fraction of the surface. Both are written to the database and can never be recomputed. |
| `DimScreen` / `dimTarget` | 0% | Two wrong answers shipped before this one - a fixed level made an already-dim phone *brighter*. |
| `MainActivity` | 0% | Nothing covered the app actually starting. |

47 new tests across seven files. The ones worth naming:

- **The winners query is order-sensitive, so the test inserts out of order** -
  newest draw last - which is the only way to tell "ordered by the joined
  draw's timestamp" from "in insertion order".
- **Pruning is tested through the cascade**: the oldest draw is evicted and its
  points must go with it, or orphans accumulate for the life of the install.
- **`recordDraw` is tested per mode**, because who "won" differs in each -
  starter by id, order by rank 1, teams not at all - and against a degenerate
  surface, where positions must collapse to the centre rather than divide by
  zero.
- **Dim mode is tested for the failure it shipped with**: dimming a dim screen
  must not raise it, and must never blank it.

### Two traps, one of which crashes the compiler

**A test in `ui.components` cannot use `@Rule`.** The app has a `Rule()`
composable - the 2px line the design is built from - and in that package the
name also has to serve as JUnit's annotation. It does not produce an error. It
crashes the Kotlin backend outright:

```
Backend Internal error: Exception during IR lowering
  The root cause java.lang.NullPointerException was thrown at:
  JvmAnnotationImplementationTransformer$AnnotationPropertyImplementor
```

- naming only the file, no line, no symbol. It took a bisect of one test file
to find. `ComponentRenderTest` now lives one package up **and** aliases the
import, so either half would do and nobody has to rediscover it.

**Compose's pointer injection does not reach the composable under
Robolectric.** Two attempts - freezing the clock and pumping frames, then
letting it auto-advance - both landed zero rings from a `down()` that works on
the phone. Advancing `System.currentTimeMillis` with `ShadowSystemClock` did not
help, because nothing was tracking pointers to begin with. So the draw
surface's touch paths stay device-only, which is where they were already, and
`DrawScreen`'s 35% is not a gap anyone should try to close on the JVM.

### What is still uncovered, deliberately

- **`DrawScreen`, 166 lines.** The multi-touch surface. Its decisions live in
  `ringSpec`, which is pure and tested; what remains is drawing and pointer
  plumbing, covered by `app/src/androidTest/` on the phone.
- **Room's generated open delegate**, the schema-creation half. Excluded from
  the coverage figure as generated code, and a test for it would be a test of
  Room.
- **`ShotgunMark`'s canvas.** Robolectric lays composables out but does not
  rasterise them, so a `Canvas` draw lambda never runs. Only a real screen can
  cover it, and only an eye can judge it.

---

## 2026-09-08 — The starter had no buzz ([#39](https://github.com/drehtuer/ShotgunApp/issues/39))

Reported from the phone: with HAPTICS on, order and teams buzz when the result
lands and **starter does not**.

### It was never played — Android dropped it before the vibrator saw it

The three modes share one code path, so nothing mode-specific could swallow the
call, and reading the code only said the effect handed to the vibrator was
different: `createWaveform([0, 90])` for starter against `[0, 90, 60, 90]` for
the other two. **Two theories were wrong before the phone settled it**, and both
were plausible enough to ship:

1. *The single-step waveform is the odd shape out* — so send a one-shot instead.
2. *A 90 ms buzz is too short to survive several hands damping the phone* — so
   pad it and raise the amplitude.

`dumpsys vibrator_manager` keeps every vibration the device has been asked for,
with a status. It showed something neither theory predicted: **the app's own
finger tick was logged under `usage: TOUCH` and `ignored_for_settings`**, and
in three days of history there was not one `[0, 90]` entry — the starter buzz
had never reached the vibrator at all.

The app calls `Vibrator.vibrate(effect)` with no `VibrationAttributes`, so the
usage is `UNKNOWN` and the framework *guesses* one. Two probes through
`cmd vibrator_manager -u 0`, which sends as `UNKNOWN` from the shell, pinned the
rule:

| Effect | Steps | Duration | Outcome |
| --- | --- | --- | --- |
| `[0, 400]` | 2 | 400 ms | ignored, re-classified `TOUCH` |
| `[0, 30, 0, 30]` | 4 | 60 ms | played, stayed `UNKNOWN` |

**It is the step count, not the duration.** An unknown vibration of three steps
or fewer is taken for haptic feedback, and this phone has *Touch feedback*
switched off in Android's Vibration & haptics settings, so it is dropped. The
double buzz has four steps and survived by accident; the starter's two never
stood a chance. The second theory's fix — padding to 200 ms — was built,
installed, and **failed on the phone exactly as the first would have**: still
two steps, still `TOUCH`, still ignored.

### What changed

`Haptics.spread` cuts a short pattern into more steps than the heuristic
accepts, separating the pieces with **zero-length** gaps so the vibrator plays
them back to back. The starter's `[90]` goes out as `[23, 0, 23, 0, 22, 0, 22]`:
seven steps, 90 ms of buzz, no silence in it. What the hand feels is unchanged;
only what the framework counts is.

That also caught a regression the rewrite had introduced. Writing the patterns
as `design.md` writes them — `[90, 60, 90]`, on-durations first — dropped the
old leading zero and took the double buzz from four steps to **three**, which
would have silenced order and teams too. `spread` covers every result pattern,
and a test asserts the step count for all three modes rather than for the one
that was reported.

### Verified on the phone, twice, and objectively

The failed attempt and the fix were both installed as **release** builds over
the top of the existing one - the phone carries the released app, not a debug
build, and the release key is local, so the draw history and settings survived
each install. `adb install -r` of a debug APK would have needed an uninstall.

- Starter draw, after the fix: felt, and the history agrees —
  `finished | usage: UNKNOWN | played: [23ms@1.00, 0ms, 23ms@1.00, 0ms, 22ms@1.00, 0ms, 22ms@1.00]`.
- **Order and teams re-checked on the phone**, because the split changed their
  step list too — both still buzz, and unchanged to the hand. Worth the second
  draw: this branch had already broken them once, silently, on the way past.
- Nine unit tests under Robolectric assert what the app actually sends the
  vibrator: the step count for every mode, that splitting preserves the buzzing
  and the silence exactly, and that a pattern with enough steps is left alone.
  124 unit tests became 134.
- `assembleDebug lint` clean, `assembleRelease` builds.

### Found on the way: the finger tick is off on this phone

The tick is a single 12 ms step, so it is classified as touch feedback and
dropped for the same reason — **on a phone with Touch feedback off, HAPTICS on
gives the result buzzes but no per-finger tick.** Left that way deliberately:
the tick *is* feedback for touching and belongs to the system's setting, while
the result is the app's answer and belongs to the app's toggle. Written down in
[`design.md`](design.md#haptics-is-the-only-switch) so it is a
decision rather than a surprise, and on [`TODO.md`](TODO.md) as a question
someone may want answered differently.

### Lesson

**"The code paths are identical, so the difference must be in the effect" was
right, and still produced two wrong fixes.** Neither was testable on the JVM:
Robolectric records what the app *asks* for, and the app asked correctly every
time. The device kept the answer in a log nobody had looked at - the fix took
minutes once `dumpsys vibrator_manager` was read, after an hour of reasoning
about actuators and waveform shapes.

Gradle ran in the `shotgun-dev` image against the devcontainer's own volumes,
because the WSL2 host still has no JDK - the recipe, and the two ways it fails,
are now in
[`build-environment.md`](build-environment.md#building-in-the-devcontainer-image-from-outside-the-devcontainer).

## 2026-09-07 — Release 0.1.2

`appVersion` 0.1.1 → 0.1.2, so `versionCode` derives to **102**. One value
changed; the tag has to match it or `release.yml` refuses the build.

Everything since 0.1.1, in the order it landed:

- **The palette is bound to the design export.** `DesignTokenTest` reads the
  nine `--pp-*` tokens out of `Shotgun.dc.html` at test time, so a re-export
  that changes a colour fails the build rather than disagreeing quietly.
- **The hardware finger limit is documented.** The Pixel 10a's digitizer
  reports ten simultaneous contacts; the app adds no cap of its own, and
  deliberately shows no finger count, because it cannot read one.
- **The fairness field is rasterised off the main thread**, keeping the
  previous bitmap up until the new one is ready.
- **Rank labels slide into the ring when a finger lifts**, which is the only
  placement guaranteed to be readable and on screen.
- **The suspense churn is dropped** - decided against rather than left open.
- **Licensed GPL-2.0-or-later**, the "or later" resolving the incompatibility
  with the Apache-2.0 dependencies.
- **Coverage went 17% to 68%**, mostly by measuring what was already tested:
  Robolectric covers the screens on the JVM, and `ringSpec` moved the ring
  styling rules out of a composable into testable code.
- **A real bug fixed**, found by a new test: `HeatField.density` allocated its
  array before validating the dimensions, so a negative width threw from the
  line written to prevent it.
- Devcontainer JDK path corrected, a `connect-android-device` skill added, and
  Wi-Fi debugging's random connect port handled by a `discover` subcommand.
- README badges, and a Codecov badge that read 68% at the time.

### Cutting it

`release.yml` fires on the tag alone, refuses to build without
`RELEASE_KEYSTORE_BASE64` - an immutable release of unsigned artifacts would
burn the version number for good - and attaches the APK and AAB to a *draft*,
publishing only once the upload finished.

---

## 2026-09-07 — Coverage reporting: Robolectric, and three silent failures

Codecov read 16.9%. **It went to about 64%** - line coverage 16.3% → 63.8%,
branch 24.9% → 56.3%, 68 tests → 124. Nothing about the app got much safer in
the process; most of the gap was measurement.

### The CI emulator was the wrong answer, and podsilo already said so

The first attempt was a CI job booting an emulator to run the 31 existing
instrumented tests. It failed three times - a device profile the runner's SDK
does not have, then a framework that was not up when the action reached it -
and it was the wrong idea regardless.

`podsilo`'s `ci.yml` forbids exactly this, in capitals, with a reason worth
repeating: a runner has no device, so such a job can only be skipped, fail, or
boot an emulator, and *an emulator agreeing with Robolectric is what let three
of that project's worst bugs through*. Its `--device=/dev/kvm` is the
devcontainer, which this repository already had, identically. The emulator that
"works" there is the local one.

Job removed. `enableAndroidTestCoverage` reverted to off, so nothing
instruments an APK any more.

### Robolectric, configured the way podsilo configures it

`sdk=34` pinned in `robolectric.properties` (it ships no framework jar for this
module's `targetSdk` of 37), `isIncludeAndroidResources = true`, and the Compose
test artifacts on the unit-test classpath. 16 new screen and navigation tests.

The device set in `androidTest/` is untouched and still runs on the phone. It
covers what neither Robolectric nor an emulator can: multi-touch, real haptics,
how the countdown feels.

### Three failures that a green build would have hidden

Worth writing down, because each looked like success:

1. **Two of the first screen tests were wrong about the app, not the app about
   itself.** `TEAMS` matches two nodes on the home screen - the mode card *and*
   the stepper's label - and the settings screen scrolls, so `COUNTDOWN` and the
   version sit below the fold. Fixed by matching unique subtitles and by
   `performScrollTo`, which also proves those rows are reachable.

2. **The Robolectric tests passed while measuring nothing.** 119 tests green,
   every screen still at 0%. Robolectric loads application classes through its
   own sandbox classloader; they arrive with no source location, and JaCoCo
   skips those by default. `isIncludeNoLocationClasses = true` is the fix. Had
   the build status been the only check, this would have been reported as a win
   that had not happened.

3. **The fix for (2) could not be applied.** `Extension of type
   'JacocoTaskExtension' does not exist` - AGP's `enableUnitTestCoverage` runs
   JaCoCo but never applies the JaCoCo *plugin*. Naming `jacoco` in
   `plugins { }` is what makes the extension exist. That failure at least was
   loud.

### Where the coverage sits

| | Line coverage |
| --- | --- |
| `HomeScreen` | 100% |
| `SettingsScreen` | 96% |
| `ResultScreen` | 93% |
| `ShotgunNavHost` | 89% |
| `Stepper`, `ModeMotif`, `Theme`, `Type`, `Color` | 95-100% |
| `DrawScreen` | 36% |

`DrawScreen` is the multi-touch surface and stays low on purpose - its decisions
were moved out into `ringSpec`, which is pure and unit-tested, so what is left
there is drawing. What remains uncovered otherwise is `ShotgunViewModel`,
`MainActivity`, `Haptics` and `DimMode`: an Android lifecycle and two hardware
services.

### Verified on the phone

A full multi-finger draw was run after the `ringSpec` extraction and the rings
render correctly - ranks, the fade by rank, and the label sliding in on lift.
That was the last thing outstanding: the extraction moved the styling rules out
of the composable, and unit tests pin the rules, but only the phone can show
that the pixels still agree.

---

## 2026-09-07 — Relicensed GPL-2.0-or-later, and Codecov switched on

### The licence

Changed from GPL-2.0-only to **GPL-2.0-or-later**, which settles the conflict
recorded a few entries down: the app's dependencies are all Apache-2.0, which
the FSF holds incompatible with GPLv2, but *is* compatible with GPLv3. The "or
later" clause lets a recipient take the work under GPLv3 terms, where the
conflict does not arise.

The change is one clause in the notice, not a new licence file. `LICENSE` still
holds the GPLv2 text, because that is what "version 2 or later" points at - the
grant lives in the notice, and licence texts are not edited. `SPDX-License-Identifier:
GPL-2.0-or-later` is stated in the README for the machine-readable form.

**The licence badge had to stop being the live one.** GitHub detects the licence
by reading `LICENSE`, and that file is the GPLv2 text, so
`img.shields.io/github/license` can only ever report *GPL-2.0*. That is not
wrong, but it is not the licence either - or-later is a distinct SPDX
identifier, and the difference is the entire point of this change. Swapped for a
static badge reading `GPL-2.0-or-later`. The tradeoff is the usual one: it is
accurate now and cannot notice if the licence changes later, so it has to be
edited by hand if it ever does.

The dependency note in the README is rewritten from *"here is an unresolved
problem"* to *"here is why the licence says or later"* - same facts, now an
explanation rather than a warning. The decision is off `TODO.md`.

### Codecov

The `CODECOV_TOKEN` secret was added, so the upload step stops skipping. Before
this it was working correctly but doing nothing: CI logged *"No CODECOV_TOKEN -
skipping the upload"* and marked the step `skipped`, which was the intended
behaviour and also the reason the badge read *unknown*.

---

## 2026-09-07 — Coverage: the gaps, a bug they found, and Codecov

Branch coverage was measured, then the gaps filled. **The logic layer went from
80.9% to 97.3%** (114/141 to 143/147 branches); 68 tests became 91.

The overall figure moved 16.2% → 20.4%, and that number is close to meaningless:
it counts Compose UI, navigation and Room's generated DAO, none of which a JVM
test can reach. Every covered branch in the app is in the logic layer, which is
the split `architecture.md` intends, so the per-layer figure is the one now
written down.

### A test found a real bug

`HeatField.density` allocated before it validated:

```kotlin
val cells = FloatArray(width * height)                        // -5 * 40 = -200
if (points.isEmpty() || width <= 0 || height <= 0) return cells
```

The guard checks `width <= 0` **after** the allocation that a negative width
already blew up - `NegativeArraySizeException`, from a line written specifically
to prevent it. Dead defensive code, and exactly the sort a coverage gap hides.
Guard moved above the allocation.

Not reachable from `ResultScreen` today, since the width is a constant and the
height is coerced to at least 1. It was still wrong.

### What was added

- **`DrawEngine`** - the early returns: an unknown pointer id lifting or moving,
  `isRevealed` before any draw, a latecomer pressing mid-reveal, `revealNext`
  with nothing to reveal, `progress` when idle and when settled.
- **`HeatField`** - degenerate input: zero and negative dimensions, points
  entirely off the grid, an empty ramp, a single-stop ramp, values past both
  ends, and two stops sharing a position (the divide-by-zero span).
- **Settings** - a stored-but-unparseable theme or timing name, `haptics`/`dim`
  stored as `false` rather than absent, and the countdown floor.

### One refactor, for one branch

`stepCountdown` resolved the legacy `countdown_seconds` key inside a DataStore
`edit` block, so the migration could not be reached without a `Context`. Pulled
out as `steppedCountdown(stored, legacySeconds, steps)`, a pure function, and
tested there. That path runs once per upgrade and never again - the kind nobody
exercises by hand.

### Four branches left uncovered, deliberately

Unreachable through the public API rather than untested:

- `DrawEngine:212` - the single-finger draw. `draw()` only runs from `tick()`
  during `COUNTING`, which needs two fingers, and dropping below two returns to
  `IDLE`. `shuffled.size <= 1` cannot happen.
- `DrawEngine:176` - `revealNext` finding a null outcome. Only `draw()` sets
  `REVEALING`, and it sets the outcome first.
- `HeatField:93` - an inner loop whose body always runs for valid input.

Reaching them would mean widening visibility or contorting a test, which buys a
percentage point and costs the meaning of the number.

### Codecov

CI now runs `createDebugUnitTestCoverageReport` alongside the tests (the
coverage task depends on the test task, so nothing runs twice), uploads the
report as an artifact, and sent it to Codecov, which hosted the README badge.

**The badge reads *unknown* until a `CODECOV_TOKEN` secret exists** - see
[`build-environment.md`](build-environment.md#sonarqube-cloud), which covers
the service that replaced it. The upload is
*skipped* rather than failed when the token is absent, because a fork's pull
request cannot read secrets and must not go red for it.

Worth keeping: the skip condition lives in a preceding `run` step rather than
in the upload step's own `if`, because **a step's own `env:` is not in scope
for its own condition**. The first draft had it inline and would have evaluated
an empty string every time.

---

## 2026-09-07 — GPLv2

`LICENSE` added, taken verbatim from GitHub's canonical text (`gh api
/licenses/gpl-2.0`) rather than retyped - 18,093 bytes, preamble through the
appendix, checked for all thirteen clauses, *NO WARRANTY* and *END OF TERMS*.
The licence text itself is never edited, so the copyright notice lives in the
README instead.

README gains a licence badge - the live GitHub one, which reads the repository's
detected licence, so it says *not specified* until this merges and *GPL-2.0*
after - and a *Licence* section carrying the copyright line and the standard
notice.

**The boilerplate is GPL-2.0-only, not -or-later.** The first draft used the
GPL appendix's usual wording, *"either version 2 of the License, or (at your
option) any later version"* - which is the **or-later** grant, not what was
asked for. Caught before committing and cut back to *"version 2 of the
License"*. Worth knowing the appendix offers both and the difference is one
clause, because it is exactly the clause that matters below.

### The part that is not resolved

**GPLv2 is incompatible with the Apache-2.0 dependencies this app is built
on.** Every runtime dependency - AndroidX, Compose, Room, DataStore, Kotlin and
kotlinx - is Apache License 2.0, whose patent-termination clause the FSF treats
as an added restriction GPLv2 does not permit. Apache 2.0 is compatible with
GPLv3, so the usual fixes are **GPL-2.0-or-later** (a one-line change, and the
one that ends the conflict most cheaply) or GPLv3.

GPLv2 was asked for and GPLv2 is what was applied; the conflict is recorded in
[`TODO.md`](TODO.md) under *Decisions needed* with the three options, and noted
in the README next to the licence. It is not an obstacle while nobody else
redistributes the app, but it should be settled before anyone forks or ships
it.

Per-file licence headers were **not** added. They are conventional for GPL
projects but would touch every source file, and the licence was the ask.

---

## 2026-09-07 — README badges

Six badges above the title: the four workflows (PR, Code scanning,
Documentation, Release), the latest release version, and Dependabot.

Each was fetched before being committed rather than trusted to the usual URL
shape - all four workflow badges report `passing`, and the release badge
resolves to `v0.1.1`.

Two details worth keeping:

- **`?branch=main` only on the two workflows that run on `main`.** `pr.yml` and
  `codeql.yml` take it. `docs.yml` and `release.yml` fire on a `v*` tag, so their
  runs sit on the tag ref, not `main` - pinning the branch would have shown them
  as *no status*. Both were checked with and without.
- **The Dependabot badge is static, and honestly so.** Dependabot publishes no
  status endpoint, so nothing can report whether it last ran or what it found.
  The badge says `enabled` and links to
  [`.github/dependabot.yml`](https://github.com/drehtuer/ShotgunApp/blob/main/.github/dependabot.yml),
  which is the claim it can actually support. It cannot go stale on its own, so
  if that config is ever removed the badge has to go with it.

Badge URLs are absolute, so they work both on GitHub and on the Jekyll site,
which renders this README unchanged.

### Found, not fixed

**There is no `LICENSE` file**, and GitHub reports the repository's licence as
none - which is why there is no licence badge. For a public repository that
means default copyright: nobody may reuse the code, which may or may not be
intended. Left alone, because picking a licence is a decision rather than a
documentation fix. Logged in [`TODO.md`](TODO.md).

---

## 2026-09-07 — Rank labels slide into the ring when the finger lifts

Closes the last of the "true for the hands it has been tried with" items, by
changing the rule rather than improving the guess.

### The problem the old placement could not solve

A label drawn at the ring's centre is under the fingertip that made it. So it
was drawn above the ring, flipping below near the top edge - and that flip was
the *only* condition ever checked. It asks whether the label fits on screen,
never whether the space is actually free.

It cannot ask. The app has a contact point and nothing else: no arm direction,
no grouping of fingers into hands, no idea who owns which. A player reaching
across from the far side has their palm over exactly the space above their
fingertip, and nothing flips, because the finger is nowhere near the top edge.

### The rule now

**Beside the ring while the finger is down, sliding to the ring's centre over
240 ms once it lifts.** Per finger, not per hand - each label moves as its own
finger comes off.

This works because the rings already outlive the fingers that drew them:
`DrawEngine.onUp` deliberately holds `REVEALED` when the last hand leaves, since
you have to lift to see what was under your own fingers. Lifting was already the
moment you read the answer; the label now moves somewhere guaranteed legible
when you do. A ring's centre is a point that was touched, so it is on screen by
construction - which also covers a label that would otherwise sit off the edge.

Verified on the phone.

### A documentation bug fell out of it

`design.md`'s state table said `revealed` is left when **"all fingers lifted"**.
That is exactly what the engine refuses to do, and had it been true this feature
could not exist - the rings would vanish on lift. `REVEALED` ends when the next
draw starts (a finger lands while none are down) or the surface is left.

Worth recording as a near miss: the table was read while designing the change,
and it described behaviour opposite to the code. The row is corrected and the
rule now also stated in prose under *Behaviour*, where it is harder to skim past.

---

## 2026-09-07 — Fairness field off the main thread, churn dropped

Two items off the open list, one closed by building it and one by deciding not
to.

### The fairness field no longer rasterises during composition

`FairnessField` computed the density grid, coloured it and allocated the bitmap
inside a `remember` block - so on the main thread, during composition, every
time the history or the palette changed. `HeatField` splats a kernel per
retained winner and up to 2,000 draws are kept, so the cost grows exactly as the
screen becomes worth opening.

Now a `produceState` on `Dispatchers.Default`, keyed on the winners, the palette
and the panel size. **The previous bitmap stays on screen until the new one
arrives**, so a recompute shows a stale field rather than flashing an empty
panel - which is what `null`-then-recompute would have done.

Keying on `colors` rather than `colors.isDark` came out of the rewrite: `PPColors`
is a data class, so structural equality already covers the ramp, and the narrower
key would have missed a palette change that kept the same `isDark`.

Verified on the phone in both palettes - the field renders, the ramp inverts
correctly between light and dark, nothing in the crash buffer. The theme switch
is itself the proof that the off-thread recompute path works, since it forces
one.

No new unit test: nothing new is computable in isolation. The maths in
`HeatField` was not touched and its tests still cover it; what changed is which
thread calls it, which the JVM tests cannot see.

### The suspense churn animation is dropped, not deferred

The export specifies every ring pulsing (`scale .94 ↔ 1.06`) during `suspense`.
It was never built, and it is now **decided against** rather than left open:
revealing the players one at a time is already the suspense, and pulsing the
rings between steps overhypes a result that takes a second to read.

Recorded in [`design.md`](design.md) as a deliberate divergence from the export,
which still shows the churn - behaviour is this document's call, and the export
is never hand-edited. It will stay a divergence until the Design project is
updated upstream.

### Building needed a container

The host still has no JDK, so `assembleDebug lint testDebugUnitTest` ran inside
the devcontainer image instead - repo mounted, SDK into a scratch directory,
Gradle as the host uid so nothing came back root-owned. Clean: 68 tests, no
failures, lint "No issues found", no compiler warnings. Worth knowing the option
exists when the workspace container is not available.

---

## 2026-09-07 — The hardware finger limit, the JDK, and a device skill

Started as a question - is the ten-finger ceiling the hardware or the app? - and
turned into three fixes.

### The limit is the hardware, and it is ten, not nine

Confirmed on the phone. The Pixel 10a's digitizer reports `ABS_MT_SLOT ... max 9`
via `getevent -pl`, and `dumpsys input` agrees with `Slot: min=0, max=9`.

**The nine is an off-by-one.** Slots are zero-based, so `max 9` is slots 0-9 -
**ten** simultaneous contacts. That misreading is exactly why the number is now
written down in [`design.md`](design.md#how-many-fingers) with the raw output
beside it.

The app adds no cap of its own, as designed: a ten-finger PLAYER ORDER draw
recorded ten `draw_points` rows, ranks 1-10, one winner, empty crash buffer.

Reading the count back out of the database was the fiddly part. Room runs in
**WAL mode**, so pulling `shotgun.db` alone gives `no such table: draws` - the
schema is still in the `-wal` file. All three files have to come across. There is
no `sqlite3` on the device either, so the query runs locally in Python. Both
traps are now in the skill.

### A settings row was asked for, then dropped - deliberately

The plan was to show the hardware maximum in Settings, above the version. It
cannot be done honestly. The app is sandboxed out of `/proc/bus/input/devices`
and `/dev/input/*` (`Permission denied` under its uid), and the only public API,
the `PackageManager` feature tier, tops out at `multitouch.jazzhand` = *"5 or
more"*. On a panel that does ten it would report five.

So nothing was added. Showing the tier understates the hardware; showing a
running high-water mark dresses an observation up as a hardware fact. The
reasoning is recorded in [`design.md`](design.md#why-this-is-not-shown-in-the-app)
so it does not get re-proposed.

### The JDK: the devcontainer was fine, its config was not

`./gradlew installDebug` failed with *"Toolchain installation ... does not
provide the required capabilities: `[JAVA_COMPILER]`"* - a JRE with no `javac`.

**The devcontainer was not the culprit.** That session was running on the WSL2
host, which has only `openjdk-21-jre`. The base image ships a full JDK 21,
verified by running it: `javac 21.0.12.1` at `/usr/lib/jvm/msopenjdk-current`.

Chasing it did turn up a real defect. `devcontainer.json` pointed the Java
extension at `/usr/local/sdkman/candidates/java/current`, which **does not exist
in this image** - a leftover from an older base image that used SDKMAN. Gradle
never noticed because it reads `JAVA_HOME`, so the breakage was invisible and
editor-only. Path corrected, and the Dockerfile now asserts `javac` at image
build so a base image that dropped the JDK fails there instead of at someone's
first build. Image rebuilt to confirm.

### Wireless debugging needed a port scan

`connect-device.sh` documented `:5555`, which is wrong for Android 11+ - the
connect port is random (43227 this time). `adb mdns services` found nothing,
because mDNS is link-local and does not cross the container's NAT, so the
documented recipe had no way to succeed.

Added a `discover` subcommand that scans 30000-50000 and tries each open port.
It also handles the trap that cost time here: the **pairing port stays open**
afterwards and looks like a candidate, but connecting to it leaves the device
`offline`. Tested end to end against the phone - two open ports found, pairing
port skipped, connected.

Wrapped the whole flow in a
[`connect-android-device`](https://github.com/drehtuer/ShotgunApp/blob/main/.claude/skills/connect-android-device/SKILL.md)
skill, the first in this repo.

### Not verified

The devcontainer changes were checked by building the image and running `javac`
in it, not by rebuilding the workspace container - so the corrected editor path
is reasoned from the image contents, not observed in VS Code. Gradle and the
test suite were never run this session: the host has no JDK, so the ten-finger
check used the existing `Shotgun-debug-0.1.1.apk`, first confirming no source
file was newer than it.

---

## 2026-09-06 — Binding the palette to the design export

**Outcome: done, and the first version of it was useless in a way that only a
mutation test could show.**

`PPColors` opens by saying it is "transcribed verbatim from the `--pp-*` custom
properties in the design export". **Nothing checked that.** `PPColorsTest`
asserts invariants - ink differs from ground, the palettes invert, the heat ramp
runs cold to hot - and every one of them holds just as well for the *wrong*
colours. Since the export is regenerated from Claude Design and has twice come
back with decisions reverted, the gap was not hypothetical.

`DesignTokenTest` now reads the export at test time and asserts all nine tokens
against both palettes, plus two things worth having:

- **The token set must be exactly the nine the app maps.** A token added by a
  re-export is a design decision that has not reached the app, and silence is
  how it would stay that way.
- **The export must agree with itself.** It writes each palette twice - once for
  the system preference, once for an explicit `data-theme` - and those can
  drift apart, after which the app matches one and not the other.

### The test passed, and caught nothing

Three deliberate mutations of the export - a changed accent, an added token, the
two dark blocks disagreeing - **all three passed**, in about a second each.

`testDebugUnitTest` was `UP-TO-DATE`. Gradle fingerprints a task from its
declared inputs, and a file read at runtime is invisible to that, so changing
*only* the export - which is precisely what a re-export does - did not re-run
the one test written to catch it. It would have sat green in CI forever.

The export is declared as a task input now, and the same three mutations fail,
naming the token and both values:

```
--pp-accent disagrees between the export and the dark palette
  expected:<#FFFF0000> but was:<#FFFF563C>
```

**Lesson: a test that has never failed has not been shown to work.** This one
was written, run, and passing while detecting nothing at all - and the reason
was not in the test.

### Where this leaves Claude Design

`/design-sync` does not apply here, and it is worth writing down why rather than
re-deciding it: it syncs design-*system* projects, pushing a local component
library up to one. This repo *consumes* a design system - Modernist, bound as
`design/_ds/modernist-f7022762-…/`, the only project id anywhere in the export -
and the app is Kotlin, not a component library.

So the integration is a contract, not a tool: **the export is never hand-edited**
- now a rule in `.claude/CLAUDE.md` - visuals go upstream, behaviour lives in
`design.md`, and the palette half of that is enforced by a test rather than by
good intentions. Two hand-edits made before the rule still need mirroring
upstream; they are in `TODO.md`.

`/design-login`, which would authorize the sync tool, is **not available in this
environment** either - and it is a Claude Code command rather than anything in
the Claude Design UI, which is worth saying because it is the wrong place to
look for it. Neither matters: nothing here depends on it. `DesignTokenTest`
reads a file already in the repository, so it needs no account, no login and no
network. `build-environment.md` records the re-export procedure that replaces
all of it.

---

## 2026-09-06 — v0.1.1, and one number fewer to get wrong

**Outcome: a patch release, and `versionCode` taken out of human hands.**

Everything since `v0.1.0` was infrastructure, documentation and three Lint
fixes - the artifact naming, the Jekyll site, the security policy, Dependabot
and CodeQL. **No behaviour changed**, so a patch bump rather than a minor one.

The release itself only needed `appVersion`. But `versionCode` was still a
hand-written `1`, and it is the number Android compares to decide whether an
APK is an update: **a release that repeats or lowers it cannot be installed over
the one before**, and an immutable release cannot be corrected afterwards. It is
also exactly the kind of number that gets forgotten, because nothing local
fails when it is wrong - the build succeeds, the tests pass, and the damage only
appears on someone else's phone.

So it is derived now - `major * 10000 + minor * 100 + patch`, giving `101` for
`0.1.1` - and cutting a release means changing one value. Verified against the
built APK rather than the source: `aapt2 dump badging` reports
`versionCode='101' versionName='0.1.1'`.

That is the third guard on this path, after the signature check and the
tag-versus-`appVersion` check. All three exist because a published release
cannot be taken back, so the only place to catch a mistake is before the tag.

### The release worked; the docs deploy was rejected

`Shotgun-0.1.1.apk` and `Shotgun-0.1.1.aab`, signed and published. The
documentation workflow **built the site and then failed to deploy it**:

> Tag "v0.1.1" is not allowed to deploy to github-pages due to environment
> protection rules.

The `github-pages` environment ships allowing the **default branch only**, and
moving the trigger to the `v*` tag put the deploy on a ref it does not accept.
The first successful publish had been a `workflow_dispatch` from `main`, which
is exactly why this went unnoticed - **the working path and the release path
were never the same path.**

The environment now carries a `v*` tag policy alongside `main`, and the failed
job was re-run against the artifact it had already built. Site live, and it
carries this entry.

**Lesson, and it is the same one as the `--user` flag on the Jekyll container:**
verifying through a slightly different route than the real one verifies a
slightly different thing. Both failures were invisible until the real path ran
for the first time.

---

## 2026-09-06 — The keystore backup is only half a backup

**Outcome: a wrong assumption caught before it mattered.**

The release keystore was backed up to a password manager as a file, on the
understanding that the keystores have no password. **They do** - 24 characters
each - so the file alone cannot be opened, and the backup as it stands would not
restore anything.

Checked rather than assumed, and worth the two commands:

```
keytool -list -keystore keystore/release.keystore -storepass ""
  -> keystore password was incorrect
keytool -list -keystore keystore/release.keystore -storepass "<real>"
  -> shotgun-release, PrivateKeyEntry
```

Within each keystore the store and key passwords are the same value; debug and
release differ from each other. The release key is 4096-bit RSA, `SHA384withRSA`,
valid until 2056 - so expiry is not the risk.

**Both are now backed up together**, which closes the last item on the list
that could not be recovered from. Until this, the only readable copy of the
password was `keystore.properties` on the build machine - a GitHub secret is
write-only by design and never counted as a copy - so the password, not the
file, was the single point of failure. `build-environment.md` records what a
backup has to contain, and that the two have to be updated in the same pass if
the key is ever rotated.

**Worth keeping as a habit:** the backup was believed complete before it was.
Nothing failed, and nothing would have failed until the day the machine died -
which is the property that makes backups worth testing rather than assuming. Two
`keytool` commands settled it.

Merge queue is dropped from the open list. The rulesets API rejected the rule at
the type level however it was sent, the `main` ruleset never carried one, and it
is not worth more archaeology - stacked PRs chained by hand have worked
throughout.

---

## 2026-09-06 — Polish: Lint clean, and a decision about how many fingers

**Outcome: done. Lint is at zero findings, and "how many players" has an answer
that is not a number this app invented.**

The five informational Lint findings were each a real thing rather than noise,
so all five were fixed rather than suppressed:

- `AutoboxingStateCreation` - the countdown's `progress` was a `Float` in a
  generic state box, boxed on **every frame** of the countdown. Now
  `mutableFloatStateOf`.
- `UseOfNonLambdaOffsetOverload` - the settings toggle knob is animated, and the
  non-lambda `offset` recomposed the whole `Box` per frame instead of only
  re-laying it out.
- `ModifierParameter` - `ShotgunMark` took `size` before `modifier`, so a
  caller could not pass a modifier positionally the way every other composable
  here allows.
- `UnusedResources` - `ic_launcher_round.xml` was **byte-identical** to
  `ic_launcher.xml`. Both are adaptive icons, which the launcher already masks
  to whatever shape it wants, so the round variant was a duplicate that could
  drift. Deleted rather than wired up with `android:roundIcon`.
- `NewerVersionAvailable` - coroutines 1.10.2 → 1.11.0.

### How many fingers

The design said "the screen is the limit", which is not a limit. The answer is
that **the app should not invent one**: Android reports a device-dependent
maximum number of simultaneous pointers, commonly ten, and past that a finger
produces no pointer at all. A cap in the app would only be a second, lower
limit that then had to be explained.

Ten is also the practical ceiling for the thing itself - two hands, one phone -
so the behaviour is pinned there by three tests: ten fingers each get a distinct
rank, ten across three teams are dealt round robin with sizes differing by at
most one, and lifting one of ten leaves the other nine undisturbed. 64 unit
tests now.

---

## 2026-09-06 — Security policy, Dependabot and code scanning

**Outcome: done. Most of the value was in deciding what *not* to automate.**

`SECURITY.md`, a Dependabot configuration and a CodeQL workflow, plus the
repository settings that make them mean anything: private vulnerability
reporting, Dependabot alerts and security updates, all now on. Secret scanning
and push protection were already enabled.

**The policy is bounded by what the app can actually do**, which is little: one
permission, `VIBRATE`; **no `INTERNET` permission at all**, so nothing can leave
the device even by mistake; and a local database holding finger positions and
nothing else. Stating that plainly is more useful to a reporter than a page of
process, and it is checkable - the manifest is three lines.

The part worth spelling out is signing material. **A leaked release key is the
one unrecoverable failure here**: Android identifies an app by its signature, so
whoever holds the key can ship an "update" to everyone who installed it. So the
policy says explicitly that finding a keystore or key password anywhere in the
repository *is* the vulnerability.

**Dependabot deliberately does not raise AGP or the Gradle wrapper.** They move
together with `compileSdk` and the Kotlin version - and AGP 9 removed the
`kotlin-android` plugin - so an automated bump breaks the build rather than
updating it. AndroidX and Compose are grouped into one PR each, because they are
released in step and a per-artifact PR could not pass CI on its own. Both are
cases where the default configuration produces PRs that are guaranteed to be
red, which teaches people to ignore Dependabot.

**CodeQL scans the workflows as well as the Kotlin**, because that is where the
signing keys and the release path live - least visible, and with immutable
releases least recoverable. It runs weekly as well as per PR so new queries
reach existing code. It is deliberately **not** a required check: findings are
advisory, and a required one blocks unrelated merges on an untriaged alert.

**`build-mode: none` does not work for Kotlin**, which was worth finding out
the noisy way. The first run completed a dependency scan, reported
`BUILD SUCCESSFUL`, and then failed at the finalize step with "CodeQL could not
process any code written in Java/Kotlin" - an empty database rather than an
error at the point of the mistake. Kotlin is extracted by the compiler as it
runs, so it needs `build-mode: manual` and a real `assembleDebug`. The `actions`
language has nothing to build and keeps `none`, so the two now differ by
design.

That was not the end of it. With a real build the run failed **the same way**,
and for a completely different reason: `39 actionable tasks: 22 executed, 17
from cache`. **CodeQL extracts Kotlin by tracing the compiler as it runs**, so
tasks served from the Gradle build cache contribute nothing - `BUILD
SUCCESSFUL`, empty database, identical error message. Caching is now off for
that job.

**Lesson: "could not process any code" is a symptom, not a cause.** It says the
extractor saw nothing, which a wrong build mode and a warm cache produce
identically. The thing that distinguished them was the task summary, not the
error.

**Two settings would not enable.** `secret_scanning_non_provider_patterns` and
`secret_scanning_validity_checks` stay `disabled` after a `PATCH` the API
accepts without error - no message, no failure, just no effect. Recorded in
`TODO.md` rather than assumed to have worked.

---

## 2026-09-06 — First release, and the documentation site

**Outcome: `v0.1.0` published and the site live - after both workflows turned
out to be broken in ways only building their output could show.**

Neither had ever run. Building the site locally, with the same container image
CI uses, found what reading them had not.

**Every link on the site was broken.** All 25 cross-document links pointed at
`.md` files that do not exist on the site; a `sed` pass rewrote a few prefixes
and left the rest. Now GitHub's own Jekyll against a root `_config.yml`, which
fixes it at the source - `jekyll-relative-links` resolves them as a matter of
course. Links to files Jekyll does not publish are absolute GitHub URLs, so they
work in the repository *and* on the site.

**The Mermaid diagrams would have been served as source code.** GitHub renders
Mermaid in its *repository* Markdown view - which is where they had been checked
- but **Pages does not**. All five would have appeared as walls of `graph TD`
text. The layout now loads Mermaid and unwraps Kramdown's markup, with blocks
hidden until it has run.

**A release would have been published unsigned, permanently.** `release.yml`
treated a missing key as normal and published unsigned artifacts with a warning
- written back when the key was deliberately not on GitHub. Since a published
release is immutable, that would have burned the version number for good. A
missing key now fails the run, `apksigner verify` confirms the signature on the
artifact rather than inferring it from the secret existing, and artifacts are
attached to a **draft** which is published only once the upload succeeds.

**The docs workflow would never have fired.** It triggered on
`release: published`, and `release.yml` publishes the release itself using the
default `GITHUB_TOKEN` - and **an event raised by that token does not start
another workflow run**. The site would silently never have published. Both
workflows now watch the `v*` tag, which a person pushes.

### The one thing local testing hid

The release worked first time: signed APK and AAB, `apksigner` confirming
`CN=Shotgun!, OU=Release, O=drehtuer`. The docs run failed on
`mkdir: cannot create directory '_site/api': Permission denied`.
`jekyll-build-pages` is a **container action running as root**, so `_site`
belongs to root while the job does not.

Local testing had hidden this precisely: the container had been run with
`--user "$(id -u)"` to keep the output writable, which made the local run
*differ from CI in the one way that mattered*. **A convenience added to avoid
cleaning up root-owned files removed the failure being tested for.** The site is
now assembled in a fresh directory - copying *out* of `_site` needs only read -
and the fix was verified by reproducing the failure first.

### Named release artifacts

`app-release.apk` says neither what it is nor which version. The builds now
produce `Shotgun-<version>.apk` and `Shotgun-debug-<version>.apk`, and the
release workflow renames the bundle to match. `outputFileName` is not on the
public `VariantOutput` interface, so this goes through `VariantOutputImpl`; the
cast is checked, not forced.

That introduced a new way to publish something unfixable - the name comes from
`appVersion` in the build file, not the tag, so a `v0.2.0` tag on an unchanged
`appVersion` would put `Shotgun-0.1.0.apk` inside a `v0.2.0` release. The
workflow now refuses to run when the two disagree. **`v0.1.0` keeps the old
names**; it is immutable.

### Documentation that had drifted

Found by checking claims against the code rather than reading for sense:
`STATUS.md` still opened with "skeleton + theme … the four screens are stubs";
`TODO.md` carried the removed "+1 s per further finger" countdown and a dim
level that had been wrong twice; `design.md` gave the old 2 s countdown default
and a 600 ms suspense; `build.gradle.kts` and `release.yml` both still said the
release key was not on GitHub.

## 2026-09-06 — Device testing, and the blank screen

**Outcome: a run of real bugs, all found by hands on glass rather than by
tests.** Installed on the target Pixel 10a over Wi-Fi.

The blank screen is worth recording in full, because three plausible
explanations were wrong before the right one.

Reported as: pick a mode, press back immediately, and the app draws nothing
until it is restarted. It could not be reproduced synthetically - six back-press
timings, rapid double taps, tap-plus-two-backs and the edge-swipe gesture all
behaved. Two suspects were ruled out with evidence rather than argument: the
glow uses `BlurMaskFilter`, historically unsupported under hardware
acceleration, but forcing a countdown produced no render errors; and the
brightness override was measured, not guessed.

What settled it was catching the app *in* the state. `dumpsys` showed the
activity resumed with no crash, and an accessibility dump showed **three nodes**
- bare window chrome, no Compose content. The navigation graph had lost its
destination.

Reproduced in a test: **popping more often than the stack is deep empties the
graph**, and an empty graph renders nothing. Two pops racing each other is
enough - a button tapped twice, or a tap arriving with the back gesture. Every
exit now goes through `popSafely()`, and back handling lives in one place.

**Lesson: an unreproducible bug is usually a wrong model of the failure, not a
rare one.** Catching the app in the broken state and dumping what it actually
contained took minutes; guessing at causes took much longer.

Also fixed from device testing: labels drawn under the very fingers they
belonged to; the countdown appearing to ignore its setting, which was the
stepper losing taps to an asynchronous read; both timers restarted forever by
the jitter of a resting hand; and dim mode, set absolutely, making a dim screen
*brighter*.

## 2026-09-06 — The four screens

**Outcome: done, across five PRs.** Each screen's logic was pushed out of the
composables into plain functions, so the parts that can be tested without
hardware are.

**Settings and dim mode.** Dim lowers `screenBrightness` on the app's own window
and restores it on the way out - deliberately not the system-wide setting, which
would leave a phone dimmed after the app closed. Applied in `MainActivity`,
because brightness is an app-wide property, not a property of the screen that
happens to toggle it. Verified by changing three settings, force-stopping and
reopening.

**Result and the fairness field.** The density maths lives in a plain
`HeatField` with no Android types, so it is unit-tested. That mattered more than
usual: the screen makes a claim about fairness, and "it looks about right" is
not a check of a claim. The test worth keeping is **a corner win registering as
hot as a centre win** - without edge mirroring, corners read as permanently
cold and the screen would libel the draw as unfair. Verified by seeding 241
draws into the real on-device database and looking at it.

Two things that cost time: `gradle connectedAndroidTest` **uninstalls the app
afterwards**, deleting its database, so seeding through it shows an empty screen
- `adb shell am instrument` leaves the data in place; and `--tests` is not a
valid filter there, it is
`-Pandroid.testInstrumentationRunnerArguments.class=`.

**Draw surface.** The rules live in a plain `DrawEngine` with no Android in it -
time passed in, randomness injected - so the countdown, the teams guard, the
reveal rules and who wins are unit-tested. The one thing that cannot be tested
without hardware is multi-touch, so everything else was made testable without
it. **Multi-touch turned out partly testable anyway**: the emulator cannot
inject a genuine multi-pointer gesture, but Compose's own pointer injection can.

**Home.** The team count lives in the ViewModel rather than in settings,
because the design keeps it beside the draw. Two bugs only visible on a device:
the vertical SETTINGS tab wrapped mid-word - "SETTIN / GS", since Compose has no
`writing-mode` and the first fix measured the rotated label against the tab's
width; and the stepper's minus stayed at full contrast at the floor, looking
live while doing nothing.

**Signing, build variants and persistence.** `debug` carries full debug data,
`release` is R8-minified and symbol-stripped - 1.2 MB against 12 MB. Two
deliberate omissions in `debug`, both easy to add by reflex and both wrong here:
no `applicationIdSuffix` (it would break every documented adb command) and no
`enableAndroidTestCoverage` (it instruments the APK, and this app is judged on
touch and countdown timing).

The integration risk was KSP: it versions independently and AGP 9 supplies its
own Kotlin, so the two could easily have disagreed. KSP 2.3.11 against Kotlin
2.4.10 under AGP 9 was checked before anything was built on top of it.

Positions are normalised to 0..1 **at write time**. That is the one thing in
this layer that can corrupt data silently - raw pixels would skew the fairness
field, and the damage stays invisible until a screen size changes.
`fallbackToDestructiveMigration` is deliberately **not** set: history is the
whole point of the fairness field, and wiping it on a schema change would make
that screen lie.

## 2026-09-06 — Rename to Shotgun!, and a documentation audit

**Outcome: done.**

The app became **Shotgun!**: re-exported design, a logo in `assets/logo/`,
package and `applicationId` moved to `de.drehtuer.shotgun`. The `PP*` prefix on
the theme types was **kept deliberately** - it mirrors the `--pp-*` token names
the design still uses, so a colour can be traced between design and code by
name.

**The re-export had been branched from the original design rather than the
edited copy here, so it silently reverted decisions made the day before**:
haptics went back to a single buzz, and the toggle copy to its old text. Those
decisions still stand, so they were re-applied. Second time hand-edits have been
at risk - see `TODO.md`.

The audit that followed found more wrong than missing. `.claude/CLAUDE.md`
opened by pointing at `design/Player Picker.dc.html`, **deleted in the rename**;
it survived because the path was line-wrapped across two lines, so the search
and replace never matched. `README.md`'s layout tree still named
`PlayerPickerTheme` and `PlayerPickerNavHost`, for the same class of reason -
inside a fenced code block.

**Lesson: a line-wrapped path and a fenced code block both defeat a naive search
and replace.** After a rename, grep for the old *words*, not the old path.

## 2026-09-05 — Keep the screen awake during a draw

**Outcome: done and verified on device.**

The screen could dim or lock mid-draw. Android resets its idle timer on touch
*events*, and a finger held still through the countdown produces none - so the
one moment the screen must stay on is exactly the moment the system counts as
idle. Fixed with `FLAG_KEEP_SCREEN_ON`, scoped to the draw surface so it cannot
leak into the other screens.

Verified with `dumpsys window` across the whole navigation graph. The first
attempt reported a **false negative**: `grep -A3` after the window line stopped
short of the `fl=` line it needed, about five lines in.

## 2026-09-05 — Specification decisions

**Outcome: done. All three open questions answered; no code changed yet.**

SOUND removed as not needed. DIM MODE defined as lowering screen brightness like
an alarm clock, explicitly *not* a palette change. Haptics redefined from a
single buzz to a `12 ms` tick per finger down plus a stronger result buzz - the
tick deliberately does **not** fire on drag or lift, because dragging is
repositioning, not joining, and buzzing on it would contradict the rule the hint
text teaches. Draw history moved to a database so the heatmap plots real draws.

Dim mode and the database cannot be expressed in a browser prototype, which
forced a question about which document wins. Resolved by splitting authority:
**the export owns visuals, `docs/design.md` owns behaviour.**

## 2026-09-05 — Toolchain, documentation set and CI

**Outcome: done, and retargeting forced a toolchain jump.**

The target device was confirmed as a **Pixel 10a on Android 17 (API 37)**.
Raising `compileSdk` to 36 failed immediately - current AndroidX requires
compileSdk 37 and AGP 9.1+ - so the whole toolchain moved: AGP 8.7.3 → 9.4.0,
Gradle 8.11.1 → 9.7.1, Kotlin 2.0.21 → 2.4.10, compileSdk/targetSdk 35 → 37.

**AGP 9 has built-in Kotlin support: the `kotlin-android` plugin now fails the
build outright and had to be removed.** Recorded in several places because it is
easy to re-add by reflex.

The emulator has no `pixel_10a` profile; `pixel_9a` on an API 37 image is the
closest match, and reported `ro.build.version.release=17`.

Also added: `docs/design.md` as a translation of the export - not a replacement,
since the `.dc.html` holds the executable state machine - `build-environment.md`,
this file, `TODO.md`, the first 11 unit tests, and the two workflows. Neither
workflow could run on its own PR, since workflows must exist on `main` first.

Translating the design surfaced two gaps: **`sound` and `dim` appeared in the
settings with no behaviour defined anywhere.** Both were answered the same day.

## 2026-09-05 — Android skeleton and Modernist theme

**Outcome: done.**

The Gradle project, the theme transcribed from the design's `--pp-*` tokens, the
navigation shell and stub screens, and the devcontainer.

**Two bugs found only by running it:** system bar icons followed `uiMode` rather
than the app's palette, so the clock was unreadable when the theme was
overridden; and the settings dividers used a hardcoded height instead of the
row's intrinsic height.

Several devcontainer problems also only appeared under use: `/dev/kvm` arrives
owned by a nonexistent group, and AVDs and the debug keystore vanished on
rebuild until they were moved into named volumes.

## 2026-09-05 — Design export imported

**Outcome: done.**

The repository began as an export from a Claude Design project, with no commits
on `main`. `/design-sync` does not apply - this repo is a *consumer* of a design
system, not the source of one.
