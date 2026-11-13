# Contributing to Presenter Cockpit

Thanks for your interest. Bug reports, ideas, translations and code are all welcome.

## Report a bug

Open an [issue](https://github.com/presenter-cockpit/presenter-cockpit/issues) and include:

- the version and the distribution, from Settings > About (for example `0.8.3`, GitHub);
- the device and the Android version;
- whether you used the pen or a finger, and whether an external screen was connected;
- what you did, what you expected and what happened instead;
- if possible, a PDF that shows the problem. Please share only files you are allowed to share, or make
  a small one with invented content.

If the app crashes, a log helps a lot: with USB debugging on, run `adb logcat -b crash` after the crash.

## Suggest a feature

Open an issue that describes the situation you are in (a lesson, a talk, a video call) and what is
missing. A description of the problem is more useful than a finished design.

## Translations

The texts are in `app/src/main/res/values/strings.xml` (English) and `values-it/strings.xml`
(Italian). To add a language, copy `values/strings.xml` to `values-<code>/strings.xml`, translate it
and add the language to `localeFilters` in `app/build.gradle.kts` and to `res/xml/locales_config.xml`.
The store listing for F-Droid is in `fastlane/metadata/android/<locale>/`.

## Code

1. Read [AGENTS.md](AGENTS.md): layout of the project, how to build and test, conventions.
2. For anything bigger than a small fix, open an issue first, so we can agree on the approach.
3. Keep a pull request focused on one change. Run the unit tests (`./gradlew testFullDebugUnitTest`)
   and make sure lint has no new warnings.
4. If you change the code of one flavor (`src/mlkit`, `src/fdroid`), build all three.
5. Write commit messages with [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/),
   for example `feat(projector): add a black screen shortcut`.
6. In the pull request, say how you tested it: device, Android version, pen or finger, external screen.

The `fdroid` flavor must build from free software only: new dependencies need a free license, or must
be confined to the `mlkit` source set like Google ML Kit.

## License

Presenter Cockpit is released under the GNU GPL version 3 only ([LICENSE](LICENSE)), with an additional
permission for Google ML Kit ([NOTICE](NOTICE)). By contributing, you agree that your contribution is
released under the same terms.
