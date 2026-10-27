# Makes the CodeMie gateway reachable from the Developer Flow's coding containers on a
# Windows + Podman (WSL) machine, where published ports and host.docker.internal do not
# reach Windows services. See README.md in this folder.
#
#   coding container --> host.docker.internal:4102   relay container in the VM (socat)
#                    --> VM 127.0.0.1:4101           sshd remote forward
#                    --> Windows 127.0.0.1:4101      bridge.js (client key -> daemon key)
#                    --> Windows 127.0.0.1:4001      `codemie proxy` (injects the SSO credential)
#                    --> the CodeMie gateway
#
# Idempotent: every link that is already up is left alone. -Stop takes them all down.
# Run it from an ordinary PowerShell; the browser SSO sign-in is done by you when asked.
param(
  [switch]$Stop,
  # Where the generated client key and the logs live. Keep it outside the repository.
  [string]$State = (Join-Path $env:LOCALAPPDATA 'factory-codemie'),
  # Optional CODEMIE_HOME (defaults to the CLI's own ~/.codemie).
  [string]$CodemieHome = '',
  # Optional .env file to receive FACTORY_PI_BASE_URL and FACTORY_PI_API_KEY.
  [string]$UpdateEnv = ''
)

$ErrorActionPreference = 'Stop'

$ProxyPort = 4001
$BridgePort = 4101
$RelayPort = 4102
$RelayName = 'factory-codemie-relay'
$RelayImage = 'docker.io/alpine/socat:1.8.0.3'
$MinCliVersion = [version]'0.15.8'   # older CLIs are rejected by the gateway with HTTP 426

# The caller may itself run under a CodeMie/Claude wrapper whose CODEMIE_* and ANTHROPIC_*
# variables override the CLI's profile lookup and must not leak into the bridge or ssh.
Get-ChildItem Env: | Where-Object { $_.Name -match '^(CODEMIE_|ANTHROPIC_|CLAUDE_CODE_|CLAUDECODE$|CLAUDE_)' } |
  ForEach-Object { [Environment]::SetEnvironmentVariable($_.Name, $null, 'Process') }
if ($CodemieHome) { $env:CODEMIE_HOME = $CodemieHome }
$daemonHome = if ($CodemieHome) { $CodemieHome } else { Join-Path $env:USERPROFILE '.codemie' }
$daemonFile = Join-Path $daemonHome 'proxy-daemon.json'
$keyFile = Join-Path $State 'bridge-client-key'
$logDir = Join-Path $State 'out'

# A native program writing progress to stderr must not terminate a script running with
# ErrorActionPreference = Stop (Windows PowerShell 5.1), so judge native commands by exit code.
function Invoke-Native {
  param([scriptblock]$Command)
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $Command } finally { $ErrorActionPreference = $previous }
}

# Connects to IPv4 loopback on purpose: the Podman VM's listeners are relayed to Windows on
# IPv6 loopback only, so a listener check that accepts any address sees the tunnel's own
# relay and mistakes it for the bridge.
function Test-Loopback {
  param([int]$Port)
  $client = New-Object Net.Sockets.TcpClient
  try {
    $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
    $pending.AsyncWaitHandle.WaitOne(1000) -and $client.Connected
  } catch { $false } finally { $client.Close() }
}

function Wait-Until {
  param([scriptblock]$Condition, [int]$Seconds)
  $deadline = (Get-Date).AddSeconds($Seconds)
  while (-not (& $Condition) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  [bool](& $Condition)
}

function Get-Daemon {
  if (-not (Test-Path -LiteralPath $daemonFile)) { return $null }
  try { Get-Content -LiteralPath $daemonFile -Raw | ConvertFrom-Json } catch { $null }
}

function Stop-Bridge {
  Get-CimInstance Win32_Process -Filter "Name='node.exe'" |
    Where-Object { $_.CommandLine -match 'bridge\.js' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}

function Stop-Tunnel {
  Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" |
    Where-Object { $_.CommandLine -match '4101:127\.0\.0\.1:4101' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}

$codemieCommand = Get-Command codemie.cmd -ErrorAction SilentlyContinue
if (-not $codemieCommand) { throw 'The CodeMie CLI is not installed: npm install -g @codemieai/code' }
$codemie = $codemieCommand.Source

if ($Stop) {
  Stop-Tunnel
  Stop-Bridge
  Invoke-Native { podman rm -f $RelayName 2>&1 | Out-Null }
  Invoke-Native { & $codemie proxy stop 2>&1 | Out-Null }
  Write-Host 'Stopped: tunnel, bridge, relay container and CodeMie proxy.'
  return
}

$cliVersion = [version](((Invoke-Native { & $codemie --version 2>&1 }) | Select-String -Pattern '\d+\.\d+\.\d+' | Select-Object -First 1).Matches[0].Value)
if ($cliVersion -lt $MinCliVersion) {
  throw "CodeMie CLI $cliVersion is too old for the gateway (needs $MinCliVersion or newer): codemie self-update"
}

# --- 1. CodeMie proxy daemon and its SSO session ----------------------------------------
# `codemie profile status` exits 0 even when there is no usable session, so its text is judged.
# Anything it prints that is not a known failure counts as signed in, so an unfamiliar wording
# never forces a sign-in. Returns the failure text, or $null when signed in.
function Get-SsoFailure {
  $text = ((Invoke-Native { & $codemie profile status 2>&1 }) | Out-String)
  if ($text -match 'Authentication required|No SSO credentials|not authenticated|(session|credentials?|token)s? (has |have |is |are )?expired') { return $text }
  $null
}

# Opens the browser sign-in (the CLI does it) for the URL the status text asks for.
function Invoke-SsoLogin {
  param([string]$StatusText)
  $url = [regex]::Match($StatusText, 'profile login --url (\S+)')
  Write-Host 'CodeMie SSO session is missing or expired: opening the browser sign-in.'
  if ($url.Success) { Invoke-Native { & $codemie profile login --url $url.Groups[1].Value } }
  else { Invoke-Native { & $codemie profile login } }
  if ($LASTEXITCODE -ne 0) { throw 'CodeMie SSO login failed' }
  if (Get-SsoFailure) { throw 'CodeMie still reports no usable SSO session after the sign-in' }
}

# A proxy daemon that is still listening after its session died fails every request, and its status
# file may still say "ok" (it is only rewritten on a probe), so the session is checked directly.
$failure = Get-SsoFailure
if ($failure) { Invoke-SsoLogin $failure }
$daemon = Get-Daemon
$expired = [bool]$failure -or ((Test-Loopback $ProxyPort) -and $daemon -and ($daemon.health -eq 'unhealthy'))
$proxyRestarted = $false
if (-not (Test-Loopback $ProxyPort) -or $expired) {
  if ($expired -and $daemon -and $daemon.health -eq 'unhealthy') { Write-Host "CodeMie proxy session expired: $($daemon.healthReason)" }
  Write-Host 'Starting the CodeMie proxy.'
  Invoke-Native { & $codemie proxy stop 2>&1 | Out-Null }
  Invoke-Native { & $codemie proxy start 2>&1 | Out-Null }
  if ($LASTEXITCODE -ne 0) {
    Invoke-SsoLogin ''
    Invoke-Native { & $codemie proxy start 2>&1 | Out-Null }
    if ($LASTEXITCODE -ne 0) { throw 'CodeMie proxy failed to start' }
  }
  if (-not (Wait-Until { Test-Loopback $ProxyPort } 25)) { throw "CodeMie proxy never opened $ProxyPort" }
  $proxyRestarted = $true
}
Write-Host "proxy ${ProxyPort}: listening"

# --- 2. client key and bridge.js ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path $State, $logDir | Out-Null
if (-not (Test-Path -LiteralPath $keyFile)) {
  $bytes = New-Object byte[] 32
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  $generated = 'flowdk_' + [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
  [IO.File]::WriteAllText($keyFile, $generated, (New-Object Text.UTF8Encoding($false)))
  Write-Host "Generated a client key in $keyFile"
}
$clientKey = (Get-Content -LiteralPath $keyFile -Raw).Trim()

# A new proxy daemon has a new key, which the running bridge does not know.
if ($proxyRestarted) {
  Stop-Bridge
  # The old listener can still accept for a moment after the kill; starting before it is gone would skip the new bridge.
  if (-not (Wait-Until { -not (Test-Loopback $BridgePort) } 15)) { throw "The old bridge is still listening on $BridgePort" }
}
if (-not (Test-Loopback $BridgePort)) {
  $daemon = Get-Daemon
  if (-not $daemon) { throw "No proxy daemon file at $daemonFile" }
  $env:BRIDGE_HOST = '127.0.0.1'
  $env:BRIDGE_PORT = "$BridgePort"
  $env:BRIDGE_UPSTREAM_HOST = '127.0.0.1'
  $env:BRIDGE_UPSTREAM_PORT = "$($daemon.port)"
  $env:BRIDGE_CLIENT_KEY = $clientKey
  $env:BRIDGE_UPSTREAM_KEY = $daemon.gatewayKey
  foreach ($name in 'BRIDGE_CLIENT_KEY', 'BRIDGE_UPSTREAM_KEY') {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "$name is empty" }
  }
  Start-Process -FilePath 'node' -ArgumentList ('"' + (Join-Path $PSScriptRoot 'bridge.js') + '"') `
    -RedirectStandardOutput (Join-Path $logDir 'bridge.out.log') `
    -RedirectStandardError (Join-Path $logDir 'bridge.err.log') -WindowStyle Hidden
  Remove-Item Env:BRIDGE_CLIENT_KEY, Env:BRIDGE_UPSTREAM_KEY
  if (-not (Wait-Until { Test-Loopback $BridgePort } 15)) { throw "bridge.js did not open $BridgePort; see $logDir" }
}
Write-Host "bridge ${BridgePort} (Windows): listening"

# --- 3. SSH remote forward into the Podman VM -----------------------------------------------
# The VM's sshd has GatewayPorts off, so the forward binds the VM's loopback only; the relay
# below makes it reachable from the default container network.
function Test-VmListener {
  param([int]$Port)
  [bool]((Invoke-Native { podman machine ssh "ss -ltn 2>/dev/null | grep -c '127.0.0.1:$Port'" }) -match '^[1-9]')
}
if (-not (Test-VmListener $BridgePort)) {
  $connection = (Invoke-Native { podman system connection list --format json } | ConvertFrom-Json) |
    Where-Object { $_.Name -notlike '*-root' } | Select-Object -First 1
  if (-not $connection) { throw 'No Podman machine connection found' }
  if ($connection.URI -notmatch '^ssh://([^@]+)@([^:]+):(\d+)') { throw 'Cannot read the Podman connection URI' }
  $sshUser, $sshHost, $sshPort = $Matches[1], $Matches[2], $Matches[3]
  Start-Process -FilePath 'ssh' -WindowStyle Hidden -ArgumentList @(
    '-N', '-i', "`"$($connection.Identity)`"", '-p', $sshPort,
    # Same host-key handling as Podman's own machine connections: the target is the local VM,
    # and a rebuilt machine gets a new host key that a known_hosts entry would reject.
    '-o', 'ExitOnForwardFailure=yes', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=NUL',
    '-o', 'LogLevel=ERROR',
    '-R', "127.0.0.1:${BridgePort}:127.0.0.1:${BridgePort}", "$sshUser@$sshHost")
  if (-not (Wait-Until { Test-VmListener $BridgePort } 45)) { throw "The VM has nothing listening on 127.0.0.1:$BridgePort" }
}
Write-Host "bridge ${BridgePort} (Podman VM): listening"

# --- 4. relay container in the VM ----------------------------------------------------------
$relayState = (Invoke-Native { podman inspect $RelayName --format '{{.State.Status}}' 2>$null }) | Select-Object -First 1
if ($relayState -ne 'running') {
  Invoke-Native { podman rm -f $RelayName 2>&1 | Out-Null }
  Invoke-Native {
    podman run -d --name $RelayName --restart=unless-stopped --network=host $RelayImage `
      "TCP-LISTEN:${RelayPort},bind=0.0.0.0,fork,reuseaddr" "TCP:127.0.0.1:${BridgePort}" 2>&1 | Out-Null
  }
  if ($LASTEXITCODE -ne 0) { throw 'Could not start the relay container' }
}
Write-Host "relay ${RelayPort} (Podman VM): running"

# --- 5. end-to-end smoke check ----------------------------------------------------------------
$code = (curl.exe -s -o NUL -w '%{http_code}' "http://127.0.0.1:${BridgePort}/")
if ($code -ne '401') { throw "The bridge answered HTTP $code without a key; expected 401" }
# The link that matters: a container on the default network, as the coding workloads use,
# must reach the relay. The relay image already carries socat, so no other image is needed.
$probe = "printf 'GET / HTTP/1.0\r\n\r\n' | socat -t5 - TCP:host.docker.internal:${RelayPort}"
$answer = (Invoke-Native { podman run --rm --entrypoint sh $RelayImage -c $probe 2>&1 }) -join "`n"
if ($answer -notmatch '^HTTP/1\.[01] 401') {
  throw "A default-network container could not reach the bridge through host.docker.internal:${RelayPort} (expected HTTP 401)"
}
Write-Host 'Upstream path is up: a default-network container reaches the bridge, which refuses a request without a key (401).'

# --- 6. Factory settings --------------------------------------------------------------------------
$baseUrl = "http://host.docker.internal:${RelayPort}/v1"
if ($UpdateEnv) {
  $lines = @(if (Test-Path -LiteralPath $UpdateEnv) { Get-Content -LiteralPath $UpdateEnv })
  foreach ($pair in @(@('FACTORY_PI_BASE_URL', $baseUrl), @('FACTORY_PI_API_KEY', $clientKey))) {
    $line = "$($pair[0])=$($pair[1])"
    $index = [array]::FindIndex([string[]]$lines, [Predicate[string]]{ param($l) $l -match "^$($pair[0])=" })
    if ($index -ge 0) { $lines[$index] = $line } else { $lines += $line }
  }
  [IO.File]::WriteAllText($UpdateEnv, (($lines -join "`r`n") + "`r`n"), (New-Object Text.UTF8Encoding($false)))
  Write-Host "Updated FACTORY_PI_BASE_URL and FACTORY_PI_API_KEY in $UpdateEnv"
} else {
  Write-Host "Factory .env: FACTORY_PI_BASE_URL=$baseUrl"
  Write-Host "              FACTORY_PI_API_KEY = the content of $keyFile (re-run with -UpdateEnv <path-to-.env> to write it)"
}
