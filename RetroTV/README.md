# RetroTV

A controller-friendly retro game emulator for Android TV / Google TV.

- Bluetooth controllers (Xbox, PlayStation, generic), up to 4 players
- Send games to the TV from your phone or PC over Wi-Fi, or load them from a USB drive
- Save states, in-game saves kept automatically, pause menu (Select + Start)
- Emulator cores are downloaded the first time you play each console

| Console | Core | File types |
|---|---|---|
| NES | FCEUmm | .nes .fds .zip |
| SNES | Snes9x | .sfc .smc .zip |
| Game Boy / Color / Advance | mGBA | .gb .gbc .gba .zip |
| Genesis / Master System / Game Gear | Genesis Plus GX | .md .gen .sms .gg .bin .zip |
| PlayStation | PCSX ReARMed | .cue/.bin .chd .pbp .m3u |
| Nintendo 64 | Mupen64Plus-Next | .n64 .z64 .v64 .zip |
| Nintendo DS | melonDS DS | .nds .zip |
| PSP | PPSSPP | .iso .cso |
| Dreamcast (experimental) | Flycast | .gdi .cdi .chd |

PS2, GameCube, Wii and Switch aren't included: TV chips are far too slow for them.

## 1. Build the APK (no software needed)

1. Make a free GitHub account and create a new **public** repository called `RetroTV`.
2. On the repo page, choose **Add file > Upload files** and drag in everything from this folder
   (including the hidden `.github` folder - if your file explorer hides it, use GitHub Desktop instead).
3. Commit. Open the **Actions** tab and wait about 5 minutes for "Build APK" to go green.
4. Your APK is now at:
   `https://github.com/YOUR-NAME/RetroTV/releases/latest/download/RetroTV.apk`

If the build goes red, open it, copy the error, and paste it to Claude.

Prefer building locally? Open this folder in Android Studio and choose **Build > Build APK(s)**.

## 2. Install it on the TV

Easiest: install the free **Downloader** app (by AFTVnews) from the TV's Play Store, type the
APK link above, and install. The TV will ask you to allow Downloader to install unknown apps - allow it.

Alternatives: the **Send Files to TV** app, or `adb install RetroTV.apk` from your PC.

When RetroTV opens, allow file access so it can read USB drives.

## 3. Pair your controller

TV **Settings > Remotes & Accessories > Pair accessory**. On an Xbox controller, hold the small
pair button on top until the Xbox logo flashes quickly. RetroTV's home screen shows when it's connected.

## 4. Add games

- **Wi-Fi:** RetroTV's home screen shows an address like `http://192.168.1.20:8080`. Open it in a
  browser on your phone or PC (same Wi-Fi), pick the console, choose files, Send.
- **USB drive:** plug it in and press Rescan. Folders named after consoles (`psx`, `snes`, `n64`...) help.
- Disc games: keep `.cue` and `.bin` files together, or convert to a single `.chd`. Don't zip them.

Use games you own. RetroTV doesn't come with any.

## Controls

| Xbox controller | Does |
|---|---|
| Buttons, sticks, triggers | Mapped to the console's controller by position |
| View + Menu (Select + Start) | Pause menu: Resume, Save state, Load state, Reset, Quit |
| Left stick | D-pad on retro consoles, analog stick on N64 / PSP / Dreamcast |
| TV remote Back | Pause menu |

## BIOS files (optional)

PS1 and Dreamcast run without a BIOS, but a few games work better with one. Upload it with the
console set to "BIOS / system file" (PS1: `scph5501.bin`; Dreamcast: `dc_boot.bin`, `dc_flash.bin`).

## Credits

Emulation by [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) and libretro cores
from [libretro](https://www.libretro.com/). LibretroDroid is GPL-3.0, so this app is too.
