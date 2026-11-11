# Rayl branding

`rails.svg` is the default Rayl app icon: four blue rays converging at one point.
`ray.svg` retains the alternate R concept. Both are editable, unmasked 1024-unit
vector sources supplied with the September 2026 branding pack. Named groups
separate the background, symbol, and lighting; gradients remain editable.

## Platform assets

- iOS: `../iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png` is an
  opaque 1024 × 1024 RGB PNG. iOS supplies the rounded mask and automatic dark
  and tinted appearances.
- Android: `../androidApp/src/main/res/mipmap-*` contains lossless adaptive
  foreground/background layers and legacy launcher icons. The adaptive artwork
  maps to the central 72dp of the 108dp layer; its convergence point remains in
  the 66dp safe circle. The decorative rays extend beyond the mask for launcher
  motion. A separate vector of the four planes supports themed icons.
- Google Play: `../../distribution/store-listing/icon.png` is the opaque,
  unmasked 512 × 512 icon export, and
  `../../distribution/store-listing/feature-graphic.png` is the opaque
  1024 × 500 feature graphic export.

The rounded presentation exports, duplicate foreground SVGs, comparison sheet,
and standalone HTML preview from the delivery pack are omitted. The two vector
sources retain the editable artwork; installed platform assets are generated
from `rails.svg`.

## Regenerating icons

Run `export-icons.py` with Python, CairoSVG 2.9.1, Pillow 12.3.0, and native Cairo
installed (on macOS, Cairo is available through Homebrew). These are asset
authoring tools, not application dependencies. For example, from this directory:

```sh
python3 -m venv /tmp/rayl-icons-venv
/tmp/rayl-icons-venv/bin/pip install CairoSVG==2.9.1 Pillow==12.3.0
/tmp/rayl-icons-venv/bin/python export-icons.py
```

On macOS, if Python cannot locate Cairo, set
`DYLD_FALLBACK_LIBRARY_PATH="$(brew --prefix)/lib"` for the export command.
The exporter is specific to the four triangular rails and their existing SVG
layer structure. Revisit its layer separation and safe-zone placement when
changing that geometry. Inspect small sizes and circular launcher crops after
exporting.

Platform references: [Apple app icons](https://developer.apple.com/design/human-interface-guidelines/app-icons)
and [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

## Other wallets' icons

"Use another wallet" shows each external wallet's own mark, 176 px PNGs trimmed from the
publishers' assets: Blink's launcher icon (`blinkbitcoin/blink-mobile`), the NWC logo
(docs.nwc.dev), and Spark's site icon (docs.spark.money). They live in
`../shared/src/androidMain/res/drawable-xxxhdpi/wallet_<id>.png` and
`../iosApp/iosApp/Assets.xcassets/wallet_<id>.imageset`; a wallet without one shows its initial.
