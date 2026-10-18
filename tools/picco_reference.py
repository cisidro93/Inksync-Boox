#!/usr/bin/env python3
"""
Reference implementation of the Picco export spec (docs/picco-export-spec.md).

This is the tie-breaker for InkSync Boox (Kotlin) and InkSync Pro (Swift): all three must
produce byte-identical .xtc/.xtch files for the same decoded pixels and options. It is slow
(pure Python) but written to read like the spec. Run it to print the fixture hashes:

    python3 tools/picco_reference.py

Spec version: 2 (adds the Picco EPUB output; XTC/XTCH output is unchanged from version 1)
"""
import hashlib
import struct

SPEC_VERSION = 2
SCREEN_W, SCREEN_H = 480, 800
# Default CrossPoint 1.2 reading area on a 480 x 800 panel (margins and status bar removed).
EPUB_W, EPUB_H = 464, 764

GAMMA_LUT = [
    0, 1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 8, 9, 9, 10, 11, 12, 13, 13, 14, 15, 16, 17, 18, 18, 19, 20, 21, 22, 23, 24, 25,
    25, 26, 27, 28, 29, 30, 31, 32, 33, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46, 47, 48, 49, 50,
    51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77,
    78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104,
    105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127,
    128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 144, 145, 146, 147, 148, 149, 150,
    151, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173,
    174, 175, 176, 177, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 193, 194, 195, 196, 197,
    198, 199, 200, 201, 202, 203, 204, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 218, 219, 220, 221,
    222, 223, 224, 225, 226, 227, 228, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 241, 242, 243, 244, 245,
    246, 247, 248, 249, 251, 252, 253, 254, 255,
]
assert len(GAMMA_LUT) == 256

# XTH 2-bit value for brightness index 0 (black) .. 3 (white).
XTH_VALUE = [3, 1, 2, 0]


def tdiv(a, b):
    """Integer division truncating toward zero (Kotlin/Swift semantics)."""
    q = abs(a) // abs(b)
    return q if (a >= 0) == (b > 0) else -q


# ---------------------------------------------------------------- pixels

def to_gray(argb, w, h):
    out = bytearray(w * h)
    for i, c in enumerate(argb):
        a = (c >> 24) & 255
        r = (c >> 16) & 255
        g = (c >> 8) & 255
        b = c & 255
        y = (299 * r + 587 * g + 114 * b + 500) // 1000
        if a < 255:
            y = (y * a + 255 * (255 - a) + 127) // 255
        out[i] = y
    return out


def box(src, sw, sh, dw, dh):
    out = bytearray(dw * dh)
    for oy in range(dh):
        y0 = oy * sh // dh
        y1 = max(y0 + 1, (oy + 1) * sh // dh)
        for ox in range(dw):
            x0 = ox * sw // dw
            x1 = max(x0 + 1, (ox + 1) * sw // dw)
            s = 0
            for y in range(y0, y1):
                row = y * sw
                for x in range(x0, x1):
                    s += src[row + x]
            n = (x1 - x0) * (y1 - y0)
            out[oy * dw + ox] = (s + n // 2) // n
    return out


def _bilinear_axis(o, sn, dn):
    fp = ((2 * o + 1) * sn * 65536) // (2 * dn) - 32768
    fp = max(0, min(fp, (sn - 1) * 65536))
    i0 = fp >> 16
    return i0, min(i0 + 1, sn - 1), fp & 0xFFFF


def bilinear(src, sw, sh, dw, dh):
    out = bytearray(dw * dh)
    xs = [_bilinear_axis(ox, sw, dw) for ox in range(dw)]
    for oy in range(dh):
        y0, y1, wy = _bilinear_axis(oy, sh, dh)
        for ox in range(dw):
            x0, x1, wx = xs[ox]
            top = src[y0 * sw + x0] * (65536 - wx) + src[y0 * sw + x1] * wx
            bot = src[y1 * sw + x0] * (65536 - wx) + src[y1 * sw + x1] * wx
            out[oy * dw + ox] = (top * (65536 - wy) + bot * wy + (1 << 31)) >> 32
    return out


def autocrop(gray, w, h):
    cw, ch = max(1, w * 2 // 5), max(1, h * 2 // 5)
    small = box(gray, w, h, cw, ch)
    min_x, min_y, max_x, max_y = cw, ch, -1, -1
    for y in range(ch):
        for x in range(cw):
            v = small[y * cw + x]
            if v > 170 or v < 5:
                continue
            min_x = min(min_x, x); max_x = max(max_x, x)
            min_y = min(min_y, y); max_y = max(max_y, y)
    if max_x < 0:
        return (0, 0, w, h)
    x0 = min_x * w // cw
    x1 = ((max_x + 1) * w + cw - 1) // cw
    y0 = min_y * h // ch
    y1 = ((max_y + 1) * h + ch - 1) // ch
    pad_x = (w * 12 + 500) // 1000
    pad_y = (h * 12 + 500) // 1000
    x0, y0 = max(0, x0 - pad_x), max(0, y0 - pad_y)
    x1, y1 = min(w, x1 + pad_x), min(h, y1 + pad_y)
    if (x1 - x0) * (y1 - y0) * 100 < 15 * w * h:
        return (0, 0, w, h)
    return (x0, y0, x1, y1)


def subimage(gray, w, rect):
    x0, y0, x1, y1 = rect
    rw = x1 - x0
    out = bytearray(rw * (y1 - y0))
    for y in range(y0, y1):
        out[(y - y0) * rw:(y - y0 + 1) * rw] = gray[y * w + x0:y * w + x1]
    return out


def rotate_cw(img, w, h):
    out = bytearray(w * h)  # new size h x w
    for ny in range(w):
        for nx in range(h):
            out[ny * h + nx] = img[(h - 1 - nx) * w + ny]
    return out, h, w


def fit(img, rw, rh, tw=SCREEN_W, th=SCREEN_H):
    if tw * rh <= th * rw:
        dw, dh = tw, max(1, (rh * tw + rw // 2) // rw)
    else:
        dw, dh = max(1, (rw * th + rh // 2) // rh), th
    if 2 * dw > 5 * rw:
        dw, dh = max(1, 5 * rw // 2), max(1, 5 * rh // 2)
    if dw * dh > rw * rh:
        return bilinear(img, rw, rh, dw, dh), dw, dh
    return box(img, rw, rh, dw, dh), dw, dh


def levels_gamma(img):
    n = len(img)
    hist = [0] * 256
    for v in img:
        hist[v] += 1
    k = n * 5 // 1000
    cum, lo = 0, 0
    for v in range(256):
        cum += hist[v]
        if cum > k:
            lo = v
            break
    cum, hi = 0, 255
    for v in range(255, -1, -1):
        cum += hist[v]
        if cum > k:
            hi = v
            break
    out = bytearray(n)
    stretch = hi - lo >= 16
    for i, v in enumerate(img):
        if stretch:
            if v <= lo:
                v = 0
            elif v >= hi:
                v = 255
            else:
                v = ((v - lo) * 255 + (hi - lo) // 2) // (hi - lo)
        out[i] = GAMMA_LUT[v]
    return out


def _quant(v, two_bit):
    if two_bit:
        b = (v * 3 + 127) // 255
        return b, b * 85
    b = 1 if v >= 128 else 0
    return b, b * 255


def dither(img, w, h, two_bit, method):
    """Returns brightness indices (0=black .. 3 or 1=white)."""
    out = bytearray(w * h)
    rows = [[0] * w for _ in range(3)]  # error for rows y, y+1, y+2
    for y in range(h):
        d = 1 if y % 2 == 0 else -1
        xs = range(w) if d == 1 else range(w - 1, -1, -1)
        cur, nxt, nxt2 = rows
        for x in xs:
            v = img[y * w + x] + (cur[x] if method != "none" else 0)
            v = max(0, min(255, v))
            b, level = _quant(v, two_bit)
            out[y * w + x] = b
            err = v - level
            if method == "fs":
                e7, e3, e5 = tdiv(err * 7, 16), tdiv(err * 3, 16), tdiv(err * 5, 16)
                e1 = err - e7 - e3 - e5
                if 0 <= x + d < w: cur[x + d] += e7
                if 0 <= x - d < w: nxt[x - d] += e3
                nxt[x] += e5
                if 0 <= x + d < w: nxt[x + d] += e1
            elif method == "atkinson":
                e = tdiv(err, 8)
                if 0 <= x + d < w: cur[x + d] += e
                if 0 <= x + 2 * d < w: cur[x + 2 * d] += e
                if 0 <= x - d < w: nxt[x - d] += e
                nxt[x] += e
                if 0 <= x + d < w: nxt[x + d] += e
                nxt2[x] += e
        rows = [nxt, nxt2, [0] * w]
    return out


def compose(indices, dw, dh, two_bit):
    white = 3 if two_bit else 1
    canvas = bytearray([white]) * (SCREEN_W * SCREEN_H)
    ox, oy = (SCREEN_W - dw) // 2, (SCREEN_H - dh) // 2
    for y in range(dh):
        canvas[(oy + y) * SCREEN_W + ox:(oy + y) * SCREEN_W + ox + dw] = indices[y * dw:(y + 1) * dw]
    return canvas


def compose_gray(img, dw, dh, tw, th):
    """Smooth gray image centered on a white tw x th canvas (EPUB screens)."""
    canvas = bytearray([255]) * (tw * th)
    ox, oy = (tw - dw) // 2, (th - dh) // 2
    for y in range(dh):
        canvas[(oy + y) * tw + ox:(oy + y) * tw + ox + dw] = img[y * dw:(y + 1) * dw]
    return canvas


def pack_xth(idx, w, h):
    colb = (h + 7) // 8
    p1, p2 = bytearray(w * colb), bytearray(w * colb)
    for y in range(h):
        for x in range(w):
            v = XTH_VALUE[idx[y * w + x]]
            off = (w - 1 - x) * colb + y // 8
            bit = 1 << (7 - y % 8)
            if v & 2: p1[off] |= bit
            if v & 1: p2[off] |= bit
    return bytes(p1 + p2)


def pack_xtg(idx, w, h):
    rowb = (w + 7) // 8
    out = bytearray(rowb * h)
    for y in range(h):
        for x in range(w):
            if idx[y * w + x]:
                out[y * rowb + x // 8] |= 1 << (7 - x % 8)
    return bytes(out)


# ---------------------------------------------------------------- layout

def norm_to_px(lo, hi, base0, size):
    import math
    a = base0 + math.floor(lo * size)
    b = base0 + math.ceil(hi * size)
    a = max(base0, min(a, base0 + size - 1))
    b = max(a + 1, min(b, base0 + size))
    return a, b


def merge_small(rects):
    """rects: normalized (l,t,r,b) in reading order. Panels under 8% area join a neighbour."""
    out, pending = [], None
    for r in rects:
        if pending is not None:
            r = _union(pending, r); pending = None
        area = (r[2] - r[0]) * (r[3] - r[1])
        if area * 100 < 8:
            if out:
                out[-1] = _union(out[-1], r)
            else:
                pending = r
            continue
        out.append(r)
    if pending is not None:
        if out:
            out[-1] = _union(out[-1], pending)
        else:
            out.append(pending)
    return out


def _union(a, b):
    return (min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))


def plan(page_sizes, opts, panels_for=None):
    """Returns (outputs, chapters). Output = dict(src, base, cell); base in whole/first/second/overview."""
    outputs, chapters = [], []
    for i, (w, h) in enumerate(page_sizes):
        start = len(outputs)
        spread = opts["split_spreads"] and 100 * w > 105 * h
        bases = ["first", "second"] if spread else ["whole"]
        if opts["layout"] == "whole":
            for b in bases:
                outputs.append(dict(src=i, base=b, cell=None))
        else:
            if opts["layout"] == "panels_overview":
                outputs.append(dict(src=i, base="overview", cell=None))
            for b in bases:
                rects = panels_for(i, b) if panels_for else None
                if rects:
                    for r in merge_small(rects):
                        outputs.append(dict(src=i, base=b, cell=("rect", r)))
                else:
                    for k in (0, 1):
                        outputs.append(dict(src=i, base=b, cell=("auto", k)))
            chapters.append(("Page %d" % (i + 1), start + 1, len(outputs)))
    return outputs, chapters


def base_rect(crop, base, rtl):
    x0, y0, x1, y1 = crop
    if base in ("whole", "overview"):
        return crop
    mid = x0 + (x1 - x0) // 2
    left, right = (x0, y0, mid, y1), (mid, y0, x1, y1)
    first, second = (right, left) if rtl else (left, right)
    return first if base == "first" else second


def cell_rect(base, cell, rtl):
    bx0, by0, bx1, by1 = base
    bw, bh = bx1 - bx0, by1 - by0
    if cell is None:
        return base
    kind, v = cell
    if kind == "auto":
        if bh >= bw:
            mid = by0 + bh // 2
            r = (bx0, by0, bx1, mid) if v == 0 else (bx0, mid, bx1, by1)
        else:
            mid = bx0 + bw // 2
            cells = [(bx0, by0, mid, by1), (mid, by0, bx1, by1)]
            if rtl: cells.reverse()
            r = cells[v]
    else:
        l, t, rr, b = v
        x0, x1 = norm_to_px(l, rr, bx0, bw)
        y0, y1 = norm_to_px(t, b, by0, bh)
        r = (x0, y0, x1, y1)
    pad_x, pad_y = (bw * 2 + 50) // 100, (bh * 2 + 50) // 100
    return (max(bx0, r[0] - pad_x), max(by0, r[1] - pad_y), min(bx1, r[2] + pad_x), min(by1, r[3] + pad_y))


# ---------------------------------------------------------------- file

def _fixed(text, size):
    raw = text.encode("utf-8")
    cut = size - 1
    while len(raw) > cut:
        raw = raw[:cut]
        try:
            raw.decode("utf-8"); break
        except UnicodeDecodeError:
            cut -= 1
    return raw + b"\0" * (size - len(raw))


def _region(gray, w, h, out, opts):
    crop = autocrop(gray, w, h) if opts["autocrop"] else (0, 0, w, h)
    rect = cell_rect(base_rect(crop, out["base"], opts["rtl"]), out["cell"], opts["rtl"])
    rw, rh = rect[2] - rect[0], rect[3] - rect[1]
    img = subimage(gray, w, rect)
    if opts["rotate"] and 5 * rw > 6 * rh:
        img, rw, rh = rotate_cw(img, rw, rh)
    return img, rw, rh


def render_epub_screen(gray, w, h, out, opts):
    """EPUB screen: steps 1-6, then a smooth (undithered) 464 x 764 gray canvas."""
    img, rw, rh = _region(gray, w, h, out, opts)
    img, dw, dh = fit(img, rw, rh, EPUB_W, EPUB_H)
    return compose_gray(levels_gamma(img), dw, dh, EPUB_W, EPUB_H)


def render_page(gray, w, h, out, opts):
    img, rw, rh = _region(gray, w, h, out, opts)
    img, dw, dh = fit(img, rw, rh)
    img = levels_gamma(img)
    idx = dither(img, dw, dh, opts["two_bit"], opts["dither"])
    canvas = compose(idx, dw, dh, opts["two_bit"])
    return pack_xth(canvas, SCREEN_W, SCREEN_H) if opts["two_bit"] else pack_xtg(canvas, SCREEN_W, SCREEN_H)


def export(pages, opts, title="", author=""):
    """pages: list of (argb_list, w, h). Returns file bytes."""
    sizes = [(w, h) for _, w, h in pages]
    outputs, chapters = plan(sizes, opts)
    if opts["layout"] == "whole":
        chapters = []
    two = opts["two_bit"]
    bitmap = SCREEN_W * ((SCREEN_H + 7) // 8) * 2 if two else ((SCREEN_W + 7) // 8) * SCREEN_H
    block = 22 + bitmap
    n = len(outputs)
    chapter_off = 0xF8 if chapters else 0
    table_off = 0xF8 + 96 * len(chapters)
    data_off = table_off + 16 * n
    head = struct.pack("<IBBHBBBBIQQQQQ",
                       0x48435458 if two else 0x00435458, 1, 0, n, 0, 1, 0, 1 if chapters else 0, 0,
                       0x38, table_off, data_off, 0, chapter_off)
    assert len(head) == 56
    parts = [head, _fixed(title, 128), _fixed(author, 64)]
    for name, s, e in chapters:
        parts.append(_fixed(name, 80) + struct.pack("<HH", s, e) + b"\0" * 12)
    for i in range(n):
        parts.append(struct.pack("<QIHH", data_off + i * block, block, SCREEN_W, SCREEN_H))
    gray_cache = {}
    for out in outputs:
        src = out["src"]
        if src not in gray_cache:
            gray_cache.clear()
            argb, w, h = pages[src]
            gray_cache[src] = (to_gray(argb, w, h), w, h)
        gray, w, h = gray_cache[src]
        bits = render_page(gray, w, h, out, opts)
        parts.append(struct.pack("<IHHBBIQ", 0x00485458 if two else 0x00475458, SCREEN_W, SCREEN_H, 0, 0, bitmap, 0))
        parts.append(bits)
    return b"".join(parts)


def epub_screens(pages, opts):
    """All EPUB screens (raw gray canvases) and the table of contents (title, first screen index)."""
    sizes = [(w, h) for _, w, h in pages]
    outputs, _ = plan(sizes, opts)
    toc, screens, gray_cache = [], [], {}
    for n, out in enumerate(outputs):
        src = out["src"]
        if not toc or toc[-1][2] != src:
            toc.append(("Page %d" % (src + 1), n, src))
        if src not in gray_cache:
            gray_cache.clear()
            argb, w, h = pages[src]
            gray_cache[src] = (to_gray(argb, w, h), w, h)
        gray, w, h = gray_cache[src]
        screens.append(render_epub_screen(gray, w, h, out, opts))
    return screens, [(t, i) for t, i, _ in toc]


def epub_identifier(title, author, screen_count):
    """Name-based book id for the Picco EPUB (spec section 9)."""
    b = bytearray(hashlib.sha256(("inksync-picco\n%s\n%s\n%d" % (title, author, screen_count)).encode("utf-8")).digest()[:16])
    b[6] = (b[6] & 0x0F) | 0x50
    b[8] = (b[8] & 0x3F) | 0x80
    h = b.hex()
    return "urn:uuid:%s-%s-%s-%s-%s" % (h[:8], h[8:12], h[12:16], h[16:20], h[20:])


# ---------------------------------------------------------------- fixtures

def fixture_page(w, h, seed, mx, my):
    px = []
    for y in range(h):
        for x in range(w):
            if x < mx or x >= w - mx or y < my or y >= h - my:
                px.append(0xFFFFFFFF)
                continue
            r = (x * 7 + y * 3) & 255
            g = (x * y + seed * 31) & 255
            b = (x * 2 + y * 5 + seed) & 255
            a = 128 if ((x // 16 + y // 16) % 5 == 0) else 255
            px.append((a << 24) | (r << 16) | (g << 8) | b)
    return px, w, h


FIXTURE_PAGES = [(300, 420, 1, 30, 40), (840, 600, 2, 50, 36), (200, 1000, 3, 12, 90)]

FIXTURES = {
    "A_xtch_panels_overview_fs": dict(two_bit=True, layout="panels_overview", autocrop=True, split_spreads=True,
                                      dither="fs", rotate=False, rtl=False),
    "B_xtc_whole_atkinson_rtl_rotate": dict(two_bit=False, layout="whole", autocrop=False, split_spreads=True,
                                            dither="atkinson", rotate=True, rtl=True),
    "C_xtch_whole_none_nosplit": dict(two_bit=True, layout="whole", autocrop=True, split_spreads=False,
                                      dither="none", rotate=False, rtl=False),
}

# EPUB fixtures: SHA-256 of all screens' raw gray bytes concatenated (464 x 764 each), in order.
EPUB_FIXTURES = {
    "D_epub_panels_overview": dict(layout="panels_overview", autocrop=True, split_spreads=True, rotate=False, rtl=False),
    "E_epub_whole_rotate_rtl": dict(layout="whole", autocrop=False, split_spreads=True, rotate=True, rtl=True),
}


def main():
    # Packing vectors from the spec.
    # Row = white, dark gray, light gray, black (brightness indices 3, 1, 2, 0).
    assert pack_xth(bytes([3, 1, 2, 0] * 8), 4, 8).hex() == "ffff0000ff00ff00"
    assert pack_xtg(bytes([0, 1, 0, 1, 0, 1, 0, 1]), 8, 1).hex() == "55"
    pages = [fixture_page(*p) for p in FIXTURE_PAGES]
    print("spec version", SPEC_VERSION)
    g0 = to_gray(*pages[0])
    print("crop p0", autocrop(g0, 300, 420))
    for name, opts in FIXTURES.items():
        data = export(pages, opts, title="Fixture")
        print(name, len(data), hashlib.sha256(data).hexdigest())
    for name, opts in EPUB_FIXTURES.items():
        screens, toc = epub_screens(pages, opts)
        print(name, len(screens), "screens", hashlib.sha256(b"".join(screens)).hexdigest(), "toc", toc)
    print("epub id", epub_identifier("Fixture", "", 11))


if __name__ == "__main__":
    main()
