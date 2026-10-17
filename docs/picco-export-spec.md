# Picco export spec (XTC / XTCH)

**Spec version: 1.** Shared by InkSync Boox (Kotlin) and InkSync Pro (Swift). Both apps must produce **byte-identical** files for the same decoded source pixels and the same options. The reference implementation is [`tools/picco_reference.py`](../tools/picco_reference.py). When this document and the Python file disagree, the Python file wins and this document gets fixed.

Target: BOOX Picco, 480 × 800 black-and-white e-ink, running Boox's fork of CrossPoint Reader. The file layout below is taken from CrossPoint's reader source (`lib/Xtc/Xtc/XtcTypes.h`, `XtcParser.cpp`, `src/activities/reader/XtcReaderActivity.cpp`).

## Conventions

- All integers in the file are **little-endian**. Offsets are absolute file offsets.
- All pixel math is **integer math**. `/` means integer division.
  - Every quotient in this spec has a non-negative numerator except error diffusion, which uses **truncation toward zero** (Kotlin and Swift `/` already do this). Python needs a helper (`tdiv`).
- Images are 8-bit grayscale arrays in row-major order, 0 = black, 255 = white, unless stated otherwise.
- **Brightness index**: 0 = black … 3 = white (2-bit), or 0 = black, 1 = white (1-bit).

## 1. File layout

```
0x0000  Header (56 bytes)
0x0038  Title  (128 bytes)
0x00B8  Author (64 bytes)
0x00F8  Chapter records (96 bytes each)          ← chapterOffset (only if chapters exist)
....    Page table (16 bytes per page)            ← pageTableOffset = 0xF8 + 96 × chapterCount
....    Page blocks, in page order                ← dataOffset = pageTableOffset + 16 × pageCount
```

Every page block is the same size (22-byte header + bitmap), so page *i*'s block starts at `dataOffset + i × blockSize`. The whole file can be written front to back in one pass.

### Header (56 bytes)

| Offset | Size | Field | Value |
|---|---|---|---|
| 0x00 | 4 | magic | 2-bit: `0x48435458` ("XTCH"); 1-bit: `0x00435458` ("XTC\0") |
| 0x04 | 1 | versionMajor | 1 |
| 0x05 | 1 | versionMinor | 0 |
| 0x06 | 2 | pageCount | number of pages, 1 to 65,535 |
| 0x08 | 1 | readDirection | 0 (page order is already in reading order) |
| 0x09 | 1 | hasMetadata | 1 |
| 0x0A | 1 | hasThumbnails | 0 |
| 0x0B | 1 | hasChapters | 1 if chapterCount > 0, else 0 |
| 0x0C | 4 | currentPage | 0 |
| 0x10 | 8 | metadataOffset | 0x38 |
| 0x18 | 8 | pageTableOffset | see layout |
| 0x20 | 8 | dataOffset | see layout |
| 0x28 | 8 | thumbOffset | 0 |
| 0x30 | 8 | chapterOffset | 0xF8 if chapters exist, else 0 (the reader reads all 8 bytes) |

### Title (0x38, 128 bytes) and author (0xB8, 64 bytes)

UTF-8 text cut to at most `size − 1` bytes without splitting a character, then padded with zero bytes to the full size. The author may be empty (all zeros).

### Chapter record (96 bytes)

| Offset | Size | Field |
|---|---|---|
| 0x00 | 80 | name, UTF-8, cut to ≤ 79 bytes on a character boundary, zero-padded |
| 0x50 | 2 | first page, 1-based |
| 0x52 | 2 | last page, 1-based, inclusive |
| 0x54 | 12 | zero |

### Page table entry (16 bytes)

| Offset | Size | Field |
|---|---|---|
| 0x00 | 8 | absolute offset of the page block |
| 0x08 | 4 | block size = 22 + bitmap size |
| 0x0C | 2 | 480 |
| 0x0E | 2 | 800 |

### Page block

A 22-byte header followed by the bitmap:

| Offset | Size | Field |
|---|---|---|
| 0x00 | 4 | 2-bit: `0x00485458` ("XTH\0"); 1-bit: `0x00475458` ("XTG\0") |
| 0x04 | 2 | 480 |
| 0x06 | 2 | 800 |
| 0x08 | 1 | 0 (color mode) |
| 0x09 | 1 | 0 (no compression) |
| 0x0A | 4 | bitmap size: 96,000 (2-bit) or 48,000 (1-bit). Must be exact or the reader rejects the page. |
| 0x0E | 8 | 0 (optional MD5, unused) |

### 2-bit bitmap (XTH): 96,000 bytes

- Two planes: plane 1 (bytes 0–47,999) holds the high bit of every pixel, plane 2 (bytes 48,000–95,999) the low bit.
- Column-major, starting from the **rightmost** column. For pixel (x, y):
  - `offset = (479 − x) × 100 + y / 8`
  - `bit = 7 − (y mod 8)`
- Value = (plane-1 bit × 2) + plane-2 bit. The values are not in brightness order:

| Brightness index | Shade | Stored value |
|---|---|---|
| 0 | black | 3 |
| 1 | dark gray | 1 |
| 2 | light gray | 2 |
| 3 | white | 0 |

### 1-bit bitmap (XTG): 48,000 bytes

Row-major, top to bottom, 60 bytes per row. The leftmost pixel is the most significant bit. 1 = white, 0 = black (the bit equals the brightness index).

### Packing test vectors

- 2-bit: a 4 × 8 image in which every row is brightness `[3, 1, 2, 0]` (white, dark gray, light gray, black) packs to `ff ff 00 00 ff 00 ff 00`.
- 1-bit: an 8 × 1 row with brightness `[0, 1, 0, 1, 0, 1, 0, 1]` packs to `55`.

## 2. Options

| Option | Values | Default |
|---|---|---|
| format | 2-bit XTCH, 1-bit XTC | 2-bit |
| layout | whole pages, panels, panels with page overview | whole pages |
| autoCrop | on/off | on |
| splitSpreads | on/off | on |
| dither | Floyd–Steinberg, Atkinson, none | Floyd–Steinberg |
| rotateWide | on/off | off |
| rightToLeft | the book's direction (manga = on) | from the reader |

## 3. Planning the output pages

Planning needs only each source page's pixel size (W × H) and the panel list. It does not look at pixels, so the page count and chapters are known before any rendering.

For each source page *i*, in source order:

1. **Spread:** the page is a spread if `splitSpreads` is on and `100 × W > 105 × H`. Its two **halves** are then used in reading order: left then right, or right then left when rightToLeft is on.
2. **Whole pages layout:** emit one output page per half (spread) or one for the whole page.
3. **Panel layouts:**
   - With overview: first emit an **overview** page (the whole cropped page, not split).
   - Then, for each half (or the whole page), emit its panels:
     - If the app has saved panels for it (InkSync Pro's Panel Editor, or InkSync Boox's tier grid), use them in their saved order after **merging small panels** (below).
     - Otherwise use the **auto grid**: exactly 2 cells.
   - Add a chapter record named `Page {i+1}` covering this source page's output pages.
4. Whole-page exports have no chapters.
5. Panels drawn on the uncropped page (InkSync Pro's Panel Editor): for a page that has such panels, auto-crop and spread splitting are turned off for that page only, so its base is the whole page and the panels apply as normalized rectangles. InkSync Boox's tier grid is applied per base instead, so it doesn't need this rule.

**Merging small panels.** Panels are normalized rectangles (0–1) relative to their half or page, in reading order. Work through them in order:
- A panel whose area is under 8% (`area × 100 < 8`) is joined to the previous kept panel; the result is the smallest rectangle containing both.
- If no panel has been kept yet, it is held and joined to the next one.
- A held panel left over at the end joins the last kept panel, or becomes the only panel if none was kept.

## 4. Rendering one output page

**Step 1. Grayscale.** For each ARGB pixel:
- `y = (299 R + 587 G + 114 B + 500) / 1000`
- If alpha < 255, blend over white: `y = (y × A + 255 × (255 − A) + 127) / 255`

**Step 2. Auto-crop** (if on; otherwise the crop is the whole page), adapted from Aidoku's border cropping:
- Box-shrink the gray page to `cw = max(1, W×2/5)` × `ch = max(1, H×2/5)` (box filter below).
- A small pixel is **content** if `5 ≤ v ≤ 170`. Find the bounding box (minX…maxX, minY…maxY) of the content pixels. If there are none, the crop is the whole page.
- Scale the box back to full size:
  - `x0 = minX×W/cw`, `x1 = ((maxX+1)×W + cw − 1)/cw`
  - `y0 = minY×H/ch`, `y1 = ((maxY+1)×H + ch − 1)/ch`
- Pad: `padX = (W×12 + 500)/1000`, `padY = (H×12 + 500)/1000`. Expand by the padding and clamp to the page.
- If `(x1−x0)×(y1−y0)×100 < 15×W×H`, the crop is the whole page.

**Step 3. Region.** All rectangles are half-open: [x0, x1) × [y0, y1).
- **Base:**
  - whole or overview: the crop.
  - Spread halves: split the crop at `mid = cx0 + (cx1 − cx0)/2`. Left half = [cx0, mid), right half = [mid, cx1).
- **Panel from a normalized rectangle (l, t, r, b)** on a base of width bw and height bh:
  - `x0 = bx0 + floor(l × bw)`, `x1 = bx0 + ceil(r × bw)`, using double-precision multiplication.
  - Clamp x0 to [bx0, bx0 + bw − 1], then x1 to [x0 + 1, bx0 + bw]. Do the same for y.
- **Auto-grid cell k (0 or 1):**
  - If `bh ≥ bw`: split rows at `by0 + bh/2`; cell 0 is the top.
  - Otherwise: split columns at `bx0 + bw/2`; cell 0 is the left one, or the right one when rightToLeft is on.
- **Panel padding** (panels and auto cells only, not whole/overview): `padX = (bw×2 + 50)/100`, `padY = (bh×2 + 50)/100`. Expand by the padding and clamp to the base.

**Step 4. Rotate** (if rotateWide is on and `5×rw > 6×rh`): rotate 90° clockwise. The new image is rh wide and rw tall, and `new(x, y) = old(y, rh − 1 − x)`.

**Step 5. Fit to 480 × 800.**
- Choose the target size:
  - If `480×rh ≤ 800×rw`: `dw = 480`, `dh = max(1, (rh×480 + rw/2)/rw)`.
  - Otherwise: `dh = 800`, `dw = max(1, (rw×800 + rh/2)/rh)`.
- Cap enlargement at 2.5×: if `2×dw > 5×rw`, then `dw = max(1, 5×rw/2)` and `dh = max(1, 5×rh/2)`.
- Use **bilinear** if `dw×dh > rw×rh`, otherwise **box**.

*Box (shrinking).* For each output pixel (ox, oy):
- `x0 = ox×sw/dw`, `x1 = max(x0 + 1, (ox+1)×sw/dw)`, and the same for y.
- Output = `(sum + n/2)/n`, where `n = (x1−x0)×(y1−y0)` and sum covers that box.

*Bilinear (enlarging).* Use 64-bit math. For each output coordinate o along an axis with source size sn and output size dn:
- `fp = ((2o + 1) × sn × 65536)/(2 × dn) − 32768`, clamped to [0, (sn − 1) × 65536].
- `i0 = fp >> 16`, `i1 = min(i0 + 1, sn − 1)`, `w = fp & 0xFFFF`.
- `top = p(x0,y0)×(65536−wx) + p(x1,y0)×wx`, `bot = p(x0,y1)×(65536−wx) + p(x1,y1)×wx`.
- Output = `(top×(65536−wy) + bot×wy + 2³¹) >> 32`.

**Step 6. Levels and gamma**, on the dw × dh image:
- Histogram it. `k = N×5/1000`, where N = dw × dh.
- `lo` = the smallest value whose cumulative count from the bottom exceeds k. `hi` = the largest value whose cumulative count from the top exceeds k.
- If `hi − lo ≥ 16`, stretch:
  - `v ≤ lo` → 0
  - `v ≥ hi` → 255
  - otherwise `((v−lo)×255 + (hi−lo)/2)/(hi−lo)`
- Then apply the gamma table `GAMMA_LUT` from the reference file to every pixel (always, even without a stretch). It is `round(255 × (v/255)^(10/9))`, stored as a fixed table so no platform's `pow` is involved.

**Step 7. Quantize and dither** to brightness indices.
- **Quantize:**
  - 2-bit: `b = (v×3 + 127)/255`, level value = `b×85`.
  - 1-bit: `b = 1 if v ≥ 128 else 0`, level value = `b×255`.
- **Scan order (serpentine):** even rows left→right, odd rows right→left. Let `d = +1` on even rows and `−1` on odd rows.
- **For each pixel:** `v = pixel + accumulated error` (no error for "none"). Clamp v to 0…255, quantize it, and set `err = v − level`.
- Spread the error (neighbours outside the image are skipped):
  - **Floyd–Steinberg:**
    - `e7 = err×7/16`, `e3 = err×3/16`, `e5 = err×5/16`, `e1 = err − e7 − e3 − e5` (truncating divisions)
    - Add e7 to (x+d, y), e3 to (x−d, y+1), e5 to (x, y+1), e1 to (x+d, y+1).
  - **Atkinson:**
    - `e = err/8` (truncating)
    - Add e to (x+d, y), (x+2d, y), (x−d, y+1), (x, y+1), (x+d, y+1) and (x, y+2).
  - **None:** nothing.

**Step 8. Compose.** Fill a 480 × 800 canvas with white (index 3, or 1 for 1-bit). Place the dithered image at `((480 − dw)/2, (800 − dh)/2)`. Only the image is dithered; the white border is not. Then pack the canvas as in section 1.

## 5. Fixtures (both apps must reproduce these hashes)

The source pages are generated, not decoded, so no image decoder is involved. Pages are `(W, H, seed, mx, my)`: `(300, 420, 1, 30, 40)`, `(840, 600, 2, 50, 36)`, `(200, 1000, 3, 12, 90)`. Each pixel:
- Inside the margin band (`x < mx`, `x ≥ W − mx`, `y < my` or `y ≥ H − my`): `0xFFFFFFFF`.
- Otherwise: `R = (7x + 3y) & 255`, `G = (xy + 31×seed) & 255`, `B = (2x + 5y + seed) & 255`, `A = 128` if `(x/16 + y/16) mod 5 == 0`, else 255.

Title `"Fixture"`, empty author, auto-grid panels:

| Fixture | Options | Bytes | SHA-256 |
|---|---|---|---|
| A | 2-bit, panels with overview, autoCrop, split, Floyd–Steinberg, no rotate, left-to-right | 1,056,954 | `023659e813f1a92b631e9f7d0aa20557d895a6d5efa8575516a33642642148e8` |
| B | 1-bit, whole pages, no autoCrop, split, Atkinson, rotate, right-to-left | 192,400 | `30a6c74a490b401865d9cdd102cdf5c3e2f1129fd0dd25ea778513e384c4c67c` |
| C | 2-bit, whole pages, autoCrop, no split, no dither | 288,362 | `220f867b03f59cdf3afc7dc0d6681bd3722573b5514ad1803e5b2c11b10a6d9e` |

The auto-crop of the first fixture page is `(26, 35, 274, 385)`.

## 6. What is and isn't byte-identical across apps

- **Identical:** everything from decoded pixels onward. The fixtures prove it.
- **Not identical:**
  - Decoding itself: JPEG decoders and PDF renderers differ slightly between Android and iOS.
  - Panels: they come from each app's own editor (tier grid vs Panel Editor).
- **Source decode rules (both apps):**
  - Images: decode at full size. If W × H > 16,000,000, decode at a smaller size (each platform's own way).
  - PDF pages: render 1440 px wide, height `(1440×ph + pw/2)/pw` from the page size in points, on white.

## 7. Position map

The export also saves (outside the file) one entry per output page: source page index, kind (`whole`, `first`, `second`, `overview`) and panel number. It's needed for future reading-progress sync between the Picco and the apps.

## 8. Sending to the device and EPUB optimizing (not byte-identical, but shared behavior)

- **Upload** (CrossPoint web API):
  - `GET http://<host>/api/status` checks the device.
  - `POST http://<host>/mkdir`, form `name=Comics&path=/`, creates the folder (errors ignored).
  - `POST http://<host>/upload?path=/Comics` uploads the file as multipart field `file`.
  - Try the address the user entered, then `crosspoint.local`.
- **EPUB optimizer:**
  - Shrink images larger than 480 × 800 (keeping aspect ratio) and convert them to gray. Keep each image's format (JPEG at quality 80, PNG lossless).
  - Text, CSS and structure are unchanged.
  - Write `mimetype` first and uncompressed.
  - Refuse DRM: `META-INF/rights.xml`, or `META-INF/encryption.xml` listing anything other than font obfuscation (`http://www.idpf.org/2008/embedding`, `http://ns.adobe.com/pdf/enc#RC`).
