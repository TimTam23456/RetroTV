# RetroTV

A controller-friendly retro game emulator for Android TV / Google TV.

- Console-style home screen: cover art, "Continue playing", Favourites, a row per console, your PC's games
- Cover art downloaded automatically from the free libretro thumbnail library (designed covers when there's no match)
- Settings screen, and a QR code for sending games from your phone
- Original background music and menu sounds (made for RetroTV - see `make_audio.py`), adjustable in Settings > Sound
- **LB / RB** switch between the Games, Browse, PC and Settings tabs
- **Browse (online library)**: add a web address and RetroTV lists every game file it can download from that page, with covers
- Bluetooth controllers (Xbox, PlayStation, generic), up to 4 players
- Send games to the TV from your phone or PC over Wi-Fi, or load them from a USB drive
- Save states, in-game saves kept automatically, pause menu (Select + Start)
- **Cheats** in the pause menu: thousands of codes from the free libretro cheat list, or type your own (Game Genie, Action Replay, GameShark)
- **Fast forward** (3x): pause menu, or Select + RB
- **Rewind**: hold Select + LB to play the last 30 seconds backwards (2D and PS1-era consoles)
- **Search** (magnifier on the top bar): finds games on the TV, USB drives, your PC and your online sources, even with typos
- **Picture styles**: Smooth HD (cleans up pixel art on a big TV), Retro CRT, Handheld screen, Sharp pixels
- **HD 3D consoles** (Settings): N64, PS1, PSP, Dreamcast and up render at 2x resolution on fast TVs
- **Kids mode** (Settings > Kids): only the games you pick, no settings or deleting, PIN to leave
- **Save backup**: open `http://<TV address>/saves` on a phone or PC to download all your saves as a zip, or restore them
- Asks the TV for its low-latency game mode while you play, so the controller feels snappier
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
- **USB drive or card reader:** plug it in and the library refreshes by itself. Folders named after consoles (`psx`, `snes`, `n64`...) help.
  Not showing up? Settings > Check USB drive shows what RetroTV can see (or open `http://<TV address>/storage` on your phone).
- Disc games: keep `.cue` and `.bin` files together, or convert to a single `.chd`. Don't zip them.

Use games you own. RetroTV doesn't come with any.

## Browse: download games from a web page

The **Browse** tab (press **RB** from Games) turns any web page that links to game files into a
library you can download from - for example a folder of your games on a home NAS or PC
(`192.168.1.50/games`), or your own web storage.

1. **Add a source:** press **Add a source** and type the address with the TV keyboard - or, easier,
   open `http://<your TV's address>/library` on your phone or PC and paste it there.
2. RetroTV reads the page and its sub-folders (two levels deep), finds every file it can play, works out
   the console from the folder name or file type, and shows them as a cover grid. Filter by console at the top.
3. Press **A** on a game to download it into your library (two downloads at a time, keeps going while you
   browse). When it says "In library", press **A** again to play.

**Share a games folder from your PC (one file):** open `http://<your TV's address>/library` on the PC and
download **RetroTV Game Server**. Double-click it: it makes a `RetroTV Games` folder with a sub-folder
per console (or uses a folder you choose), shares it on your home Wi-Fi at `http://<PC address>:8080/games`,
and adds it to the TV's Browse tab by itself. Drop games into the console folders and they show up on the
TV. It can start with Windows so the library is always there while the PC is on (it's also in this
project at `app/src/main/assets/RetroTV-Game-Server.bat`).

- Opening Browse checks your sources for new games by itself; **Rescan** (top bar) checks again right away.
- Sources on your home network are searched deeply (sub-folders inside console folders, hundreds of games), so `snes/Some Game/Some Game.sfc` works.
- Disc games: a `.cue` downloads together with its `.bin` tracks.
- `.7z` and `.rar` files are skipped (RetroTV can't open them) - the screen tells you how many.
- Pages that build their list with JavaScript, or need a login, can't be read. Plain file listings work best.
- Remove a source: highlight it and press **Y** (or hold OK). Already downloaded games stay.
- Only add sources with games you own or that are free to download.

## Home screen

- **Left / right** moves along a row, **up / down** between rows. The top shows the selected game.
- **A / OK** plays. **Y** (or hold OK, or the remote's menu button) opens options: favourite,
  remove from Continue playing, delete the game from the TV.
- Top bar: **Games**, **Browse**, **PC**, **Settings** (or press **LB / RB** to switch), then **+** (add games via QR code) and the TV check.
- Music plays in the menus and fades out when a game or PC stream starts. Turn it down or off in Settings > Sound.

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
