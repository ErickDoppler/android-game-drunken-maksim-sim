# Drunken Maksim Sim

An Android chat-moderator game for a **BLE LED matrix panel**. Chat user names
drift DVD-logo-style across a 96×16 (or bigger) iPixel panel; you hold the
phone like a TV remote, aim a gyroscope-driven crosshair at a name, and hit
**BAN** or **MUTE** on the phone screen. Ban the right people, mute the right
people, and do not — under any circumstances — touch an admin.

No panel? The game also runs entirely on the phone screen (**PLAY ANYWAYS**).

---

## How to run it

You need nothing installed beforehand — no JDK, no Android Studio, no SDK.
Step 2 downloads all of it into the project folder.

### Windows

**1. Get the code**

```bash
git clone https://github.com/ErickDoppler/android-game-drunken-maksim-sim.git
cd android-game-drunken-maksim-sim
```

**2. Download the build tools** (~700 MB, one time)

```bash
download-tools.cmd
```

**3. Build the APK**

```bash
build.cmd
```

**4. Install it on the phone**

```bash
build.cmd --install
```

### Linux / macOS / WSL / Git Bash

**1. Get the code**

```bash
git clone https://github.com/ErickDoppler/android-game-drunken-maksim-sim.git
cd android-game-drunken-maksim-sim
```

**2. Download the build tools** (~700 MB, one time)

```bash
./download-tools.sh
```

**3. Build the APK**

```bash
./build.sh
```

**4. Install it on the phone**

```bash
./build.sh --install
```

That is it. The APK is at **`dist/drunken-maksim-sim-debug.apk`** and the app
is on the phone.

A few notes on step 4:

* The phone needs **USB debugging** on (Settings → Developer options) and has
  to be plugged in. Accept the "Allow USB debugging?" prompt on its screen.
* No cable? Skip `--install`, copy `dist/drunken-maksim-sim-debug.apk` to the
  phone and tap it. Android will ask you to allow installs from that app.
* Steps 2 and 3 are separate on purpose: you only ever run step 2 once, then
  rebuild with step 3 as often as you like.

Then open the app and read [**How to play**](#how-to-play) below.

---

| | |
|---|---|
| **Language** | Kotlin (no Compose, everything is a custom `View`) |
| **Build** | Gradle 8.11.1 Kotlin DSL + Android Gradle Plugin 8.7.3 |
| **Min / target SDK** | 26 (Android 8.0) / 35 (Android 15) |
| **Output** | `com.example.drunkenmaksim` — a single ~2.5 MB APK, no runtime deps beyond AndroidX core |

---

## The tool scripts

### `download-tools.cmd` / `download-tools.sh`

Everything the build needs is fetched into a project-local `tools/` directory —
nothing is installed system-wide and nothing outside the project is touched:

| Path | What |
|---|---|
| `tools/jdk` | Eclipse Temurin **JDK 17** |
| `tools/android-sdk/cmdline-tools/latest` | Android command-line tools (build 13114758) |
| `tools/android-sdk/platforms/android-35` | Compile SDK |
| `tools/android-sdk/build-tools/35.0.0` | aapt2, d8, apksigner, zipalign |
| `tools/android-sdk/platform-tools` | `adb` |

It also accepts the Android SDK licences, writes `local.properties` pointing at
the downloaded SDK, and warms the Gradle wrapper (Gradle 8.11.1 into
`~/.gradle`). Expect roughly **700 MB** of download on a cold run.

```
download-tools.cmd [--force]      # --force re-downloads what is already there
./download-tools.sh [--force]
```

Requirements: PowerShell 5+ on Windows; `curl` or `wget` plus `tar` on
Linux/macOS. Nothing else — not even a pre-existing JDK.

> **Why JDK 17 and not something newer?** Gradle 8.11 refuses to start its
> daemon on JDK 24+, and Android Studio's bundled JBR is already too new.
> 17 is the version AGP 8.7 is built against, so it is the safe floor.

### `build.cmd` / `build.sh`

```
build.cmd [debug|release] [--install] [--clean]
./build.sh [debug|release] [--install] [--clean]
```

| | |
|---|---|
| `debug` (default) | Signed with the local debug key — installable straight away |
| `release` | **Unsigned**; run `apksigner` on it yourself before installing |
| `--install` | `adb install -r` onto the attached device |
| `--clean` | Clean build first |

The scripts resolve the toolchain in this order, so they work whether or not
you ran `download-tools`:

1. `./tools/jdk` and `./tools/android-sdk`
2. `$JAVA_HOME` and `$ANDROID_HOME` / `$ANDROID_SDK_ROOT`
3. the system `java` and whatever `local.properties` already says

`local.properties` is rewritten to match the SDK that was resolved. It is
git-ignored, as are `tools/` and `dist/`.

You can of course still use the wrapper directly:

```bash
./gradlew assembleDebug
```

— but then `JAVA_HOME` has to be a JDK 17–23 yourself.

---

## How to play

### Setting up

1. Launch the app. While no panel is connected the **settings** page opens by
   itself.
2. Set **IPIXEL DISPLAY** to `ENABLE`. Android 12+ asks for Bluetooth
   scan/connect permission the first time, and offers to switch Bluetooth on if
   it is off.
3. The panel's size is auto-detected. If detection fails, pick it by hand under
   **SIZE**: `96*16`, `96*32`, `128*16`, `128*24`, `128*32`. Only game-capable
   sizes are listed — the game needs height ≥ 16 and at least 96×16 pixels.
4. Once a panel qualifies, the intro rolls (`MAKSIM IS THE CHAT ADMIN. IT IS
   YOU ARE.` → `READY?` → `GO!`) and the run starts.

No panel, or you just want to try it? Choose **PLAY ANYWAYS** — the game runs
on the phone screen on a virtual 96-px-wide field.

**Hold Volume Down for 3 seconds** at any time to open settings (this pauses
the game). Long-press the header there to exit.

### Holding the phone

* **Panel mode** — portrait phone held *horizontally*, screen up, top edge
  pointed at the panel like a TV remote. Pan and tilt move the crosshair.
* **Local mode** — portrait, tilted back about 45°. The gain is retuned so a
  full vertical sweep is a comfortable wrist tilt.

Tap the empty area of the screen to re-center the crosshair (or to restart
after a game over).

### The rules

You get **60 seconds** of credit and a chat that keeps up to four names on the
panel at a time. Each name has a colour, and the colour is the whole game:

| Colour | Who | BAN | MUTE |
|---|---|---|---|
| **White** | ordinary users | **−100** (20 % chance they return as grey) | **−100**, turns them yellow |
| **Grey** | sad newbies | 0 | **−50** (5th mute of the same one turns it red) |
| **Red** | aggressive | **+100** | **+10**, but each mute shortens the next mute (20→15→10→5→never) |
| **Dark red** | the specials | **+200** | — |
| **Yellow** | currently muted | reverts to its old colour when the timer runs out | |
| **Green** | **admins** | **−10000 and instant game over** | **−10000 and instant game over** |

* **Blinking whites** — at least once every 40 s a white starts blinking. Mute
  it inside 20 s for **+50**; miss it and it goes red or grey, 50-50.
* **Trolls** — a smile appears behind a white name. Not muted within 5 s and
  the whole chat cascades to red, an admin drops in for 5 s and storms off
  leaving a red `NU VAS NAHER` behind.
* **Specials** — `oLeg`, `Kozlenko`, `Konrad`, `neDima`, `LeoLeo` spawn dark
  red regardless of the four-word cap and keep coming back. Leave one for 15 s
  and the chat cascades around him; leave him 40 s and the run is over. They
  also **flood the chat** with red spam over the cap, and *the spam stays after
  the boss is banned* — cleaning up is your job, and each line escalates on its
  own red timer:

  | Boss | Spam | Every |
  |---|---|---|
  | `oLeg` | BABKI · BABKI · GDE · SUKA · BABKI | 2 s |
  | `Kozlenko` | LINUX | 2 s |
  | `Konrad` | DOOM · PENTIUM | 2 s |
  | `LeoLeo` | BLAH-BLAH | 2 s |
  | `neDima` | SERVICE · ZAVTRA · POTOM · REMONT | 3 s |

* **Reds are a clock.** An unbanned red goes dark red after 20 s and ends the
  run 5 s after that.
* **Pickups** — hover the beer icon for **+30 s and +5 % word speed**; hover
  the red pause icon to freeze all movement for 5 s.

### How it ends

| Ending | Trigger | Banner |
|---|---|---|
| **GOOD** | panel emptied of every name, score positive | lime `WINNER WINNER HYENA DINNER` |
| **BAD** | panel emptied with a negative score, or a dark red ran out | red `GAME OVER! LOOSER!!` |
| **BAD** | panel emptied on a score of exactly zero | red `YOU ARE USELESS` |
| **BAD_ADMIN** | you banned or muted a green admin | red `ONE MISTAKE AND YOU ARE MISTAKEN!` |
| **NEUTRAL** | the clock ran out | orange `TIME IS UP! GAME OVER!` |

Every banner scrolls and blinks for 5 s, then the score appears in the ending's
colour.

---

## Hardware

Any **iPixel / LED_96*16**-class BLE matrix panel of at least 96×16 pixels.
The app talks to it over Bluetooth LE (`led/IPixelHub`) — the same driver used
by the F16 HUD project it was lifted from. Colours are streamed frame by frame
at ~30 fps; the phone screen shows a live pixel-for-pixel mirror of whatever
the panel is displaying, so you can see what is going on even with the panel
across the room.

---

## Project layout

```
app/src/main/java/com/example/drunkenmaksim/
├── MainActivity.kt        game loop (33 ms tick), gyro → crosshair, BLE flow,
│                          Volume-Down-hold → settings
├── GameState.kt           iPixel connection/settings state shared with the UI
├── game/
│   ├── GameEngine.kt      ALL the rules, timers and the panel renderer.
│   │                      The doc comment at the top of the class is the
│   │                      authoritative rules spec; rosters are in the
│   │                      companion object.
│   └── SoundFx.kt         glass break / whip / cap pop / ding, synthesized to
│                          WAV in the cache dir at startup, played via SoundPool
├── ui/
│   ├── GameView.kt        phone screen: pixel mirror, score/time, BAN + MUTE
│   └── SettingsView.kt    self-drawn monospace settings stripe
└── led/
    ├── IPixelHub.kt       BLE driver for iPixel panels
    └── LedPages.kt        pixel fonts (5×7 and a narrow 3×5) used by the renderer
```

`GameEngine.renderFrame(w, h)` is the single source of pixels: the LED hub and
the on-screen mirror both call it. The engine is coarsely `@Synchronized`.

**Tuning the game** happens in `GameEngine`'s companion object — name rosters,
`SPAM`, `MAX_WORDS`, `BASE_SPEED`, `START_TIME_MS`, the banner texts. Gyro
feel lives in `MainActivity` (`GYRO_GAIN`, `LOCAL_SWEEP_RAD`).

Preferences are stored in `SharedPreferences("drunkenmaksim_ui")`.

**Debug hook:** launch with `--es settings 1` to open the settings page
directly:

```bash
adb shell am start -n com.example.drunkenmaksim/.MainActivity --es settings 1
```

---

## Contributing

The `main` branch is protected: it only moves through a reviewed pull request.

1. Fork the repository (or, if you have push access, create a branch).
2. Commit on your own branch.
3. Open a pull request against `main`.
4. A maintainer reviews and merges it.

Please keep `led/IPixelHub.kt` in sync with the upstream F16 HUD copy — it is
shared verbatim between projects, package name aside.

---

## Troubleshooting

**`Unsupported class file major version` / the Gradle daemon will not start**
Your `java` is too new. Run `download-tools` and let `build.*` use
`tools/jdk`, or point `JAVA_HOME` at a JDK between 17 and 23.

**`SDK location not found`**
`local.properties` is missing or stale. Re-run `download-tools`, or set
`ANDROID_HOME` and re-run `build.*` — it rewrites the file.

**`Failed to install the following SDK components ... accept the licences`**
Re-run `download-tools`; it feeds `sdkmanager --licenses` automatically.

**`--clean` fails with "Unable to delete directory ... lint-cache" (Windows)**
A Gradle daemon from an earlier build still has the files open. Stop it and
retry:

```bash
gradlew.bat --stop
```

**The panel never connects**
Check Bluetooth is on and that the app has the scan/connect permission
(Android 12+) or location permission (Android 6–11 gate BLE scan results behind
it). Settings → IPIXEL DISPLAY shows the live connection status.

**The panel connects but the game will not start**
Its resolution is too small. The game needs height ≥ 16 and ≥ 96×16 pixels
total. Use **PLAY ANYWAYS** instead.
