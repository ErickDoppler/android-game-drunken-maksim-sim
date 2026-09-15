# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

"Drunken Maksim Sim" — an Android chat-moderator game (Kotlin, Gradle Kotlin
DSL). The iPixel BLE LED matrix panel (96x16 class or bigger) is the game's
main output: user names float DVD-logo-style on the panel, the player points
a gyro-driven crosshair (Wii-controller style, phone in portrait) and hits
them with the BAN (hammer) or MUTE buttons on the phone screen. The shell
(settings page, iPixel wiring) is mirrored from `../android-f16-hud-v6`.

## Build

```bash
# JAVA_HOME must be JDK 23 (Gradle 8.11 cannot run on JDK 26);
# gradle.properties pins org.gradle.java.home=C:\workenv\jdk-23.0.2.
# SDK at C:\workenv\android-sdk (local.properties).
gradlew.bat assembleDebug
```

## Architecture

- `game/GameEngine` — all game rules, timers and the panel renderer
  (`renderFrame(w,h)` is called by both the LED hub and the phone mirror;
  the whole engine is coarse-`@Synchronized`). The rules doc comment at the
  top of the class is the authoritative rules summary: word states WHITE /
  GREY / RED / DARK_RED / YELLOW (muted) / GREEN (admins = instant game
  over), blinking whites, the troll smile cascade, beer (+30 s, +5% speed)
  and pause icons, 60 s time credit, GOOD / BAD / NEUTRAL endings. Name
  rosters live in its companion object.
- `game/SoundFx` — sound effects synthesized to WAV files in the cache dir
  at startup, played via SoundPool: glass break (ban), whip (mute), cap pop
  (beer), ding (pause).
- `ui/GameView` — portrait phone screen: live pixel mirror of the panel,
  score/time header, phase messages, BAN + MUTE buttons; tap on the empty
  area re-centers the crosshair (or restarts after game over).
- `ui/SettingsView` — self-drawn monospace settings stripe from the HUD
  project (drawSetting helpers, drag scroll, VOLUME/BACK/LONG PRESS - EXIT
  header); holds the IPIXEL DISPLAY section (OFF/ENABLE, status, SIZE
  fallback picker).
- `led/IPixelHub` — BLE driver for LED_96*16-style iPixel panels, copied
  verbatim from the HUD project (package renamed only). Keep in sync with
  the original when it gets fixes.
- `led/LedPages` — pixel-font utility (5x7 + full-alphabet narrow 3x5 font,
  scaled text, px/vline) used by the engine's renderer.
- `GameState` — iPixel connection/settings state shared with the settings
  page (the Telemetry pattern).
- `MainActivity` — game loop (33 ms handler tick), gyroscope -> crosshair,
  sounds,
  and the HUD-style iPixel flow: Android 12+ BLUETOOTH_SCAN/CONNECT runtime
  request (code 77), ACTION_REQUEST_ENABLE (code 78). Hold Volume Down 3 s
  for settings (pauses the game); settings auto-open at launch while no
  panel is connected. Game starts (rolling intro -> READY? GO!) once a
  connected panel passes `GameEngine.panelSizeOk` (h >= 16, w*h >= 96*16).
  Prefs in SharedPreferences `drunkenmaksim_ui`. Debug hook: `--es settings
  1` opens the settings page on launch.

## Endings

An empty panel — not one name left, admins included, whatever is still
waiting to join — ends the session and the score decides it: positive = GOOD
(win), zero or negative = BAD. BAD also comes from a red escalating past its
dark-red window, and as BAD_ADMIN from banning or muting a green admin. Time
running out is the only NEUTRAL ending.
Every ending scrolls its own blinking banner for 5 s (win: lime "WINNER
WINNER HYENA DINNER"; admin hit: red "ONE MISTAKE AND YOU ARE MISTAKEN!";
other bad: red "GAME OVER! LOOSER!!", or "YOU ARE USELESS" when the score is
exactly zero; neutral: orange "TIME IS UP! GAME
OVER!") and then shows the score in the ending colour.

## Spamming specials

`GameEngine.SPAM` maps a special's name to (spam lines, interval): oLeg
(BABKI, BABKI, GDE, SUKA, BABKI), Kozlenko (LINUX), Konrad (DOOM, PENTIUM)
and LeoLeo (BLAH-BLAH) post every 2 s; neDima (SERVICE, ZAVTRA, POTOM,
REMONT) every 3 s. Lines cycle, spawn as RED names over the four-word cap,
and stay on the panel after the boss is banned — cleaning up after the
flood is the player's job, and each line still escalates on its own red
timer. Edit that map to retune a flood.

## Two play modes

- **Panel mode** (preferred): the LED panel is the game field. Grip assumed
  is portrait but held HORIZONTALLY, screen up, top edge toward the panel
  like a TV remote — so the world vertical is device Z: pan = -gz,
  tilt = -gx, gain GYRO_GAIN px/rad.
- **Local mode** ("PLAY ANYWAYS" in the settings, shown whenever no
  game-capable panel is ready): the game runs on the phone screen only, on
  a virtual field 96 px wide whose height fills everything above the
  buttons + hint line (`GameView.localFieldSize()`, square pixels on
  screen); the score and time counters are inset three digit widths from
  the field edges there so curved/rounded phone edges cannot eat them.
  Grip assumed is portrait tilted back ~45 deg, so the world
  vertical splits evenly between device Y and Z: pan = -(gy+gz)*cos45,
  tilt = -gx. Gain is retuned to fieldHeight/LOCAL_SWEEP_RAD so a full
  vertical sweep is a comfortable wrist tilt. Nothing is streamed to a
  panel while local mode runs; a panel that connects mid-game takes over on
  the next restart.

The manual SIZE picker only lists game-capable panels: AUTO, 96*16, 96*32,
128*16, 128*24, 128*32.

## Deploy

Do not deploy to any device unless asked. The device pool and serials are
the same as the F16 HUD project's (see that project / user memory).
