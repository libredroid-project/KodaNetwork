# Branding assets

`kodadash_icon_source.png` is the master file for the **KodaDash and website** mark.
`kodadash_icon.png` is the square crop used for generation.

**The Android app has its own, unchanged launcher icon.** These assets are not used for it.

| Asset | Path |
| --- | --- |
| KodaDash plugin icon | `KodaDash/src/main/resources/icon.png` |
| Website logo + favicon | `../website/assets/logo.png` |
| Admin panel logo | `../admin_panel/public/logo.png` |
| Lobby / transfer plugins | use the app icon (unchanged) |

Regenerate with `python3 tools/make_brand_icons.py` after replacing the source image.
