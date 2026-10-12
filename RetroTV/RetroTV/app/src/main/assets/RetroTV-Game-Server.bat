<# :
@echo off
setlocal
title RetroTV Game Server
set "RETROTV_SELF=%~f0"
set "RETROTV_ARG=%~1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ([IO.File]::ReadAllText($env:RETROTV_SELF))"
echo.
pause
exit /b
#>

# =====================================================================================
#  RetroTV Game Server
#  Turns a folder on this PC into a game library your TV can browse:
#   - shares the folder at http://<this PC>:8080/games (on your home Wi-Fi only)
#   - adds it to RetroTV's Browse tab by itself
#   - can start automatically with Windows
#  Drop games into the console folders; RetroTV sees them next time you open Browse.
#  Close this window to stop sharing.
# =====================================================================================

$ErrorActionPreference = 'Stop'
$TvAddress = '__RETROTV_TV__'
$PreferredPort = 8080
$Auto = $env:RETROTV_ARG -eq 'auto'
$Self = $env:RETROTV_SELF
$AppDir = Join-Path $env:LOCALAPPDATA 'RetroTV'
$ConfigFile = Join-Path $AppDir 'game-server.json'
$Installed = Join-Path $AppDir 'RetroTV-Game-Server.bat'
$Consoles = 'nes', 'snes', 'gb', 'gba', 'genesis', 'psx', 'n64', 'nds', 'psp', 'dc', 'gc', 'ps2', '3ds'
$IsWin = [Environment]::OSVersion.Platform -eq 'Win32NT'

function Say($text, $color = 'Gray') { Write-Host $text -ForegroundColor $color }

function Get-LanIp {
    # Asks Windows which address it would use to reach the internet (nothing is actually sent).
    try {
        $s = [Net.Sockets.Socket]::new([Net.Sockets.AddressFamily]::InterNetwork, [Net.Sockets.SocketType]::Dgram, [Net.Sockets.ProtocolType]::Udp)
        $s.Connect('8.8.8.8', 80)
        $ip = $s.LocalEndPoint.Address.ToString()
        $s.Dispose()
        if ($ip -and $ip -ne '0.0.0.0') { return $ip }
    } catch { }
    $any = [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) |
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and -not [Net.IPAddress]::IsLoopback($_) } | Select-Object -First 1
    if ($any) { return $any.ToString() }
    return '127.0.0.1'
}

function Load-Config {
    if (Test-Path -LiteralPath $ConfigFile) {
        try { return Get-Content -LiteralPath $ConfigFile -Raw | ConvertFrom-Json } catch { }
    }
    return [pscustomobject]@{ folder = ''; tv = ''; linked = '' }
}

function Save-Config($c) {
    New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
    $c | ConvertTo-Json | Set-Content -LiteralPath $ConfigFile -Encoding UTF8
}

Say ''
Say '  RetroTV Game Server' 'Cyan'
Say '  Shares a folder of your games with RetroTV on your TV.' 'DarkGray'
Say ''

$config = Load-Config
foreach ($k in 'folder', 'tv', 'linked') { if (-not ($config.PSObject.Properties.Name -contains $k)) { $config | Add-Member $k '' } }
if ($TvAddress -like 'http*') { $config.tv = $TvAddress }

# ------------------------------------------------------------------ 1. the games folder
if (-not $config.folder -or (-not $Auto)) {
    $default = if ($config.folder) { $config.folder } else { Join-Path ([Environment]::GetFolderPath('UserProfile')) 'RetroTV Games' }
    if ($Auto) {
        $config.folder = $default
    } else {
        Say "  Where are your games?"
        Say "  Press Enter to use:  $default" 'White'
        $answer = Read-Host '  Or paste a different folder'
        $answer = $answer.Trim().Trim('"')
        $config.folder = if ($answer) { $answer } else { $default }
    }
}
$Root = [IO.Path]::GetFullPath($config.folder)
if (-not [IO.Directory]::Exists($Root)) { [IO.Directory]::CreateDirectory($Root) | Out-Null }
# A new or empty folder gets one sub-folder per console, so RetroTV knows what each game is.
if ([IO.Directory]::GetDirectories($Root).Count -eq 0) {
    foreach ($c in $Consoles) { [IO.Directory]::CreateDirectory((Join-Path $Root $c)) | Out-Null }
    $readme = "Put each game in its console's folder, e.g. snes\Super Mario World.sfc`r`n" +
        "nes snes gb gba genesis psx n64 nds psp dc gc ps2 3ds`r`n`r`n" +
        "Disc games: keep the .cue and its .bin files together. .7z and .rar can't be used - extract them first.`r`n" +
        "Use games you own."
    [IO.File]::WriteAllText((Join-Path $Root 'HOW TO USE.txt'), $readme)
}
Save-Config $config

# ------------------------------------------------------------------ 2. start with Windows (first run only asks)
if (-not $Auto -and $IsWin) {
    try {
        New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
        if ($Self -and ([IO.Path]::GetFullPath($Self) -ne [IO.Path]::GetFullPath($Installed))) {
            Copy-Item -LiteralPath $Self -Destination $Installed -Force
        }
        $lnkPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'RetroTV Game Server.lnk'
        $want = Read-Host '  Share your games automatically whenever this PC is on? (Y/n)'
        if ($want.Trim() -notmatch '^(n|no)$') {
            $ws = New-Object -ComObject WScript.Shell
            $lnk = $ws.CreateShortcut($lnkPath)
            $lnk.TargetPath = $Installed
            $lnk.Arguments = 'auto'
            $lnk.WindowStyle = 7   # minimised
            $lnk.Description = 'Shares your games with RetroTV'
            $lnk.Save()
            Say '  OK - it will start (minimised) when you sign in to Windows.' 'Green'
        } elseif (Test-Path -LiteralPath $lnkPath) {
            Remove-Item -LiteralPath $lnkPath -Force
        }
    } catch {
        Say "  (Couldn't set up auto-start: $($_.Exception.Message))" 'DarkYellow'
    }
}

# ------------------------------------------------------------------ 3. firewall (lets the TV in, home networks only)
if ($IsWin) {
    try {
        $rule = Get-NetFirewallRule -DisplayName 'RetroTV Game Server' -ErrorAction SilentlyContinue
        if (-not $rule) {
            Say '  Asking Windows to let your TV reach the games (click Yes)...'
            $cmd = "New-NetFirewallRule -DisplayName 'RetroTV Game Server' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8080-8090 -Profile Private,Domain | Out-Null"
            Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $cmd
        }
        $public = Get-NetConnectionProfile -ErrorAction SilentlyContinue | Where-Object { $_.NetworkCategory -eq 'Public' }
        if ($public) {
            Say ''
            Say "  Heads-up: Windows thinks '$($public[0].Name)' is a public network, so it will block the TV." 'Yellow'
            Say '  Fix: Settings > Network & internet > Wi-Fi (or Ethernet) > your network > choose "Private network".' 'Yellow'
        }
    } catch {
        Say '  (If Windows asks whether to allow PowerShell on private networks, choose Allow.)' 'DarkYellow'
    }
}

# ------------------------------------------------------------------ 4. start sharing
$listener = $null
foreach ($p in $PreferredPort..($PreferredPort + 10)) {
    try {
        $l = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $p)
        $l.Start()
        $listener = $l; $Port = $p; break
    } catch { }
}
if (-not $listener) { Say '  Ports 8080-8090 are all in use, so the games can''t be shared. Close other servers and try again.' 'Red'; return }

$ip = Get-LanIp
$GamesUrl = "http://${ip}:$Port/games/"

# ------------------------------------------------------------------ 5. add it to RetroTV
function Link-Tv($tv) {
    $tv = $tv.Trim().TrimEnd('/')
    if ($tv -notlike 'http*') { $tv = "http://$tv" }
    if ($tv -notmatch ':\d+$') { $tv = "${tv}:8080" }
    $pcName = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [Environment]::MachineName }
    $name = "$pcName games"
    $null = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$tv/library/add" -Body @{ url = $GamesUrl; name = $name } -TimeoutSec 8
    return $tv
}

if ($config.linked -ne $GamesUrl) {
    if (-not $config.tv -and -not $Auto) {
        Say ''
        Say '  To add this to RetroTV by itself, type the address shown on RetroTV''s home screen'
        $config.tv = (Read-Host '  (e.g. 192.168.1.20:8080), or press Enter to skip').Trim()
    }
    if ($config.tv) {
        try {
            $config.tv = Link-Tv $config.tv
            $config.linked = $GamesUrl
            Say '  Added to RetroTV - open the Browse tab on the TV.' 'Green'
        } catch {
            Say "  Couldn't reach the TV at $($config.tv). Make sure RetroTV is open, or add the address below yourself." 'DarkYellow'
        }
    }
    Save-Config $config
}

Say ''
Say '  ===============================================================' 'Cyan'
Say "   Your games are shared at:  $GamesUrl" 'White'
Say "   Games folder:              $Root" 'White'
Say '  ===============================================================' 'Cyan'
Say '  Drop games into the console folders - RetroTV sees them when you open Browse (or press Rescan).'
if ($config.linked -ne $GamesUrl) { Say '  On the TV: Browse > Add a source, and type the address above.' }
Say '  Keep this window open (minimise it) while you use the TV. Close it to stop sharing.' 'DarkGray'
Say ''
if (-not $Auto -and $IsWin) { try { Start-Process explorer.exe $Root } catch { } }

# Each request is handled on its own thread, so the TV can download two games while it browses.
$handler = {
    param($client, $root)
    $log = $null
    try {
        $client.ReceiveTimeout = 20000
        $client.SendTimeout = 120000
        $stream = $client.GetStream()
        $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $false, 8192, $true)
        $first = $reader.ReadLine()
        if (-not $first) { return }
        $headers = @{}
        while ($true) {
            $h = $reader.ReadLine()
            if ([string]::IsNullOrEmpty($h)) { break }
            $i = $h.IndexOf(':')
            if ($i -gt 0) { $headers[$h.Substring(0, $i).Trim().ToLower()] = $h.Substring($i + 1).Trim() }
        }
        $parts = $first.Split(' ')
        $method = $parts[0].ToUpper()
        $target = if ($parts.Count -gt 1) { $parts[1] } else { '/' }

        function Send-Head([int]$code, [string]$status, [hashtable]$extra) {
            $sb = [Text.StringBuilder]::new()
            [void]$sb.Append("HTTP/1.1 $code $status`r`nConnection: close`r`nServer: RetroTV`r`n")
            foreach ($k in $extra.Keys) { [void]$sb.Append("${k}: $($extra[$k])`r`n") }
            [void]$sb.Append("`r`n")
            $b = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
            $stream.Write($b, 0, $b.Length)
        }
        function Send-Text([int]$code, [string]$status, [string]$type, [string]$body, [hashtable]$extra = @{}) {
            $b = [Text.Encoding]::UTF8.GetBytes($body)
            $extra['Content-Type'] = $type
            $extra['Content-Length'] = $b.Length
            Send-Head $code $status $extra
            if ($method -ne 'HEAD') { $stream.Write($b, 0, $b.Length) }
        }
        function Html([string]$s) { [Net.WebUtility]::HtmlEncode($s) }

        if ($method -ne 'GET' -and $method -ne 'HEAD') { Send-Text 405 'Method Not Allowed' 'text/plain' 'Only GET'; return }
        $path = [Uri]::UnescapeDataString($target.Split('?')[0])
        if ($path -eq '/' -or $path -eq '/games') { Send-Text 302 'Found' 'text/plain' '' @{ Location = '/games/' }; return }
        if (-not $path.StartsWith('/games/')) { Send-Text 404 'Not Found' 'text/plain' 'Not found'; return }

        $rel = $path.Substring(7).Replace('/', [IO.Path]::DirectorySeparatorChar)
        $rootFull = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar)
        $full = [IO.Path]::GetFullPath([IO.Path]::Combine($rootFull, $rel))
        # Never serve anything outside the games folder.
        if ($full -ne $rootFull -and -not $full.StartsWith($rootFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            Send-Text 403 'Forbidden' 'text/plain' 'Forbidden'; return
        }

        if ([IO.Directory]::Exists($full)) {
            if (-not $path.EndsWith('/')) { Send-Text 302 'Found' 'text/plain' '' @{ Location = ($target.Split('?')[0] + '/') }; return }
            $dirs = [IO.DirectoryInfo]::new($full).GetDirectories() | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::Hidden) -and -not $_.Name.StartsWith('.') } | Sort-Object Name
            $files = [IO.DirectoryInfo]::new($full).GetFiles() | Where-Object { -not ($_.Attributes -band [IO.FileAttributes]::Hidden) -and -not $_.Name.StartsWith('.') -and $_.Extension -ne '.part' } | Sort-Object Name
            $sb = [Text.StringBuilder]::new()
            [void]$sb.Append("<!doctype html><html><head><meta charset=`"utf-8`"><meta name=`"viewport`" content=`"width=device-width,initial-scale=1`"><title>RetroTV games - $(Html $path)</title>")
            [void]$sb.Append('<style>body{font-family:system-ui,sans-serif;background:#0e0f13;color:#eceaf4;max-width:720px;margin:0 auto;padding:24px 16px}a{color:#b9a8ff;text-decoration:none}li{padding:6px 0;border-bottom:1px solid #222}span{color:#8d8a9c;float:right}</style></head><body>')
            [void]$sb.Append("<h1>$(Html $path)</h1><ul>")
            if ($path -ne '/games/') { [void]$sb.Append('<li><a href="../">.. (up)</a></li>') }
            foreach ($d in $dirs) { [void]$sb.Append("<li><a href=`"$([Uri]::EscapeDataString($d.Name))/`">$(Html $d.Name)/</a></li>") }
            foreach ($f in $files) {
                $mb = '{0:N1} MB' -f ($f.Length / 1MB)
                [void]$sb.Append("<li><a href=`"$([Uri]::EscapeDataString($f.Name))`">$(Html $f.Name)</a><span>$mb</span></li>")
            }
            [void]$sb.Append('</ul></body></html>')
            Send-Text 200 'OK' 'text/html; charset=utf-8' $sb.ToString()
            return
        }

        if (-not [IO.File]::Exists($full)) { Send-Text 404 'Not Found' 'text/plain' 'Not found'; return }
        $fs = [IO.FileStream]::new($full, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $len = $fs.Length
            $start = 0L; $end = $len - 1
            $code = 200; $status = 'OK'
            $extra = @{ 'Content-Type' = 'application/octet-stream'; 'Accept-Ranges' = 'bytes' }
            $range = $headers['range']
            if ($range -and $range -match '^bytes=(\d*)-(\d*)$' -and $len -gt 0) {
                if ($Matches[1] -ne '') { $start = [long]$Matches[1]; if ($Matches[2] -ne '') { $end = [Math]::Min([long]$Matches[2], $len - 1) } }
                elseif ($Matches[2] -ne '') { $start = [Math]::Max(0L, $len - [long]$Matches[2]) }
                if ($start -gt $end -or $start -ge $len) {
                    Send-Text 416 'Range Not Satisfiable' 'text/plain' '' @{ 'Content-Range' = "bytes */$len" }; return
                }
                $code = 206; $status = 'Partial Content'
                $extra['Content-Range'] = "bytes $start-$end/$len"
            }
            $count = [Math]::Max(0L, $end - $start + 1)
            $extra['Content-Length'] = $count
            Send-Head $code $status $extra
            if ($method -eq 'GET' -and $count -gt 0) {
                $log = "Sending $([IO.Path]::GetFileName($full)) ({0:N0} MB)" -f ($count / 1MB)
                [void]$fs.Seek($start, [IO.SeekOrigin]::Begin)
                $buf = New-Object byte[] (1MB)
                $left = $count
                while ($left -gt 0) {
                    $n = $fs.Read($buf, 0, [int][Math]::Min($buf.Length, $left))
                    if ($n -le 0) { break }
                    $stream.Write($buf, 0, $n)
                    $left -= $n
                }
            }
        } finally { $fs.Dispose() }
    } catch {
        if ($log) { $log += ' - stopped (the TV cancelled or lost connection)' }
    } finally {
        try { $client.Close() } catch { }
    }
    return $log
}

$pool = [runspacefactory]::CreateRunspacePool(1, 8)
$pool.Open()
$jobs = [Collections.Generic.List[object]]::new()
try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        $ps = [powershell]::Create()
        $ps.RunspacePool = $pool
        [void]$ps.AddScript($handler).AddArgument($client).AddArgument($Root)
        $jobs.Add([pscustomobject]@{ ps = $ps; handle = $ps.BeginInvoke() })
        foreach ($j in @($jobs)) {
            if ($j.handle.IsCompleted) {
                try { foreach ($line in $j.ps.EndInvoke($j.handle)) { if ($line) { Say ("  {0:HH:mm}  {1}" -f (Get-Date), $line) 'DarkCyan' } } } catch { }
                $j.ps.Dispose()
                [void]$jobs.Remove($j)
            }
        }
    }
} finally {
    $listener.Stop()
    $pool.Close()
}
