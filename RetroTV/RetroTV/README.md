# RetroTV

A controller-friendly retro game emulator for Android TV / Google TV.

- Console-style home screen: cover art, "Continue playing", Favourites, a row per console, your PC's games
- Cover art downloaded automatically from the free libretro thumbnail library (designed covers when there's no match)
- Settings screen, and a QR code for sending games from your phone
- Bluetooth controllers (Xbox, PlayStation, generic), up to 4 players
- Send games to the TV from your phone or PC over Wi-Fi, or load them from a USB drive
- Save states, in-game saves kept automatically, pause menu (Select + Start)
- Emulator cores are downloaded the first time you play each console
- **TV check**: works out how powerful your TV is and rates every console (great / playable / too slow)
- **Live speed check**: if a game runs slow, you get a heads-up and the rating updates
- **Play from PC**: streams PS2, PS3, GameCube, Wii, Switch and more from your Windows PC, built in, set up with one file

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
| Dreamcast | Flycast | .gdi .cdi .chd |
| GameCube / Wii (heavy) | Dolphin | .iso .rvz .gcm .wbfs .gcz |
| PlayStation 2 (heavy) | Play! | .iso .chd |
| Nintendo 3DS (heavy) | Citra | .3ds .cci .cxi (decrypted) |

The heavy consoles need a fast, 64-bit TV such as an Nvidia Shield. Open **TV check** on the home
screen to see what yours can handle. If your TV can't run them, use **Play from PC**.

`.iso` files are recognised automatically (PSP, PS2, GameCube or Wii) by reading the disc header.

## Play newer consoles from your PC (built in)

For consoles the TV is too slow for (PS2, PS3, GameCube, Wii, Switch, Wii U, Xbox...), your Windows PC
runs the emulator and RetroTV streams it. Streaming is built into RetroTV (it uses the open-source
Moonlight engine), so there's no second app on the TV.

**One-time setup**

1. On the TV, open RetroTV and choose **Play newer consoles from PC**. It shows an address like
   `http://192.168.1.20:8080/pc`.
2. Open that address in your PC's browser and download **RetroTV PC Setup**. Double-click it.
   (If Windows says "Windows protected your PC": *More info* > *Run anyway*, then *Yes*.)
3. That's it. The setup installs Sunshine and the controller driver, adds a tile for each emulator it
   finds, and then links the TV by itself - no code to type. (The downloaded file carries a one-time
   pass for your TV, so it can hand the TV the Sunshine login.)

Linking again later (e.g. after "Link a different PC") is automatic too, because the TV keeps the login.
Installed Sunshine yourself? Enter its web login on the TV's `/pc` page and the TV links automatically.

Your PC's consoles then appear at the top of the RetroTV library, marked "via PC". Select one to start streaming.

- **Stop streaming:** hold View + Menu + LB + RB together on the controller.
- **Mouse for PC menus:** hold Menu on the controller to switch it into mouse mode.
- **Picture quality:** Play from PC > Stream quality (defaults to 1080p, 60 fps).
- **PC asleep?** RetroTV sends a Wake-on-LAN signal when you pick a game (if the PC supports it).
- Installed another emulator? Run the setup file again; it updates the tiles.
- Switch emulators need keys and firmware dumped from your own Switch; the setup can't provide those.

The setup file is also in this project at `app/src/main/assets/RetroTV-PC-Setup.bat`.

## 1. Build the APK (no software needed)

1. Make a free GitHub account and create a new **public** repository called `RetroTV`.
2. On the repo page, choose **Add file > Upload files** and drag in everything from this folder
   (including the hidden `.github` folder - if your file explorer hides it, use GitHub Desktop instead).
3. Commit. Open the **Actions** tab and wait for "Build RetroTV" to go green. The first build takes about
   10-15 minutes because it also compiles the streaming engine; later builds are faster.
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

## Home screen

- **Left / right** moves along a row, **up / down** between rows. The top shows the selected game.
- **A / OK** plays. **Y** (or hold OK, or the remote's menu button) opens options: favourite,
  remove from Continue playing, delete the game from the TV.
- Top bar: **Games**, **PC**, **Settings**, then **+** (add games via QR code) and the TV check.

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
from [libretro](https://www.libretro.com/). PC streaming by [Moonlight](https://github.com/moonlight-stream/moonlight-android)
(v12.1, downloaded automatically at build time) and [Sunshine](https://github.com/LizardByte/Sunshine) on the PC.
All are GPL-3.0, so this app is too.
