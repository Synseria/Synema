# Dashboard and Home Assistant

This fork's home screen is a dashboard rather than the tvOS dock: favourites on their plate, continue
watching, what the apps put forward, the cameras and the House panel, on a dark colour taken
from what you are watching; with alerts over any app, a house assistant, remote buttons that drive the house and OLED protection. Every app is in
**All apps**, a plain grid (hold OK on an app to favourite, hide or close it).

## Set up

```bash
cp secrets.local.json.example secrets.local.json   # the TV's address, HA's address and a long-lived token
cp launcher/app/src/main/res/raw/home.json home.local.json   # then make it your house (below)
sh run.sh --install                                  # builds, installs, pushes home.local.json
tools/secrets.sh                                     # pushes the tokens to the TV, never to git
tools/screensaver.sh 5                               # screensaver after 5 minutes
```

`secrets.local.json` and `home.local.json` are ignored by git. The scripts take the TV's address from
`TV_HOST`, else from `tv_host` in `secrets.local.json` (`tools/tv-host.sh`). `tools/secrets.sh` copies it to the launcher's private files
directory on the TV as `secrets.json`; the APK and the repo never contain the tokens (Home Assistant's, and TMDB's
below). A long-lived token is created in Home Assistant under your profile, **Security**, **Long-lived
access tokens**.

`sh run.sh --install` also turns on the accessibility service that reassigns remote buttons and shows
the alerts (`REMOTE_KEY_SERVICE=1` of `launcher/build-and-install.sh`), which Android switches off on
every reinstall.

## What it shows: `home.local.json`

Entity ids only, no secrets. `launcher/app/src/main/res/raw/home.json` is an example, built into the
APK; your house lives in `home.local.json` at the repo root, ignored by git, which the TV reads instead
(the whole file replaces the example). To change it: find ids with `tools/ha-entities.sh <word>`, edit
`home.local.json`, apply it with `tools/home.sh` (no rebuild; `sh run.sh --install` pushes it too).

| Key | What it does |
|---|---|
| `weather` | The sky (a `weather.*` entity's condition), in the top line's outside column and the screensaver |
| `outside` | The top line's outside column: `label`, `entity` (temperature) and optional `humidity` and `pressure`; the pressure carries its three-hour trend (below) |
| `temperatures` | Rooms, the same shape plus optional `co2` and `noise`, `label` being the Home Assistant area's name. The first is the TV's room: its column on the top line, the CO₂ with a dot (green under 800 ppm, orange under 1200, red beyond: then a capsule too). An entry's sensors give its room's readings in the House panel and on its page, over the area's own: its reference temperature and humidity sensors (the area's settings in Home Assistant), else its first sensor of each kind (which can be a fridge thermometer in the kitchen). The screensaver lists them all: "Salon 23° / 57 %" |
| `house_label` | The rooms, in the House panel and on their pages: every Home Assistant area of a floor (of any while there is no floor) with things to switch (lights, covers, fans, a vacuum; switches too when labelled) carrying this label; with none labelled, all of them |
| `energy` | `power` (live W or VA) and its `limit` (a capsule above it), `subscribed` (the contract, kVA: the panel's bar), `subscription` (its price a month, €: added to what the Energy dashboard counts since the month began, each hour at its price, for the month's cost), `tempo` and `tempo_tomorrow` (EDF Tempo colours: dots in the panel, a capsule on a red day) |
| `status` | Anything else out of the ordinary, configured nowhere else: a capsule on the top line (and a line in the screensaver) only while its conditions hold (below) |
| `cameras` | `entity`, `label`, optional `stream` (the go2rtc stream for the picture, default the entity's name; Frigate's `_sub` ones hold best) and optional `sound` (the one for the sound both ways, default the entity's name: the main stream, which has the sound and the speaker's back channel) each, a row of their own on the left; OK opens one full screen, Left/Right to switch. Full screen shows Home Assistant's pictures at once, then live video and sound over WebRTC through the Frigate integration's proxy (no Frigate credentials on the TV), ~0.5 s behind. The remote's mic button talks through the camera (pressed: until pressed again; held: while held), as does OK on the talk button |
| `tiles` | The House panel's tiles, two per line, before the rooms: a name and an action; the entity's state shows under the name, with the dot of the colour button that toggles it |
| `alerts` | Shown over any app when an entity changes: `entity`, `to`, optional `from`, `title`, optional `camera` (live, top right, Enlarge/Close), `pause` (pauses what is playing) and `reply` (a `label` plus an action, e.g. an announcement) |
| `assistant` | `language`, `suggestions`, and `presets`: each a `name`, a conversation `agent` and an optional `context` sent ahead of a conversation's first sentence (`{app}`: what the TV shows, `{time}`, `{resume}`: the titles to continue); the assistant offers them as chips. "Mets / lance / regarde …" plays the title itself before asking Home Assistant. The conversation reads as messages; the house's things (`house_label`'s) and scenes go with its first sentence, and the agent may end an answer with up to three actions, shown as cards: OK on one calls Home Assistant directly (only those things, with a service that suits them) |
| `keys` | Remote buttons by keycode: an action each (an app, `assistant` or `sources`: the Sources page) |
| `keys_off_in` | Apps in front of which the Home Assistant buttons are left alone (teletext uses the colours) |
| `dismiss` | Screens (activity class names) sent away as soon as they show, Home coming back over them: Philips' terms prompt for its FAST channels, which comes back and cannot be closed |

### The top line

The clock, the date, then outside and the TV's room: the temperature large, the other readings beside
it. The pressure's arrow compares it with three hours before, read from Home Assistant's history every
15 minutes at most: → steady (under 1 hPa either way), ↗ rising, ↘ falling, an orange ↘ for a fall of
3 hPa or more (worse weather on the way). Then the capsules, empty when all is calm, problems first;
and the buttons: Assistant, Applications, Sources, Settings, House. Down from a button goes to the
first favourite, Up from the favourites to the first button.

Sources opens a page beside the dashboard, as a room does: the TV (Philips' Live TV app), each HDMI
port under the name of the device on it (the same card as a favourite input), the AirPlay receiver,
then "All sources…", the TV's own drawer. OK switches to it; Back returns to the button. The remote's
Source button (178, `keys`) brings the same page from any app, at once; it goes when another app takes
the screen, so Home shows the dashboard again.

A capsule comes from the measure it is about, never from a second copy of its entity: the CO₂ in its
red band, the power above `energy.limit`, a red Tempo day today or tomorrow. Anything else is a `status`
entry, shown while every condition it sets holds (none: always):

| Key | Shows |
|---|---|
| `show` | in one of these states |
| `for` | once the state has held for so many seconds |
| `within` | for so many seconds after the state changed |

Its words are `label` (by default the entity's name in Home Assistant) then the state through `states`
(a state left out reads as Home Assistant has it). `alert` (a list of states, or `true` for all) makes
it red, a problem; otherwise it is violet, an activity. For instance, the front door left open two
minutes, and someone coming home:

```json
{"entity": "binary_sensor.front_door", "label": "Front door", "states": {"on": "open"},
 "show": ["on"], "for": 120, "alert": true}
{"entity": "person.resident", "states": {"home": "is home"}, "show": ["home"], "within": 600}
```

An **action** is one of `{"app": "<package>"}`, `{"assistant": true}` or
`{"service": "light/toggle", "entity": "light.salon"}`.

An action with `data` passes extra service fields: `{"service": "notify/alexa_media_salon_echo_dot",
"data": {"message": "J'arrive", "data": {"type": "announce"}}}`.

Favourites stay in `config.xml` (`dock_apps`), with the launcher's other settings. Holding **OK** on a
favourite: move, remove, close the app (ends its background processes), add an app, or customise the
home screen: add a favourite, show or hide continue watching, À découvrir and the Home Assistant panel, pick
the background, applied at once.
Those edits go to the `config.json` overlay on the TV, the same file a theme writes (see
`themes/README.md`).

## Remote buttons

```bash
tools/remote-keys.sh learn 60   # press buttons, read their keycodes
tools/remote-keys.sh off        # every button back to normal
```

On the Philips 2021/22 remote: App Gallery 326, Rakuten 779, red/green/yellow/blue 183 to 186, Source 178. Branded
buttons are taken from the TV's own handler because the accessibility service sees keys first.

The Assistant button is answered by the system before any app sees it. `tools/assistant-key.sh on`
gives every remote the launcher's key layout overlay (`KeyLayouts`, `res/raw/assistant_key.kcm`): the
button becomes F12, and `keys` maps 142 to the house assistant, so Google's never starts. A Bluetooth
remote is only listed while awake: press a key on it just before, and run it again after pairing a new
one. `off` gives the button back to Google. If a remote's button is not covered, `remote-keys.sh learn`
shows its raw scancode for the overlay.

## The House panel

On the right, as tall as the column: "Maison" and how many rooms have a light on, the `tiles`, a rule,
then the rooms two by two (`house_label`): the name, after a small yellow dot while a light of the
room is on, then the temperature large and, stacked beside it as on the top line, the humidity, the
CO₂ (dot and value) and the noise, those the room has. A room is lit when one of the lights of its page is
on: rooms, not bulbs, as a lamp of three bulbs or a Hue group would count several times. Each reading
(temperature, humidity, CO₂, noise) comes from the room's entry in `temperatures` when that entry sets
it, else from the area's reference temperature or humidity sensor in Home Assistant, else from the
room's first sensor of that kind. At the bottom, the electricity on two lines: the power against the
subscription (and its bar) with the month's cost (the Energy dashboard's, each hour at its price, and
`energy.subscription`), then Tempo today and tomorrow. Eight rooms and six tiles fit; the tiles stay
put, and in a larger house the rooms alone scroll between them and the electricity. Down stops at the last line, Left goes back to the column.

OK on a room opens its page: the home is pushed out to the left as the room comes in from the right,
edge to edge; Back pushes it back, onto the room's tile. The page shows the room's name, its readings,
its lights, its other things and the cameras of `cameras` placed in that area in Home Assistant; OK on
a tile switches it, as on the panel. At the bottom, its graphs side by side, one card per kind it
reads: temperature and humidity together (each on its own scale), CO₂, noise. Down from the tiles
reaches the spans, 6 h, 24 h, 7 j or 30 j (the last chosen is kept for every room); the curves are
Home Assistant's long-term statistics (per 5 minutes, hour or day: a sensor needs a `state_class`),
gathered into a few dozen periods (15 or 30 minutes, 3 hours, a day) drawn as a smooth curve that
never goes past what was read, each period's min–max a faint band round it. The graphs take the
height the tiles leave.

OK on the electricity opens its page the same way: the power now, the month's cost and Tempo, then
what was used today so far, yesterday, this month and the last, and a graph per hour today, per day
over 30 days or per month over a year. The meters are those Home Assistant's Energy dashboard counts
from the grid, stacked in their Tempo colour (darker off-peak). A meter's jump the house cannot have
drawn (above the subscription over the period, off-peak counting 8 hours a day) is left out: a meter
that came back from zero or doubled for a moment shows in Home Assistant's own statistics as a day of
hundreds of kWh.

Each side moves as one picture (a hardware layer) over the background, which stays put (a moving
background holds still); they never overlap, fade or shrink, so each frame covers the screen once.
The focus moves, and its lift ends, before the slide: a lift under way would redraw a whole side every
frame. Home Assistant's answers that arrive meanwhile are drawn after.

## The rows

Apps publish to the TV provider; the launcher reads what is marked searchable with `READ_TV_LISTINGS`,
which `build-and-install.sh` grants over adb.

- **Continue watching** is the Watch Next rows, up to fifteen, each app's newest first so one app
  cannot fill the row (Canal+, Prime Video and Plex publish them here). Netflix publishes no Watch Next
  but puts the titles you are watching in its channel, marked "Reprendre la lecture" on the artwork.
- **À découvrir** is what every app puts forward, its first channel (the row the stock home showed),
  interleaved: each app's first title before any app's second, up to fifteen. On a favourite, the band
  above the rows shows that app's first one.

The favourites sit on a plate as wide as the rows and never scroll. The rows fill the width: as many
cards as fit at 118 dp or more, stretched to fill it, 16:9. Beyond them, Right slides the row by one
card and the picture at the edge fades out to say there are more; past the last card, Right reaches the
House panel. Up and Down always go to the next row, onto its card in view nearest across, however far.
The column scrolls under the featured title by whole sections: while the focused row is in full view
nothing moves, otherwise it comes to the top with its title, and nothing of the row before shows above
it. The band follows the focus once it rests (200 ms), the background a moment later.

## When things are read

- **States**: pushed by Home Assistant as they change, over a WebSocket (`HaLive`, `subscribe_entities`)
  held while the dashboard or the screensaver is in front: a light switched anywhere shows within a
  moment, nothing polls. While Home Assistant does not answer (it reboots), a red capsule says so, the
  last states stay on screen, and the connection is tried again by itself (every 15 s at most).
- **Rooms** (which areas, which things, which sensors): on every return to Home, then every 10 minutes.
- **The month's cost**: the Energy dashboard's statistics, every quarter of an hour.
- **Trends**: Home Assistant's history, every quarter of an hour (History). **Graphs** (a room's, the
  electricity's, the screensaver's curves): its long-term statistics when they show, kept 5 minutes.
- **Alerts**: pushed over a WebSocket as they happen; it reconnects by itself.
- **Rows**: on every return to Home. **TMDB**: kept a week. **Cameras' stills**: every 30 s.

## TMDB

With `tmdb_token` in `secrets.local.json` (a TMDB API read access token, themoviedb.org > Settings > API;
optional), the band completes a title from TMDB where its app says nothing: the synopsis, an episode's
title and synopsis when the app gives its season and episode, the genre and the year, and a large
picture for the background. What the app publishes always wins. A title is matched on its exact name,
as a film or a series when the app says which, on its year when the app gives one, else the most
popular; no sure match, nothing. At the band's top right, the audience's rating as a small capsule
(TMDB's average, from 50 votes up). Only the title the band shows is looked up, off the main thread, a
few requests a second at most, and kept a week on the TV, misses included. The token goes in a request
header, never in an address or a log. The credit TMDB asks for is at the bottom of Customise the home screen.

"reste 23 min" comes from the app's length and how far in the title is, when that length is
believable: not under 5 minutes for a film, nor more than twice off TMDB's (Canal+ gives some at the
wrong scale, which leaves the progress bar right but the minutes wrong). Otherwise it comes from TMDB's
length, and without TMDB nothing is said (TvListings.Left).

## Alerts

`RemoteKeyService` keeps one WebSocket open to Home Assistant (`HaSocket`) and subscribes to a state
trigger per alert, so nothing polls. A camera alert shows the camera live (Home Assistant's MJPEG
stream, `CameraStream`) in the top-right corner, pauses the playing app and offers Enlarge, the optional
Reply and Close; Back or Close resumes. The same alert is not repeated within two minutes.

## OLED

The screensaver (`tools/screensaver.sh 5`) glides a dim clock and the house status by one pixel every
two seconds. The dashboard itself moves by a pixel or two every minute.

## Background

By default (Customise the home screen, Background: Artwork), the focused title's picture fills the
screen behind the dashboard, sharp and centred: TMDB's large picture when there is one, else the app's
artwork. It is shaded towards the base colour where the words and the rows sit (left, bottom, top, a
touch at the right), the shading painted into the picture once, composed off the main thread at
960×540 (`Backdrop.SCENE_W`, the one figure to lower if the background costs frames). It is drawn by
a GL surface under the window (MovingBackdrop), in every mode, which fades from one title to the next
in its shader once the focus rests, and draws nothing in between unless the background is set to move.
On this TV a window alone is composed by the display's overlay, whose buffers come back late (a third
of the frames missed); over a surface, the GPU composes it smoothly. All apps sits over the same kind of
surface, painting its plain colour; the camera's video has its own. The colour themes, the moving
background and a picture of your own stay in the menu.

## Assistant

The Assistant button (or a remote button set to `{"assistant": true}`) sends a sentence to Home Assistant's
conversation API with the agent from `home.json`. It listens at once through the TV's speech recognition
(Google's, on this TV) when the remote's microphone works, and otherwise takes typed text or a quick
prompt. Answers are shown and read aloud.

## Apps from GitHub

`tools/apps.sh` installs and updates the apps in `tools/apps.txt` from their latest GitHub release, and
opens the Play Store page of the others. Run it again to update.

## Notifications from Home Assistant

Any automation can put a message on the TV, over whatever is playing, by firing the event
`synema_notify` (Developer tools > Events to try it):

```yaml
action:
  - event: synema_notify
    event_data:
      title: "Lave-linge terminé"
      message: "Le cycle coton est fini"   # optional
      camera: camera.doorbell             # optional: shown live, with Enlarge and Close
      pause: true                         # optional, with a camera: pauses what is playing
```

Without a camera it is a small notice bottom left for a few seconds that never takes the remote.

## Cinema mode

Lights that follow what the TV plays are a Home Assistant automation, not a launcher setting: trigger
on the TV's media player (Android TV Remote or Philips integration) changing to `playing`, with a
condition on its `app_id`/`source` (Netflix, Plex…), and turn on a scene (`scene.salon_attenue`);
a second automation on `paused`/`idle` turns the room back on.

## Playing from Home Assistant

The TV reports what it shows as `sensor.synema` (the app in front, its package, and `a_reprendre`, the
titles to continue), and plays what Home Assistant asks with the event `synema_play` (`title`,
optional `app`): it looks the title up in what the apps published (continue watching, their rows) and
opens it, or opens the app. The assistant does the same by itself for "mets / lance / regarde …".

To let an LLM conversation agent start a film, give it this script as a tool (expose it to Assist):

```yaml
script:
  tv_lancer:
    alias: Lancer sur la TV
    description: >-
      Lance un film, une série ou une appli sur la TV du salon. Les titres en cours sont dans
      l'attribut a_reprendre de sensor.synema.
    fields:
      titre:
        description: Titre du film ou de la série, ou nom de l'appli
        example: L'amour est dans le pré
        required: true
        selector: {text: {}}
      appli:
        description: Appli (Netflix, Canal+, Prime Video…), facultatif
        selector: {text: {}}
    sequence:
      - event: synema_play
        event_data:
          title: "{{ titre }}"
          app: "{{ appli | default('') }}"
```
