<# :
@echo off
setlocal
title RetroTV PC Setup
set "RETROTV_SELF=%~f0"

rem --- Needs administrator rights to install Sunshine. Ask Windows, then run again elevated. ---
net session >nul 2>&1
if errorlevel 1 (
    echo Asking Windows for permission to install...
    powershell -NoProfile -Command "try { Start-Process -FilePath $env:RETROTV_SELF -Verb RunAs } catch { Write-Host 'Setup needs permission to install. Run it again and choose Yes.'; Read-Host 'Press Enter to close' }"
    exit /b
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ([IO.File]::ReadAllText($env:RETROTV_SELF))"
echo.
pause
exit /b
#>

# =====================================================================================
#  RetroTV PC Setup
#  Gets this PC ready to stream games to RetroTV on your TV:
#   1. installs Sunshine (free, open-source game streaming host)
#   2. gives it a login RetroTV can use
#   3. installs the virtual controller driver so your TV's controller works on the PC
#   4. adds a tile for each emulator it finds (PS2, PS3, GameCube/Wii, Switch, ...)
#   5. links your TV: type the 4-digit code the TV shows
#  Safe to run again any time (e.g. after installing another emulator).
# =====================================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Pinned downloads with their published SHA-256 checksums (from the Windows Package Manager catalog).
$SunshineVersion = '2026.914.233613'
$Downloads = @{
    SunshineX64 = @{ Url = "https://github.com/LizardByte/Sunshine/releases/download/v$SunshineVersion/Sunshine-Windows-AMD64-installer.msi"; Sha = '1D7FED8BEECD5889DC7FF14CF9F42D6D38F37C3066C13C6C2A5F4E91847E0CCF' }
    SunshineArm = @{ Url = "https://github.com/LizardByte/Sunshine/releases/download/v$SunshineVersion/Sunshine-Windows-ARM64-installer.msi"; Sha = '1E211404B924FCF421657C71E1AAA95CF1F76E172CACF8FF8430BEACC0E30E4A' }
    ViGEmBus    = @{ Url = 'https://github.com/nefarius/ViGEmBus/releases/download/v1.22.0/ViGEmBus_1.22.0_x64_x86_arm64.exe'; Sha = '89220A7865076B342892F98865F3499FB7C4CFD673159E89D352C360FD014C6A' }
    PCSX2       = @{ Url = 'https://github.com/PCSX2/pcsx2/releases/download/v2.8.2/pcsx2-v2.8.2-windows-x64-installer.exe'; Sha = '3422F3705CCC641B7E4FDF3F280D635D05C3C8B53A56C3AF55D9DF115BDCF768' }
}

$Api = 'https://localhost:47990'
# Filled in by the TV when this file is downloaded from it, so setup can link the TV by itself.
$TvAddress = '__RETROTV_TV__'
$TvToken = '__RETROTV_TOKEN__'
$TvKnown = ($TvAddress -like 'http*') -and ($TvToken -match '^[0-9a-f]{32}$')
$DataDir = Join-Path $env:ProgramData 'RetroTV'
$LoginFile = Join-Path $DataDir 'sunshine-login.json'
$Work = Join-Path $env:TEMP 'RetroTV-Setup'
New-Item -ItemType Directory -Force -Path $DataDir, $Work | Out-Null

# Sunshine's local web API uses a self-signed certificate. Trust it for localhost ONLY;
# every other HTTPS download is still fully checked.
Add-Type @"
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class RetroTvCerts {
    public static bool Check(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errors) {
        HttpWebRequest req = sender as HttpWebRequest;
        if (req != null && (req.RequestUri.Host == "localhost" || req.RequestUri.Host == "127.0.0.1")) return true;
        return errors == SslPolicyErrors.None;
    }
    public static void Install() {
        ServicePointManager.ServerCertificateValidationCallback = Check;
    }
}
"@
[RetroTvCerts]::Install()

function Say([string]$Text, [string]$Color = 'Gray') { Write-Host $Text -ForegroundColor $Color }
function Good([string]$Text) { Write-Host "  OK  $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "  !   $Text" -ForegroundColor Yellow }
function Step([int]$N, [string]$Text) { Write-Host ""; Write-Host "[$N/5] $Text" -ForegroundColor Cyan }

function Get-Verified([hashtable]$Item, [string]$FileName) {
    $path = Join-Path $Work $FileName
    if (-not (Test-Path $path) -or (Get-FileHash $path -Algorithm SHA256).Hash -ne $Item.Sha) {
        Say "      downloading $FileName ..."
        Invoke-WebRequest -Uri $Item.Url -OutFile $path -UseBasicParsing
    }
    $hash = (Get-FileHash $path -Algorithm SHA256).Hash
    if ($hash -ne $Item.Sha) {
        Remove-Item $path -Force -ErrorAction SilentlyContinue
        throw "$FileName didn't download correctly (checksum mismatch). Check your internet connection and run setup again."
    }
    return $path
}

function Get-SunshineRoot {
    $svc = Get-CimInstance Win32_Service -Filter "Name='SunshineService'" -ErrorAction SilentlyContinue
    if ($svc -and $svc.PathName) {
        $exe = $svc.PathName.Trim().Trim('"')
        if ($exe -match '^(.*?\.exe)') { $exe = $Matches[1] }
        $root = Split-Path (Split-Path $exe -Parent) -Parent
        if (Test-Path (Join-Path $root 'sunshine.exe')) { return $root }
    }
    $default = Join-Path $env:ProgramFiles 'Sunshine'
    if (Test-Path (Join-Path $default 'sunshine.exe')) { return $default }
    return $null
}

function Get-AuthHeader($Login) {
    $pair = '{0}:{1}' -f $Login.user, $Login.pass
    return @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }
}

function Invoke-Sunshine([string]$Method, [string]$Path, $Body = $null, [int]$TimeoutSec = 30) {
    $params = @{ Uri = "$Api$Path"; Method = $Method; Headers = (Get-AuthHeader $script:Login); TimeoutSec = $TimeoutSec; UseBasicParsing = $true }
    if ($null -ne $Body) {
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress))
        $params.ContentType = 'application/json'
    }
    return Invoke-RestMethod @params
}

function Test-SunshineLogin {
    try { Invoke-Sunshine GET '/api/apps' -TimeoutSec 5 | Out-Null; return $true } catch { return $false }
}

function Wait-Sunshine([int]$Seconds = 45) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri "$Api/api/configLocale" -UseBasicParsing -TimeoutSec 3 | Out-Null
            return $true
        } catch {
            # A 401 still means Sunshine is up and answering.
            if ($_.Exception.Response) { return $true }
            Start-Sleep -Seconds 2
        }
    }
    return $false
}

function Restart-Sunshine {
    Set-Service -Name SunshineService -StartupType Automatic -ErrorAction SilentlyContinue
    Restart-Service -Name SunshineService -Force -ErrorAction SilentlyContinue
    if (-not (Get-Service SunshineService -ErrorAction SilentlyContinue | Where-Object Status -eq 'Running')) {
        Start-Service -Name SunshineService -ErrorAction SilentlyContinue
    }
    if (-not (Wait-Sunshine)) { throw "Sunshine didn't start. Try restarting the PC, then run setup again." }
}

function New-Password {
    $chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'.ToCharArray()
    $bytes = New-Object byte[] 20
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

function Get-LanAddress {
    try {
        $cfg = Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.NetAdapter.Status -eq 'Up' } | Select-Object -First 1
        if ($cfg) { return ($cfg.IPv4Address | Select-Object -First 1).IPAddress }
    } catch { }
    return $null
}

# ------------------------------------------------------------------------------------
Clear-Host
Say ''
Say '  ============================================' Magenta
Say '     RetroTV PC Setup' White
Say '     Stream your PC games to RetroTV on the TV' Gray
Say '  ============================================' Magenta

try {
    # ---------------------------------------------------------------- 1. Sunshine
    Step 1 'Installing Sunshine (the streaming engine)'
    $root = Get-SunshineRoot
    if ($root) {
        Good "Sunshine is already installed ($root)"
    } else {
        $arm = $env:PROCESSOR_ARCHITECTURE -eq 'ARM64'
        $msi = if ($arm) { Get-Verified $Downloads.SunshineArm 'Sunshine-ARM64.msi' } else { Get-Verified $Downloads.SunshineX64 'Sunshine-AMD64.msi' }
        Say '      installing (a progress bar may appear) ...'
        $p = Start-Process msiexec.exe -ArgumentList @('/i', "`"$msi`"", '/passive', '/norestart') -Wait -PassThru
        if ($p.ExitCode -ne 0 -and $p.ExitCode -ne 3010) { throw "Sunshine's installer failed (code $($p.ExitCode))." }
        $root = Get-SunshineRoot
        if (-not $root) { throw "Sunshine installed but couldn't be found. Restart the PC and run setup again." }
        Good 'Sunshine installed'
    }
    $configDir = Join-Path $root 'config'
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null

    # ---------------------------------------------------------------- 2. Login + settings
    Step 2 'Setting up the Sunshine login for RetroTV'
    $script:Login = $null
    if (Test-Path $LoginFile) {
        try { $script:Login = Get-Content $LoginFile -Raw | ConvertFrom-Json } catch { $script:Login = $null }
    }

    # Use the free ViGEmBus controller driver (newer Sunshine versions otherwise ask which driver to use).
    $conf = Join-Path $configDir 'sunshine.conf'
    $lines = @()
    if (Test-Path $conf) { $lines = @(Get-Content $conf -Encoding UTF8 | Where-Object { $_ -notmatch '^\s*gamepad_driver\s*=' }) }
    $lines += 'gamepad_driver = vigembus'
    [IO.File]::WriteAllLines($conf, [string[]]$lines, (New-Object Text.UTF8Encoding $false))

    Restart-Sunshine
    if (-not ($script:Login -and (Test-SunshineLogin))) {
        $script:Login = [pscustomobject]@{ user = 'retrotv'; pass = (New-Password) }
        $exe = Join-Path $root 'sunshine.exe'
        $p = Start-Process -FilePath $exe -ArgumentList @('--creds', $script:Login.user, $script:Login.pass) -WorkingDirectory $root -WindowStyle Hidden -Wait -PassThru
        $script:Login | ConvertTo-Json | Set-Content -Path $LoginFile -Encoding ASCII
        & icacls.exe $LoginFile /inheritance:r /grant:r 'Administrators:F' 'SYSTEM:F' | Out-Null
        Restart-Sunshine
        if (-not (Test-SunshineLogin)) { throw "Couldn't log in to Sunshine after setting the login. Restart the PC and run setup again." }
        Good 'Login created'
        Say "      (Sunshine web page login, if you ever need it: user '$($script:Login.user)', password '$($script:Login.pass)')" DarkGray
    } else {
        Good 'Login already set up'
    }

    # ---------------------------------------------------------------- 3. Controller driver
    Step 3 'Installing the virtual controller driver (lets your TV controller work on the PC)'
    if (Get-Service -Name ViGEmBus -ErrorAction SilentlyContinue) {
        Good 'Controller driver already installed'
    } else {
        $exe = Get-Verified $Downloads.ViGEmBus 'ViGEmBus-setup.exe'
        Start-Process -FilePath $exe -ArgumentList '/exenoui /qn /norestart' -Wait | Out-Null
        if (-not (Get-Service -Name ViGEmBus -ErrorAction SilentlyContinue)) {
            Warn 'Silent install did not finish - the installer will open now. Click through it, then come back here.'
            Start-Process -FilePath $exe -Wait | Out-Null
        }
        if (Get-Service -Name ViGEmBus -ErrorAction SilentlyContinue) {
            Good 'Controller driver installed'
            Restart-Sunshine
        } else {
            Warn 'Controller driver not installed. Streaming works, but the controller may not reach the PC. Run setup again to retry.'
        }
    }

    # ---------------------------------------------------------------- 4. Emulator tiles
    Step 4 'Looking for emulators on this PC'
    $catalog = @(
        @{ Tile = 'PS2';            Exes = @('pcsx2-qt.exe', 'pcsx2.exe'); Args = @{ 'pcsx2-qt.exe' = '-bigpicture -fullscreen' } }
        @{ Tile = 'PS3';            Exes = @('rpcs3.exe') }
        @{ Tile = 'GameCube / Wii'; Exes = @('dolphin.exe') }
        @{ Tile = 'Switch';         Exes = @('eden.exe', 'citron.exe', 'sudachi.exe', 'suyu.exe', 'torzu.exe', 'yuzu.exe', 'ryujinx.exe') }
        @{ Tile = 'Wii U';          Exes = @('cemu.exe') }
        @{ Tile = '3DS';            Exes = @('azahar.exe', 'lime3ds.exe', 'lime3ds-gui.exe', 'citra-qt.exe') }
        @{ Tile = 'Xbox';           Exes = @('xemu.exe'); Args = @{ 'xemu.exe' = '-full-screen' } }
        @{ Tile = 'Xbox 360';       Exes = @('xenia_canary.exe', 'xenia.exe') }
        @{ Tile = 'PS Vita';        Exes = @('vita3k.exe') }
    )
    $wanted = @{}
    foreach ($c in $catalog) { foreach ($e in $c.Exes) { $wanted[$e] = $true } }

    Say '      searching (this can take a minute) ...'
    $hits = @{}
    function Add-Hit([string]$Path) {
        if (-not $Path) { return }
        $leaf = (Split-Path $Path -Leaf).ToLower()
        if ($wanted.ContainsKey($leaf) -and -not $hits.ContainsKey($leaf) -and (Test-Path $Path)) { $hits[$leaf] = $Path }
    }
    # Shortcuts in the Start menu and on desktops
    $shell = New-Object -ComObject WScript.Shell
    $linkRoots = @("$env:ProgramData\Microsoft\Windows\Start Menu\Programs", "$env:PUBLIC\Desktop") +
        @(Get-ChildItem 'C:\Users' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            "$($_.FullName)\Desktop", "$($_.FullName)\AppData\Roaming\Microsoft\Windows\Start Menu\Programs", "$($_.FullName)\OneDrive\Desktop" })
    foreach ($r in $linkRoots) {
        if (-not (Test-Path $r)) { continue }
        Get-ChildItem $r -Recurse -Filter *.lnk -ErrorAction SilentlyContinue | ForEach-Object {
            try { Add-Hit $shell.CreateShortcut($_.FullName).TargetPath } catch { }
        }
    }
    # Common install folders
    $dirRoots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}) +
        @(Get-ChildItem 'C:\Users' -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            "$($_.FullName)\AppData\Local\Programs", "$($_.FullName)\AppData\Local", "$($_.FullName)\AppData\Roaming",
            "$($_.FullName)\Desktop", "$($_.FullName)\Documents", "$($_.FullName)\Downloads", "$($_.FullName)\scoop\apps",
            "$($_.FullName)\OneDrive\Desktop", "$($_.FullName)\OneDrive\Documents" }) +
        @(Get-PSDrive -PSProvider FileSystem -ErrorAction SilentlyContinue | ForEach-Object {
            "$($_.Root)Emulators", "$($_.Root)Emulation", "$($_.Root)Games", "$($_.Root)Emus" })
    foreach ($r in $dirRoots) {
        if (-not $r -or -not (Test-Path $r)) { continue }
        Get-ChildItem $r -Recurse -Depth 3 -Filter *.exe -File -ErrorAction SilentlyContinue | ForEach-Object { Add-Hit $_.FullName }
    }

    # Offer PCSX2 if there's no PS2 emulator yet (it's free and has a TV-friendly mode).
    if (-not ($hits['pcsx2-qt.exe'] -or $hits['pcsx2.exe']) -and $env:PROCESSOR_ARCHITECTURE -ne 'ARM64') {
        $ans = Read-Host '      No PS2 emulator found. Install PCSX2 now? (Y/n)'
        if ($ans -notmatch '^[nN]') {
            $exe = Get-Verified $Downloads.PCSX2 'PCSX2-setup.exe'
            Start-Process -FilePath $exe -ArgumentList '/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP-' -Wait | Out-Null
            Add-Hit (Join-Path $env:ProgramFiles 'PCSX2\pcsx2-qt.exe')
            if ($hits['pcsx2-qt.exe']) { Good 'PCSX2 installed' } else { Warn 'PCSX2 did not install.' }
        }
    }

    # Build the list of tiles to add
    $tiles = @()
    foreach ($c in $catalog) {
        $exeName = $c.Exes | Where-Object { $hits.ContainsKey($_) } | Select-Object -First 1
        if ($exeName) {
            $path = $hits[$exeName]
            $extra = if ($c.Args -and $c.Args.ContainsKey($exeName)) { ' ' + $c.Args[$exeName] } else { '' }
            $tiles += @{ Name = $c.Tile; Cmd = "`"$path`"$extra"; Dir = (Split-Path $path -Parent) }
            Good "$($c.Tile): $path"
        }
    }
    if (-not ($hits['dolphin.exe'])) { Say '      Not found: GameCube / Wii (Dolphin) - free at dolphin-emu.org. Run setup again after installing.' DarkGray }
    if (-not ($catalog[3].Exes | Where-Object { $hits.ContainsKey($_) })) { Say '      Not found: Switch emulator. Run setup again after installing one.' DarkGray }

    # Let the user add anything else (a game launcher, a specific game...)
    while ($true) {
        $ans = Read-Host '      Add another program as a tile (e.g. a game launcher)? (y/N)'
        if ($ans -notmatch '^[yY]') { break }
        Add-Type -AssemblyName System.Windows.Forms
        $dlg = New-Object System.Windows.Forms.OpenFileDialog
        $dlg.Filter = 'Programs (*.exe)|*.exe'
        $dlg.Title = 'Choose a program to show on your TV'
        if ($dlg.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { continue }
        $default = [IO.Path]::GetFileNameWithoutExtension($dlg.FileName)
        $name = Read-Host "      Name to show on the TV [$default]"
        if (-not $name) { $name = $default }
        $tiles += @{ Name = $name; Cmd = "`"$($dlg.FileName)`""; Dir = (Split-Path $dlg.FileName -Parent) }
        Good "$name added"
    }

    # Add / update the tiles in Sunshine
    foreach ($t in $tiles) {
        $existing = @((Invoke-Sunshine GET '/api/apps').apps)
        $index = -1
        for ($i = 0; $i -lt $existing.Count; $i++) { if ($existing[$i].name -eq $t.Name) { $index = $i; break } }
        $body = [ordered]@{
            name           = $t.Name
            cmd            = $t.Cmd
            'working-dir'  = $t.Dir
            index          = $index
            'auto-detach'  = $true
            'wait-all'     = $true
            'exit-timeout' = 5
            'exclude-global-prep-cmd' = $false
            elevated       = $false
        }
        Invoke-Sunshine POST '/api/apps' $body | Out-Null
    }
    $names = @((Invoke-Sunshine GET '/api/apps').apps | ForEach-Object { $_.name })
    Good ("Tiles on your TV: " + ($names -join ', '))

    # ---------------------------------------------------------------- 5. Link the TV
    Step 5 'Linking your TV'
    $linked = $false
    if ($TvKnown) {
        Say "      Linking the TV at $TvAddress (nothing to type) ..."
        try {
            $payload = @{ token = $TvToken; user = $script:Login.user; pass = $script:Login.pass } | ConvertTo-Json -Compress
            Invoke-RestMethod -Uri "$TvAddress/pc/link" -Method POST -Body $payload -ContentType 'application/json' -TimeoutSec 15 | Out-Null
            $deadline = (Get-Date).AddSeconds(90)
            while ((Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 2
                $st = Invoke-RestMethod -Uri "$TvAddress/pc/link-status" -TimeoutSec 10
                if ($st.state -eq 'linked') { $linked = $true; Good ("Linked! The TV now shows this PC's games" + $(if ($st.pc) { " ($($st.pc))" } else { '' })); break }
                if ($st.state -eq 'failed') { Warn "The TV couldn't link automatically: $($st.message)"; break }
            }
            if (-not $linked -and $st.state -ne 'failed') { Warn 'The TV took too long to answer.' }
        } catch {
            Warn "Couldn't reach the TV at $TvAddress. Is RetroTV open on the TV?"
        }
    }

    $ip = Get-LanAddress
    if (-not $linked) {
        Say ''
        Say '      Link by code instead: on the TV open RetroTV > "Play newer consoles from PC" > select this PC.' White
        if ($ip) { Say "      (If the TV doesn't list it, choose 'Enter PC address yourself' and type: $ip)" Gray }
        Say '      The TV then shows a 4-digit code.' White
    }

    while (-not $linked) {
        Say ''
        $pin = Read-Host '      Type the code from the TV (or just press Enter to finish)'
        $pin = "$pin".Trim()
        if (-not $pin) { break }
        if ($pin -notmatch '^\d{4}$') { Warn 'The code is 4 digits, like 4821.'; continue }

        $ok = $false
        $pending = $null
        try { $pending = Invoke-Sunshine GET '/api/pin' -TimeoutSec 10 } catch { $pending = $null }
        if ($pending -and ($pending.PSObject.Properties.Name -contains 'pairings')) {
            # Newer Sunshine: pick the TV's waiting request.
            $deadline = (Get-Date).AddSeconds(20)
            while (@($pending.pairings).Count -eq 0 -and (Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 2
                $pending = Invoke-Sunshine GET '/api/pin' -TimeoutSec 10
            }
            $req = @($pending.pairings) | Select-Object -Last 1
            if (-not $req) { Warn "The TV isn't waiting yet. On the TV select this PC first, then type the new code."; continue }
            $r = Invoke-Sunshine POST '/api/pin' ([ordered]@{ pairing_id = $req.id; pin = $pin; name = 'RetroTV' }) -TimeoutSec 60
        } else {
            # Older Sunshine
            $r = Invoke-Sunshine POST '/api/pin' ([ordered]@{ pin = $pin; name = 'RetroTV' }) -TimeoutSec 60
        }
        if ($r -and ("$($r.status)" -eq 'True' -or "$($r.status)" -eq 'true')) { $ok = $true }

        if ($ok) {
            Say ''
            Good 'Linked! Your PC games now show up in RetroTV on the TV.'
            break
        } else {
            Warn "That didn't work. On the TV choose 'Try again', then type the new code here."
        }
    }

    Say ''
    Say '  All done. Leave the PC on (and signed in) when you want to play.' Green
    Say '  Tip: in each emulator, set the controller to "XInput" / "Xbox 360 controller" once.' DarkGray
    Say '  Run this setup again any time you install another emulator.' DarkGray
}
catch {
    Say ''
    Say "  Setup stopped: $($_.Exception.Message)" Red
    Say '  Nothing harmful was left half-done - you can simply run setup again.' Gray
}
