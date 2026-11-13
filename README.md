<p align="center"><img src="https://presenter-cockpit.github.io/assets/img/logo.svg" alt="" width="96" height="96"></p>

# Presenter Cockpit

Free and open source Android app for tablets with a pen. It shows your PDF slides on a projector or an
external screen, and you write on them, point with a laser and keep your notes on the tablet.

![The tablet with tools, timer and the screens menu, and the external screen with only the slide and the ink](https://presenter-cockpit.github.io/assets/screens/dual.webp)

Website, with more screenshots: https://presenter-cockpit.github.io/

## What it does

- Two screens: the audience sees the slide, the ink and the laser; the tablet also shows tools, notes
  and a timer.
- Ink with pressure, shapes that snap into place, text boxes, handwriting turned into text.
- Annotations saved inside the PDF, still editable when you open it again.
- Made for LaTeX Beamer and PDFPC: overlays, notes, hidden slides and bookmarks from `.pdfpc` files.
- Freeze or pause the projector, spotlight, inverted colors, many outputs, video calls.
- Tabs for code (local or over SFTP), terminals (local or SSH) and web pages next to the slides.
- Workspaces, search, outline, remotes and keyboard shortcuts.

Android 10 or later, on a tablet with an active pen.

## Builds

There are three flavors. Each one has its own application id, so they can live on the same tablet.

| Flavor | Id | Notes |
|---|---|---|
| `full` | `it.presenter.app` | everything; the APK attached to the GitHub releases |
| `fdroid` | `it.presenter.app.fdroid` | free software only: no handwriting recognition (Google ML Kit) |
| `store` | `it.presenter.app.store` | for Google Play: files through the system picker, no local terminal |

To build you need JDK 17, the Android SDK (compile SDK 37, build tools 37.0.0) and the NDK
30.0.16248370:

```
./gradlew assembleFullDebug      # or assembleFdroidDebug, assembleStoreDebug
./gradlew testFullDebugUnitTest  # JVM tests (Robolectric)
```

Release builds without the signing keys are signed with the debug key.

## Author

Mario Di Raimondo. The code is written with the help of AI, starting from the author's needs and ideas.

## License

GPL version 3 only ([LICENSE](LICENSE)), with an additional permission to combine the app with Google
ML Kit ([NOTICE](NOTICE)). The `fdroid` flavor does not include ML Kit. Third-party
components keep their own licenses; the list is in the app, under Settings > About.
