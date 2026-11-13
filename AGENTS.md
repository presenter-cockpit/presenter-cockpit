# AGENTS.md

Notes for coding agents and contributors working on Presenter Cockpit. What the app does is in
[README.md](README.md).

## Project

- One Gradle module, `app`: Kotlin and Jetpack Compose, `minSdk` 29, bytecode for Java 17.
- Three flavors, each with its own application id, so they can live on the same tablet:
  - `full` (`it.presenter.app`): every feature, with access to all files. The APK on GitHub.
  - `store` (`it.presenter.app.store`): for Google Play. Folders chosen with the system picker, no local
    terminal.
  - `fdroid` (`it.presenter.app.fdroid`): free software only, so without Google ML Kit and without
    handwriting to text.
- Debug builds add `.debug` to the id and their commit to the version (`0.8.3-dev+1a2b3c4`).

## Layout

Sources in `app/src/main/java/it/presenter/app/`:

| Package | What it holds |
|---|---|
| `session` | state of the open documents and tabs, saving and restoring the session (`PresenterSession`) |
| `canvas` | the page on the tablet: rendering, layers, zoom and pan |
| `ink`, `tools`, `laser` | strokes, shapes, text boxes and erasing; tool presets; laser pointer |
| `doc`, `pdf` | PDF pages, annotations saved into the PDF, PDFPC notes, page edits, whiteboards |
| `projector` | the audience screens: external display, window for video calls, pause screen |
| `tabs`, `code`, `remote` | code editor, local terminal and SSH terminal, SFTP, web pages |
| `files`, `workspace`, `settings`, `input` | file browsers, workspaces, preferences, keyboard shortcuts |
| `ui` | Compose screens and shared components |

Other source sets:

- `app/src/mlkit/`: handwriting recognition with ML Kit, used by `full` and `store`.
- `app/src/fdroid/`: the same API without ML Kit, plus the F-Droid manifest and icon.
- `app/src/full/`, `app/src/store/`: manifest and resources of those flavors.
- `app/src/main/jni/`: a small pseudo-terminal helper in C, built with ndk-build.
- `fastlane/metadata/android/`: store listing (en-US, it-IT), read by F-Droid.

## Build and test

You need JDK 17 or 21, the Android SDK (compile SDK 37, build tools 37.0.0) and the NDK
30.0.16248370.

```
./gradlew assembleFullDebug           # also assembleStoreDebug, assembleFdroidDebug
./gradlew testFullDebugUnitTest       # JVM tests with Robolectric
./gradlew testFullDebugUnitTest -Pdeep --tests 'it.presenter.app.deep.*'   # slow end-to-end tests
./gradlew lintFullDebug               # known false positives: RestrictedApi on Jetpack Ink
```

- Tests live in `app/src/test/`, by package. `deep/` holds whole user flows and random sequences of
  actions, excluded from normal runs. Property tests use `testing/Property`; a failing case prints its
  seed, which can be replayed with `-Pproperty.seed=N`.
- When a change touches a flavor's own code (`src/mlkit`, `src/fdroid`), build all three flavors.
- Release builds without signing keys are signed with the debug key, except `fdroid`, which stays
  unsigned because F-Droid signs it.

## Conventions

- Code, comments, tests and commit messages are in English. User-facing text is in
  `res/values/strings.xml`, with the Italian translation in `res/values-it/strings.xml`: add both.
- Comments say why, in plain full sentences.
- Tests and examples use invented data only (see the sample talk in `app/src/test/resources/sample/`).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/), one line,
  for example `fix(ink): keep the eraser on the pen button`.
- The version in `app/build.gradle.kts` changes only for a release (`chore(release): x.y.z`), following
  Semantic Versioning. Do not bump it in feature or fix commits.
- No dependencies with non-free licenses outside the ML Kit source set: the `fdroid` flavor must
  build from free software only.
- The pen stroke path (Jetpack Ink with front-buffer rendering and motion prediction) is tuned for low
  latency. Change it only with a clear reason and test on a real pen tablet.

## Contributing

How to report bugs, suggest features, translate and send pull requests: [CONTRIBUTING.md](CONTRIBUTING.md).
