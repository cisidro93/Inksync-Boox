# InkSync Boox

A reader for the **Onyx Boox Note Air 4C** (10.3", Kaleido 3 color e-ink, Android 13): EPUB books, PDFs, and comics/manga (CBZ, CBR, CB7) in one app, laid out for e-ink.

## What it does

- **Library** of recently opened documents. Each one reopens on the page you left, and the last document reopens when you launch the app.
- **EPUB reader** with real pagination: one screen per page, four text sizes, one or two columns, bold/italic kept, images (covers and illustrations, scaled to fit), chapter list. Long-press a paragraph to highlight it.
- **PDF and comic viewer** that renders each page (or zoomed region) at screen resolution, so text stays sharp on the 300 PPI panel.
- **Comics and manga**: CBZ, CBR (RAR4 and RAR5) and CB7 archives; right-to-left page turning for manga; wide two-page spreads shown as two screens (right half first for manga); optional black background; natural page ordering.
- **Page display**: automatic margin cropping (detected per page), grayscale mode (full 300 PPI on the Kaleido 3 panel instead of the 150 PPI color layer), contrast boost, and the next two views rendered ahead so page turns don't wait.
- **Tiers ("NeoFlow")**: split a page into a grid (2×1, 2×2, 3×3, …) and step through it with page turns. Useful for two-column papers and comic panels on a portrait screen.
- **Pen**: write, highlight, mark an area, or erase on PDF and comic pages, with palm rejection. On Boox, the Pen and Marker tools use Onyx's low-latency pen layer, and the pen's eraser end erases. A side notes pane (Cornell, ruled, or dot grid) sits next to any document. A quick pen tap in reading mode turns the page instead of leaving a dot.
- **Highlights** per document, with jump-to-source and Markdown export to any folder you choose.
- **E-ink care**: no animations, ripples, or overscroll; a full-screen refresh every N page turns (1, 3, 5, 10, or never) plus a manual "Refresh now".

Everything you add (position, highlights, ink, notes) is saved automatically on the device.

## Page turning

| Input | Next | Previous |
|---|---|---|
| Tap, "Left / right edges" layout | right quarter | left quarter |
| Tap, "Kindle style" layout (top third opens the menu) | anywhere except the left third | left third |
| Keys / remotes | Volume down, Page down, →, ↓, Space | Volume up, Page up, ←, ↑ |

Manga mode mirrors the tap zones. Tap the middle of the page (or the top third in Kindle style) for the menu. Back closes the menu, then returns to the library.

## E-ink refresh on Boox

Full-screen refresh uses Onyx's framework method `View.refreshScreen(x, y, w, h, mode)` with a GC16 full-update waveform. This is the same call KOReader's Android launcher uses for the Note Air 4C, and the one Onyx's own `onyxsdk-device` library (1.3.6, `SDMDevice`) looks up internally. Because Android restricts reflection into non-SDK methods, the lookup falls back to [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) (as the Boox notes app Notable does). On other devices it does nothing.

Everyday partial refresh is left to the system, so the speed you pick for this app in Boox **E-Ink Center** (HD, Balanced, Fast, Ultrafast) still applies. The library screen shows whether the refresh API was found on your device.

## Pen layer on Boox

With the Pen or Marker tool selected, ink is drawn by Onyx's firmware through `TouchHelper` from `onyxsdk-pen` (the same low-latency path the Boox Notes app uses). The sequence follows Onyx's Pen SDK docs and the Notable app, which models NeoReader (`com.onyx.kreader`): the firmware draws the stroke live; on pen-up the app saves and draws it; then the app briefly releases the screen (500 ms on color panels) so the panel shows the app's version. Raw drawing is switched off while the menu is open, while adjusting tiers, and during page turns, because it holds the screen still while active.

## Comics in RAR and 7-Zip

`.cbr` and `.cb7` files are read with [libarchive-android](https://github.com/zhanghai/libarchive-android) (as Mihon does), which handles RAR5. These formats are often "solid", so the pages are unpacked once into the app cache when the comic opens; large archives take a few seconds. Files named `.cbr` that are really zips are detected and read directly.

## Ideas borrowed from Mihon and Aidoku

Margin detection is ported from Aidoku's `CropBordersProcessor` (near-white and near-black pixels count as border). The "Kindle style" tap layout is Mihon's `KindlishNavigation`. Spread splitting and page preloading follow both apps' readers.

## Send to Picco

For the BOOX Picco (3.97", 480 × 800, running Boox's fork of CrossPoint Reader), open a PDF or comic, tap the middle of the page, and choose **Picco**. The app converts it to CrossPoint's `.xtch` (4 grays) or `.xtc` (black and white) format:

- **Whole pages** (spreads split into halves), **panels** (one panel per screen, so speech bubbles are readable on a 4" screen), or **panels with a page overview** first. Panels follow your tier grid; with no grid, each page is split in two.
- Auto-crop, Floyd–Steinberg or Atkinson dithering (or none for crisp line art), optional rotation of wide panels, and manga order.
- Each original page becomes a chapter, so the Picco's chapter menu can jump to it.
- Save the file, share it, or **send it over Wi-Fi** to the Picco's file transfer screen (CrossPoint's `/upload` API).
- For EPUBs, **Optimize for Picco** shrinks and grays the images so the Picco opens the book quickly.

The format and every pixel step are defined in [`docs/picco-export-spec.md`](docs/picco-export-spec.md), shared with InkSync Pro (iOS). [`tools/picco_reference.py`](tools/picco_reference.py) is a reference implementation; the unit tests check that this app produces exactly its fixture files, byte for byte.

## Limitations

- EPUB: tables, SVG drawings and publisher CSS aren't rendered; DRM-protected books can't be opened.
- Password-protected RAR/7z comics aren't supported.
- The pen layer, refresh and comic unpacking need testing on a real Note Air 4C; none of it can be exercised in the emulator.
- Picco files and Wi-Fi sending are built from CrossPoint's documented format and API but haven't been tried on a Picco yet; Boox's firmware fork may differ.

## Building

CI (`.github/workflows/build-boox-apk.yml`) runs the unit tests and builds a debug APK on every push and pull request. Pushes to `main` publish the APK to the `latest-boox` release, which you can download in the Boox browser or install with `adb install`.

Locally: `./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 36).
