# AirPlay on the TV

The TV receives AirPlay with [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server)
(GPL-3.0, built on [UxPlay](https://github.com/FDH2/UxPlay)), installed by `tools/apps.sh` and updated
from its GitHub releases. It mirrors screens (H.264 and H.265), plays AirPlay video (HLS) and audio
(AAC, ALAC), and starts at boot. `setup.sh` lets it draw over other apps: without that, Android keeps
its screen from coming forward when a sender connects, and nothing shows. Writing our own receiver
would mean redoing that protocol work (FairPlay handshake, RAOP, HLS proxy) for the same result: this
folder configures that one for this TV instead.

```bash
airplay/setup.sh            # 4K, H.265, 60 fps, low latency
airplay/setup.sh "Salon TV" # same, and the name senders see
airplay/setup.sh --show     # every setting
```

## What this TV can take

- Android draws its menus at 1920×1080 but the panel and the video plane are 4K, and the MediaTek HEVC
  decoder handles up to 4096×2304 at 120 fps. With the receiver's resolution left on `auto` it asks
  senders for 1080p; `setup.sh` asks for 3840×2160 in H.265 instead.
- The sender decides within that box. A Mac can drive a 4K virtual display (choose the TV as a display,
  not mirroring at the Mac's own size). An iPhone sends its own screen, at most its native resolution
  (about 2.6K on recent models), in H.265; older devices without H.265 encoding stay at 1080p H.264.
- Latency is that of AirPlay mirroring, a tenth to two tenths of a second: right for photos, films and
  presentations, not for fast games. For games from the Mac, Moonlight (installed) with Sunshine on the
  Mac streams at 4K/60 with a few tens of milliseconds over a wired network. The TV's "Game" picture
  style removes most of the panel's own processing delay.
- The decoder declares no low-latency mode; `low_latency` is set for decoders that honour it and costs
  nothing here.
- DRM-protected video (the Apple TV app) does not play over a third-party receiver.
