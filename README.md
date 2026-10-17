# InkSync Boox

A reader for the **Onyx Boox Note Air 4C** (10.3", Kaleido 3 color e-ink, Android 13): EPUB books, PDFs, and CBZ comics/manga in one app, laid out for e-ink.

## What it does

- **Library** of recently opened documents. Each one reopens on the page you left, and the last document reopens when you launch the app.
- **EPUB reader** with real pagination: one screen per page, four text sizes, one or two columns, bold/italic kept, chapter list. Long-press a paragraph to highlight it.
- **PDF and CBZ viewer** that renders each page (or zoomed region) at screen resolution, so text stays sharp on the 300 PPI panel.
- **Comics and manga**: right-to-left page turning for manga, optional black background, natural page ordering inside CBZ archives.
- **Tiers ("NeoFlow")**: split a page into a grid (2×1, 2×2, 3×3, …) and step through it with page turns. Useful for two-column papers and comic panels on a portrait screen.
- **Pen**: write, highlight, mark an area, or erase on PDF and comic pages, with palm rejection. A side notes pane (Cornell, ruled, or dot grid) sits next to any document. A quick pen tap in reading mode turns the page instead of leaving a dot.
- **Highlights** per document, with jump-to-source and Markdown export to any folder you choose.
- **E-ink care**: no animations, ripples, or overscroll; a full-screen refresh every N page turns (1, 3, 5, 10, or never) plus a manual "Refresh now".

Everything you add (position, highlights, ink, notes) is saved automatically on the device.

## Page turning

| Input | Next | Previous |
|---|---|---|
| Tap | right quarter of the screen | left quarter |
| Tap (manga mode) | left quarter | right quarter |
| Keys / remotes | Volume down, Page down, →, ↓, Space | Volume up, Page up, ←, ↑ |

Tap the middle of the page for the menu. Back closes the menu, then returns to the library.

## E-ink refresh on Boox

Full-screen refresh uses Onyx's framework method `View.refreshScreen(x, y, w, h, mode)` with a GC16 full-update waveform. This is the same call KOReader's Android launcher uses for the Note Air 4C, and the one Onyx's own `onyxsdk-device` library (1.3.6, `SDMDevice`) looks up internally. Because Android restricts reflection into non-SDK methods, the lookup falls back to [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (as the Boox notes app Notable does). On other devices it does nothing.

Everyday partial refresh is left to the system, so the speed you pick for this app in Boox **E-Ink Center** (HD, Balanced, Fast, Ultrafast) still applies. The library screen shows whether the refresh API was found on your device.

## Limitations

- EPUB: images, tables and publisher CSS aren't shown; DRM-protected books can't be opened.
- No CBR (RAR) comics.
- Pen ink is drawn by the app, not Onyx's low-latency pen layer, so it lags more than the Boox Notes app.
- The refresh and pen code paths need testing on a real Note Air 4C; they can't be exercised in the emulator.

## Building

CI (`.github/workflows/build-boox-apk.yml`) runs the unit tests and builds a debug APK on every push and pull request. Pushes to `main` publish the APK to the `latest-boox` release, which you can download in the Boox browser or install with `adb install`.

Locally: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 36).
