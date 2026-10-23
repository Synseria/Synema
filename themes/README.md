# Themes

A theme is a folder with a `config.json` and, optionally, a `wallpaper.jpg`. Pushing one changes the
launcher's favourites and look on the TV without rebuilding the APK: the launcher reads
`config.json` as an overlay over its built-in `config.xml` the next time it starts.

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/aurora          # push a theme and restart Home
TV_HOST=192.168.1.50 tools/theme.sh --reset                # back to the built-in config.xml
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost 192.168.1.50 themes\aurora
```

| Theme | What it does |
|---|---|
| `default/` | The built-in look written out as JSON, as a starting point for your own |
| `aurora/` | A dark aurora picture (teal and violet on navy, darker at the bottom) |

## Keys

Every key is optional; a missing key keeps the value from `config.xml`, an unknown or wrongly typed key
is ignored, and a file that doesn't parse is ignored as a whole, so a theme can never break Home.
The dashboard's edit menus (hold OK on a favourite, or the pencil pill) write `dock_apps`,
`hidden_blocks` and `backdrop` into the same file on the TV, so pushing a theme replaces those edits.

| Key | Type | Meaning |
|---|---|---|
| `dock_apps` | list of package names | The favourites (the dashboard's first row), in order; missing apps are skipped |
| `hidden_apps` | list of package names | Left out of All apps |
| `hidden_blocks` | list of `resume`, `picks`, `house` | Dashboard sections not shown |
| `backdrop` | `artwork`, `picture`, or a colour theme: `night`, `aurora`, `dusk`, `ocean`, `graphite` | Behind the dashboard: the focused title's artwork blurred (default), `wallpaper.jpg`, or the theme's gradient. Also in the pencil menu ("Fond") |
| `backdrop_motion` | bool | The background, whichever it is, drifts slowly and smoothly |

## Wallpapers

With `"backdrop": "picture"`, `wallpaper.jpg` is drawn full screen behind the dashboard, stretched to
16:9, in place of the blurred artwork of the focused card. Keep it dark, 1920×1080 and under about 300 KB: the text is designed for
a dark background, and the picture is decoded on every Home start. Larger pictures are downsampled.

## Adding a theme

Open a PR with a new folder here, a one-line row in the table above and a screenshot from a real TV
in the PR description. Keep the dock list generic (the big streaming apps) unless the theme is for a
specific TV, and say which TV you tried it on.
