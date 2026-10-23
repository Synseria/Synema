# Synéma

Android TV launcher + debloat tools for a Philips 65OLED806 (Android TV 11, 32-bit, 1920×1080 at 320 dpi,
so 960×540 dp), driven by Home Assistant. Born from a fork of Gathin23/cleanyourtv (MIT), on its own
since v3.0.0: nothing is taken from it any more.

## Map
- `launcher/` — the APK (Java, no Kotlin). `MainActivity` = dashboard (+ `HouseHeader` top line, `Dock`
  favourites, `CardRow` rows in `ColumnScroll`, `HousePanel` (its rooms in `TileScroll`), `CameraRow`, `Backdrop`/`MovingBackdrop`);
  `AppsActivity`; `SidePage` (a page beside the dashboard): `RoomPage` (a room; rooms read by `Rooms`),
  `EnergyPage` (the electricity) and `SourcesPage` (Live TV, HDMI, AirPlay, then
  the TV's drawer; also the remote's Source button, `keys` in home.json); `Chart` (graphs, from `Stats`); `CameraActivity`
  (`CameraStream` MJPEG, then `CameraRtc` WebRTC via HA's Frigate proxy); `AssistActivity`; `ClockDream`
  (screensaver); `RemoteKeyService` = accessibility service (remote buttons, Home with its animation,
  `Alerts` overlay + HA events, `TvState` → sensor.synema); `KeyLayouts` (Assistant button overlay);
  `TvListings` (what apps publish, completed by `Tmdb`) and `Launch` (play a title by name, open an app
  out of its card); `HomeAssistant` (REST), `HaSocket` (WebSocket), `HaLive` (states pushed as they
  change, for the dashboard and the screensaver), `History` (sensors' past: the readings' trends in
  `Place`), `Stats` (long-term statistics, for the graphs); `Secrets`; `EditMenu`; `Favourites` (apps
  and HDMI inputs, their banners).
- `home.local.json` (repo root, git-ignored) — the house: every Home Assistant entity, tile, alert, camera
  and remote button, pushed by `tools/home.sh`; `launcher/app/src/main/res/raw/home.json` is the public
  example built into the APK, never a real house. Reference: `docs/dashboard.md`. Favourites are `dock_apps` in `res/values/config.xml`.
- `airplay/` — the AirPlay receiver's settings for this 4K TV (`setup.sh`) and what it can take.
- `examples/philips-65oled806/debloat.txt` — what is disabled on this TV and the firmware's quirks.
- `tools/` — `ha-entities.sh <word>` (find entity ids), `home.sh` (apply home.local.json without rebuild),
  `assistant-key.sh`, `secrets.sh` (push the keys), `remote-keys.sh`, `apps.sh`, `screensaver.sh`, `theme.sh`,
  `disable.sh`. `sh run.sh --install [--start]` builds and installs.

## Rules
- `README.md` and `README.fr.md` say the same thing, section for section: a change to one goes to both.
- The repo is public: nothing about the user's home (entity ids, people, places, addresses) goes in a
  tracked file. Secrets live only in `secrets.local.json` (git-ignored); never print the token or put it
  in a command line.
- The TV is the single test device (its address is `tv_host` in `secrets.local.json`, read by
  `tools/tv-host.sh`; adb over 5555). Ask before driving it if the
  user may be watching. Verify UI changes with `adb exec-out screencap` and performance with `dumpsys
  gfxinfo com.synseria.synema framestats` (target: no janky frames while navigating). On this GPU,
  `clipToOutline`, elevation shadows and any full-screen picture or fade in the window drop frames: round
  with `RoundedImageView`, never animate a full-screen view. Every full-screen window of the launcher sits
  over a GL surface that paints its background (`MovingBackdrop.under`; the dashboard's shows `Backdrop`'s
  pictures), drawing only while it fades or drifts, and the window paints none: on this panel a window alone
  is composed by the display's overlay, whose buffers come back late (dequeueBuffer 11–17 ms, a third of the
  frames missed), while a window over a surface is composed by the GPU, smoothly. Text is Roboto (theme
  `TvText`); Outfit only for the big clocks and the big temperatures (top line, room page).
- Focus has one look, from `Focus` only (cards: ring and sheen, tiles: veil and ring, buttons: white
  disc). Every container between a focusable and the room left for its lift keeps `clipChildren` and
  `clipToPadding` off; only a row's window clips, across. A ScrollView's default `clipChildren` cuts its
  content to the content's own bounds, so the side lift goes: tiles that scroll between neighbours sit in
  `TileScroll`, which clips along its axis only.
- Install with `sh run.sh --install [--start]` (re-enables the remote-buttons service, which Android drops on
  reinstall, and the Assistant button overlay, and pushes home.local.json); `sh run.sh` alone is a debug build.
- Never press OK on the dashboard blind while testing: the panel's tiles switch real lights. Check the
  focus first (`uiautomator dump`) or test away from the panel.
- Never trigger the user's lights or devices to test; fake states on a throwaway entity
  (`POST /api/states/sensor.synema_test`, then `DELETE`).
