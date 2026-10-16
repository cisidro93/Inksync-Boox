# InkSync Boox · Note Air 4C Edition

A purpose-built, ultra-responsive E-Ink study workspace, unified Kindle-style EPUB reflow reader, and NeoReader-grade PDF engine crafted specifically for the **Onyx Boox Note Air 4C** (10.3" Kaleido 3 Color E-Ink + Wacom EMR Stylus).

## Core Architectural Pillars

1. **Unified "One Library" Sanctuary (EPUB + PDF + Manga + Notes)**:
   - Never choose between the Kindle app (for novels/EPUBs) and NeoReader (for technical PDFs).
   - **Kindle-Grade Reflow Engine** with zero-guilt Momentum Analytics (tracks reading flow without punitive daily streak anxiety).
   - **NeoFlow Smart Tiers ($M \times N$ Matrix Engine)** for 2-column academic papers, textbooks, and comics (`1×2`, `2×1`, `2×2`, `2×3`, `3×3`) with `Z-Flow`, `Reverse-Z`, `N-Flow`, and `Reverse-N` traversal and 8% Connection Redundancy.
2. **Kaleido 3 Hardware Color & 300 PPI Contrast Engine**:
   - Pure `#000000` on `#FFFFFF` monochrome layer for 300 PPI typography sharpness.
   - High-luminance pastel CFA highlighter swatches (`Kaleido Amber`, `Kaleido Mint`, `Kaleido Cyan`, `Kaleido Coral`) rendered with `BlendMode.Multiply` so color highlights never muddy black text.
   - Zero-animation instant transitions and disabled overscroll glow to eliminate A2 refresh ghosting.
3. **Onyx EPD & Wacom EMR Hardware Bridge**:
   - Direct reflection hooks into `com.onyx.android.sdk.api.device.epd.EpdController` (`Regal HD`, `Speed Mode`, `A2 Ultra`) and `TouchHelper` for sub-millisecond Wacom stylus inking.
   - Full support for hardware page-turn buttons (`Volume Up/Down`, `Page Up/Down`, Bluetooth remotes).
