# TODO

Open work only. When something lands it moves to [`STATUS.md`](STATUS.md),
with what actually happened - so this file stays a list of what is left, not a
record of what was done.

Behaviour for everything below is specified in [`design.md`](design.md) and,
authoritatively, in the design export itself.

## Decisions needed

- **Where does the COLOURS list live** - edited once and persisted, or set up
  per draw? It decides the navigation and whether it is stored at all, so the
  mode cannot be built until it is answered.

## Screens

- [ ] **COLOURS mode** - a fourth draw mode. Like player order, but the colours
      are defined first and each finger is given one in the order they were
      defined. Blocked on the decision above.
  - [ ] A colour-definition screen: a vertical list with a `+` to add
  - [ ] Preset player colours - blue, red, black, green, yellow and so on
  - [ ] A colour picker for anything finer
  - [ ] Rings and result dots take the chosen colours rather than the palette's
        team fills

## Infrastructure


- [ ] **The corrected devcontainer JDK path is unverified in the editor.**
      `devcontainer.json` pointed the Java extension at a SDKMAN path absent
      from the base image; it now points at `/usr/lib/jvm/msopenjdk-current`,
      confirmed present by running the image. Nobody has rebuilt the workspace
      container and checked the extension actually resolves it - Gradle reads
      `JAVA_HOME` and never cared either way, which is why this went unnoticed.
- [ ] **The `setup-android` fix in `docs.yml` and `release.yml` is unverified.**
      All three workflows were moved off the broken v4.0.1 pin together, but
      only `codeql.yml` runs on a pull request. The other two are tag-only, so
      the identical one-line change in them is fixed by inspection. The next
      `v*` tag is the first thing that will actually run them - check the
      release job reaches the signing step rather than failing at SDK setup.
      Background in [`STATUS.md`](STATUS.md).
- [ ] **Nothing tells anyone that a tag-only workflow has gone stale.**
      `docs.yml` and `release.yml` had been broken since 2026-09-19 and neither
      had run since 2026-09-12, so there was no signal at all. A scheduled dry
      run of the release path - build unsigned, skip publishing - would have
      caught it, but it duplicates most of `release.yml` and needs deciding
      before it is worth building.
- [ ] **`connect-device.sh discover` scans a guessed range.** 30000-50000 covers
      what Android has picked so far, but the port is only documented as
      ephemeral. A phone that lands outside the range fails with "no open ports"
      and no hint that the range is the problem.

- [ ] **Two secret-scanning options would not enable.**
      `secret_scanning_non_provider_patterns` (generic secrets, which is what a
      keystore password looks like) and `secret_scanning_validity_checks` both
      stay `disabled` after a `PATCH` that the API accepts without error.
      Probably a UI toggle or an Advanced Security requirement. They matter here
      more than most repositories, because a leaked signing key is the one
      unrecoverable failure - see
      [`SECURITY.md`](https://github.com/drehtuer/ShotgunApp/blob/main/SECURITY.md).
- [ ] **Mirror the two hand-edits still living in the export.** The rule is now
      that the export is never edited here - visuals go upstream in the Claude
      Design project, behaviour goes in `design.md`. Two edits predate that
      rule and would be lost by a re-export: the haptics change and the
      HAPTICS/DIM MODE toggle copy. They need making in the Design project
      itself. `DesignTokenTest` covers the palette, not copy.
- [ ] **Decide on Gradle dependency verification.** SonarQube raises two
      findings for it (`kotlin:S6474`, `text:S8569`) and they are the only
      security findings left open. Generating
      `gradle/verification-metadata.xml` was measured: 3,745 lines, 536
      components, 42s. The blocker is not the file, it is that any dependency
      change invalidates it and Dependabot cannot regenerate it - every weekly
      Gradle PR would go red until someone regenerated it by hand, against the
      grouping in `dependabot.yml` that exists to keep those PRs green. Worth
      revisiting if the dependency set settles. **Until then the two findings
      want marking won't-fix in SonarCloud**, which needs the project account.
- [ ] Consider `actions/attest-build-provenance` on release artifacts. It pairs
      with immutable releases - the release cannot change, and the attestation
      says which workflow and commit produced it - but it is not needed until
      the app is actually distributed.

## Polish


- [ ] Check the in-app mark optically against
      [`assets/logo/`](https://github.com/drehtuer/ShotgunApp/tree/main/assets/logo)
      on a real screen at small sizes.
- [ ] **No accessibility tests.** The design leans on colour and scale, and the
      draw surface has no text alternative.

## Settled, so they are not open

Recorded here because each was a real question, and the answer is easy to
re-litigate otherwise.

| Question | Answer |
| --- | --- |
| Should CI run the instrumented tests? | **No.** They run locally, against the phone. An emulator cannot test multi-touch, so a green CI run would say nothing about the one thing they exist to check. |
| SOUND | Not needed. Removed from the design and the docs. |
| DIM MODE | Lowers screen brightness like an alarm clock - halves the current level, app window only. |
| Draw history | Recorded to a database, so the fairness field plots real draws. |
| Double-tap to lift a player | Deliberately absent. Lifting a finger removes its ring, so it has no purpose on a touchscreen. |
| Should the tick follow the system's touch-feedback setting? | **No.** HAPTICS is the only switch: on gives ticks and buzzes, off gives neither. Deferring to the system made the app's own toggle mean different things on different phones. |
