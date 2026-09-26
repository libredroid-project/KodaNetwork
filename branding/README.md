# Branding assets

`app_icon_source.png` is the master file the launcher and plugin icons are derived from.
`app_icon.png` is the square crop used for generation.

Where the icons live:

| Asset | Path |
| --- | --- |
| Android launcher (legacy, per density) | `app/src/main/res/mipmap-*/ic_launcher.png` |
| Android launcher (round) | `app/src/main/res/mipmap-*/ic_launcher_round.png` |
| Adaptive background (full bleed) | `app/src/main/res/mipmap-*/ic_launcher_bg.png` |
| Adaptive foreground | `app/src/main/res/drawable/ic_launcher_foreground_empty.xml` |
| Themed icons (Android 13+) | `app/src/main/res/mipmap-*/ic_launcher_monochrome.png` |
| KodaDash plugin icon | `KodaDash/src/main/resources/icon.png` |
| Lobby plugin icon | `KodaLobbyPlugin/src/main/resources/icon.png` |
| Prebuilt transfer jars | patched in place in `app/src/main/assets/koda_transfer.jar` and `transfer_plugin.jar` |

Regenerate with the helper in `tools/make_app_icons.py` after replacing the source image.
