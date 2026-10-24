param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

# Optional local overrides. Empty values use MIGRATE_JAVA_HOME, JAVA_HOME, then PATH.
$CustomJavaHome = ''
$JvmOptions = @()

$ErrorActionPreference = 'Stop'
$AppDirectory = [IO.Path]::GetFullPath($PSScriptRoot)
$LogDirectory = Join-Path $AppDirectory 'log'
$StateFile = Join-Path $LogDirectory '.configs-migrate-launcher.json'
$LauncherMarker = "-Dconfigs.migrate.launcher=$AppDirectory"

# Test whether console key input is available without blocking redirected invocations.
function Test-InteractiveConsole {
    try { return -not ([Console]::IsInputRedirected -or [Console]::IsOutputRedirected) }
    catch { return $false }
}

# Keep navigation independent from the countdown so key presses never reset the deadline.
function Get-MenuSelection([int]$Current, [string]$Key) {
    switch ($Key) {
        'UpArrow' { return ($Current + 2) % 3 }
        'DownArrow' { return ($Current + 1) % 3 }
        { $_ -in 'D1', 'NumPad1' } { return 0 }
        { $_ -in 'D2', 'NumPad2' } { return 1 }
        { $_ -in 'D3', 'NumPad3' } { return 2 }
        default { return $Current }
    }
}

# Present the no-command Windows menu and execute the current selection after five seconds.
function Select-LauncherAction {
    if (-not (Test-InteractiveConsole)) { return 'restart' }
    $actions = @('restart', 'stop', 'start')
    $selected = 0
    $foreground = [Console]::ForegroundColor
    $background = [Console]::BackgroundColor
    $cursorVisible = [Console]::CursorVisible
    Write-Host 'Select an action with Up/Down or 1/2/3. Enter runs it immediately.'
    Write-Host 'Navigation does not interrupt the five-second countdown.'
    1..4 | ForEach-Object { Write-Host '' }
    $top = [Console]::CursorTop - 4
    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        [Console]::CursorVisible = $false
        $lastSelection = -1
        $lastSeconds = -1
        while ($timer.Elapsed.TotalSeconds -lt 5) {
            $seconds = [int][Math]::Ceiling(5 - $timer.Elapsed.TotalSeconds)
            if ($lastSelection -ne $selected -or $lastSeconds -ne $seconds) {
                $width = [Math]::Max(1, [Math]::Min(76, [Console]::BufferWidth - 1))
                for ($index = 0; $index -lt $actions.Count; $index++) {
                    [Console]::SetCursorPosition(0, $top + $index)
                    [Console]::ForegroundColor = $foreground
                    [Console]::BackgroundColor = $background
                    $marker = '  '
                    if ($index -eq $selected) {
                        $marker = '> '
                        [Console]::ForegroundColor = [ConsoleColor]::Black
                        [Console]::BackgroundColor = [ConsoleColor]::Cyan
                    }
                    [Console]::Write(("$marker$($index + 1). $($actions[$index])").PadRight($width).Substring(0, $width))
                }
                [Console]::ForegroundColor = $foreground
                [Console]::BackgroundColor = $background
                [Console]::SetCursorPosition(0, $top + 3)
                [Console]::Write(("Running selected action in $seconds second(s)...").PadRight($width).Substring(0, $width))
                $lastSelection = $selected
                $lastSeconds = $seconds
            }
            if ([Console]::KeyAvailable) {
                $key = [Console]::ReadKey($true).Key.ToString()
                if ($timer.Elapsed.TotalSeconds -ge 5) { break }
                if ($key -eq 'Enter') { break }
                $selected = Get-MenuSelection $selected $key
            }
            Start-Sleep -Milliseconds 40
        }
        # Use the cursor's final selection, not a hard-coded restart fallback.
        return $actions[$selected]
    }
    finally {
        $timer.Stop()
        [Console]::ForegroundColor = $foreground
        [Console]::BackgroundColor = $background
        [Console]::CursorVisible = $cursorVisible
        [Console]::SetCursorPosition(0, $top + 4)
    }
}

# Leave failures visible until a key is pressed; successful launches close after at most five seconds.
function Wait-LauncherExit([switch]$Failure, [switch]$ServiceRunning) {
    if ($Failure) { Write-Host 'Press any key to exit...' }
    else {
        Write-Host 'Press any key to close this launcher, or wait 5 seconds.'
        if ($ServiceRunning) { Write-Host 'The service stays running after this launcher closes.' }
    }
    if (-not (Test-InteractiveConsole)) { return }
    if ($Failure) { [Console]::ReadKey($true) | Out-Null; return }
    # Discard keys left over from menu navigation before starting the independent exit countdown.
    while ([Console]::KeyAvailable) { [Console]::ReadKey($true) | Out-Null }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt 5) {
        if ([Console]::KeyAvailable) { [Console]::ReadKey($true) | Out-Null; return }
        Start-Sleep -Milliseconds 50
    }
}

# Quote each Windows argument using the CRT backslash/quote rules, including paths with spaces.
function ConvertTo-WindowsArgument([string]$Value) {
    return '"' + [regex]::Replace([regex]::Replace($Value, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
}

# Read a validated timeout without changing any machine or user environment variables.
function Get-TimeoutSeconds([string]$Name, [int]$Default) {
    $text = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($text)) { return $Default }
    $value = 0
    if (-not [int]::TryParse($text, [ref]$value) -or $value -lt 1) { throw "$Name must be a positive integer." }
    return $value
}

# Select the JDK for this launch only; stop does not require Java to be installed.
function Resolve-LauncherJava([string]$RequestedHome) {
    $jdkDirectory = $RequestedHome
    if (-not $jdkDirectory) { $jdkDirectory = $CustomJavaHome }
    if (-not $jdkDirectory) { $jdkDirectory = $env:MIGRATE_JAVA_HOME }
    if (-not $jdkDirectory) { $jdkDirectory = $env:JAVA_HOME }
    if ($jdkDirectory) {
        $executable = Join-Path $jdkDirectory 'bin/java.exe'
        if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw "Java executable not found: $executable" }
    } else {
        $executable = (Get-Command java.exe -ErrorAction Stop).Source
    }
    # Java writes its version to stderr; read both streams directly to support Windows PowerShell 5.1.
    $probe = [Diagnostics.Process]::new()
    try {
        $probe.StartInfo.FileName = $executable
        $probe.StartInfo.Arguments = '-version'
        $probe.StartInfo.UseShellExecute = $false
        $probe.StartInfo.CreateNoWindow = $true
        $probe.StartInfo.RedirectStandardOutput = $true
        $probe.StartInfo.RedirectStandardError = $true
        $null = $probe.Start()
        $stdout = $probe.StandardOutput.ReadToEndAsync()
        $stderr = $probe.StandardError.ReadToEndAsync()
        if (-not $probe.WaitForExit(10000)) { $probe.Kill(); throw 'Java version detection timed out.' }
        $versionOutput = $stdout.GetAwaiter().GetResult() + "`n" + $stderr.GetAwaiter().GetResult()
        if ($probe.ExitCode -ne 0 -or $versionOutput -notmatch 'version\s+"?(\d+)') { throw "Cannot determine Java version: $executable" }
    } finally { $probe.Dispose() }
    if ([int]$Matches[1] -lt 25) { throw "Java 25 or newer is required. Selected executable: $executable" }
    return [IO.Path]::GetFullPath($executable)
}

# Require one colocated application JAR unless MIGRATE_JAR explicitly chooses its filename.
function Resolve-LauncherJar {
    if ($env:MIGRATE_JAR) {
        $candidate = $env:MIGRATE_JAR
        if (-not [IO.Path]::IsPathRooted($candidate)) { $candidate = Join-Path $AppDirectory $candidate }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "JAR not found: $candidate" }
        $resolved = (Resolve-Path -LiteralPath $candidate).Path
        if ((Split-Path -Parent $resolved) -ne $AppDirectory) { throw 'MIGRATE_JAR must select a JAR in the launcher directory.' }
        return $resolved
    }
    $candidates = @(Get-ChildItem -LiteralPath $AppDirectory -Filter 'configs-migrate-mcp*.jar' -File)
    if ($candidates.Count -ne 1) { throw 'Place exactly one configs-migrate-mcp*.jar beside this launcher, or set MIGRATE_JAR to its filename.' }
    return $candidates[0].FullName
}

# Identify only Java processes bearing this directory's exact launcher marker, never an arbitrary saved PID.
function Get-ManagedProcess {
    $quotedMarker = '"' + $LauncherMarker + '"'
    $plainMarker = '(?:^|\s)' + [regex]::Escape($LauncherMarker) + '(?=\s|$)'
    $ownedProcesses = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object {
        $_.CommandLine -and ($_.CommandLine.IndexOf($quotedMarker, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or $_.CommandLine -match $plainMarker)
    })
    if ($ownedProcesses.Count -gt 1) { throw "Multiple managed processes found: $($ownedProcesses.ProcessId -join ', '). Resolve them before proceeding." }
    if ($ownedProcesses.Count -eq 1) { return $ownedProcesses[0] }
    return $null
}

# Use netstat so port diagnostics also work without the NetTCPIP PowerShell module.
function Get-LauncherListeners([int]$Port = 0, [int]$OwnerId = 0) {
    $rows = @(netstat.exe -ano 2>$null)
    foreach ($row in $rows) {
        if ($row -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
            $listenerPort = [int]$Matches[1]
            $listenerOwner = [int]$Matches[2]
            if (($Port -eq 0 -or $listenerPort -eq $Port) -and ($OwnerId -eq 0 -or $listenerOwner -eq $OwnerId)) {
                [PSCustomObject]@{ Port = $listenerPort; OwnerId = $listenerOwner }
            }
        }
    }
}

# Report conflict ownership without attempting to terminate an unrelated service.
function Show-LauncherPortConflict([int]$Port) {
    Write-Host "Port conflict: $Port" -ForegroundColor Red
    $listeners = @(Get-LauncherListeners -Port $Port | Sort-Object OwnerId -Unique)
    if ($listeners.Count -eq 0) { Write-Host 'Owner PID, name and executable path: unavailable (listener exited or access denied).' }
    foreach ($listener in $listeners) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwnerId)" -ErrorAction SilentlyContinue
        $name = if ($owner) { $owner.Name } else { 'unavailable (access denied or process exited)' }
        $path = if ($owner -and $owner.ExecutablePath) { $owner.ExecutablePath } else { 'unavailable (access denied or process exited)' }
        Write-Host "Owner PID: $($listener.OwnerId)"
        Write-Host "Process name: $name"
        Write-Host "Executable path: $path"
    }
}

# Inspect Spring Boot's actual failure report, honoring any YAML, environment, profile or CLI port override.
function Show-LauncherStartupFailure([string]$OutputLog, [string]$ErrorLog) {
    $text = ((Get-Content -LiteralPath $OutputLog -Raw -ErrorAction SilentlyContinue),
             (Get-Content -LiteralPath $ErrorLog -Raw -ErrorAction SilentlyContinue)) -join "`n"
    $conflict = [regex]::Match($text, 'CONFIGS_MIGRATE_PORT_CONFLICT port=(\d+)')
    if (-not $conflict.Success) { $conflict = [regex]::Match($text, '(?i)Port\s+(\d+)\s+(?:was already|is already)\s+in use') }
    if ($conflict.Success) { Show-LauncherPortConflict ([int]$conflict.Groups[1].Value) }
}

# Stop only this directory's managed service; a missing process is a normal first-run condition.
function Stop-LauncherService([int]$Timeout) {
    $running = Get-ManagedProcess
    if (-not $running) {
        Remove-Item -LiteralPath $StateFile -ErrorAction SilentlyContinue
        Write-Host 'The service is not running. Continuing without error.'
        return
    }
    $serviceId = [int]$running.ProcessId
    Write-Host "Stopping service PID $serviceId..."
    Stop-Process -Id $serviceId -ErrorAction SilentlyContinue
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $Timeout) {
        if (-not (Get-Process -Id $serviceId -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $StateFile -ErrorAction SilentlyContinue
            Write-Host "Service stopped (PID $serviceId)."
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw "Service PID $serviceId did not stop within $Timeout seconds. Start was not attempted."
}

# Detach Java and wait for both application readiness and a listening socket owned by the launched process.
function Start-LauncherService([string]$Java, [string]$Jar, [string[]]$ApplicationArguments, [int]$Timeout) {
    $running = Get-ManagedProcess
    if ($running) {
        $ports = @(Get-LauncherListeners -OwnerId $running.ProcessId | Select-Object -ExpandProperty Port -Unique)
        Write-Host "The service is already running. PID: $($running.ProcessId); service port(s): $($ports -join ', '); log directory: $LogDirectory"
        return
    }
    New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
    $stamp = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
    $outputLog = Join-Path $LogDirectory "configs-migrate-$stamp.out.log"
    $errorLog = Join-Path $LogDirectory "configs-migrate-$stamp.err.log"
    $configUri = ([Uri](Join-Path $AppDirectory 'application.yml')).AbsoluteUri
    # Business overrides retain their precedence; background launchers always own an HTTP process.
    $javaArguments = @($JvmOptions) + @($LauncherMarker, '-Dspring.output.ansi.enabled=never', '-jar', $Jar,
        "--spring.config.location=$configUri") + @($ApplicationArguments) + @('--spring.ai.mcp.server.stdio=false')
    $commandLine = ($javaArguments | ForEach-Object { ConvertTo-WindowsArgument $_ }) -join ' '
    Write-Host "Java: $Java"
    Write-Host "Starting: $Jar"
    # A separate hidden process with redirected handles survives closure of this launcher's console.
    $launched = Start-Process -FilePath $Java -ArgumentList $commandLine -WorkingDirectory $AppDirectory `
        -WindowStyle Hidden -RedirectStandardOutput $outputLog -RedirectStandardError $errorLog -PassThru
    @{ pid = $launched.Id; jar = $Jar; outputLog = $outputLog; errorLog = $errorLog } |
        ConvertTo-Json | Set-Content -LiteralPath $StateFile -Encoding UTF8
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $Timeout) {
        $launched.Refresh()
        if ($launched.HasExited) {
            Remove-Item -LiteralPath $StateFile -ErrorAction SilentlyContinue
            Show-LauncherStartupFailure $outputLog $errorLog
            throw "Startup failed. Logs: $outputLog ; $errorLog"
        }
        $text = Get-Content -LiteralPath $outputLog -Raw -ErrorAction SilentlyContinue
        if ($text) {
            # Prefer the application-ready signal; retain compatibility with the standard Boot log format.
            $portMatch = [regex]::Match($text, "CONFIGS_MIGRATE_READY pid=$($launched.Id) port=(\d+)")
            if (-not $portMatch.Success -and $text -match 'Started\s+\S+\s+in\s+[\d.]+\s+seconds') {
                $portMatch = [regex]::Match($text, 'Tomcat started on port(?:\(s\))?:?\s+(\d+)')
            }
            if ($portMatch.Success) {
                $servicePort = [int]$portMatch.Groups[1].Value
                if (@(Get-LauncherListeners -Port $servicePort -OwnerId $launched.Id).Count -gt 0) {
                    Write-Host 'Service started successfully.' -ForegroundColor Green
                    Write-Host "PID: $($launched.Id)"
                    Write-Host "Service port: $servicePort"
                    Write-Host "Output log: $outputLog"
                    Write-Host "Error log: $errorLog"
                    return
                }
            }
        }
        Start-Sleep -Milliseconds 250
    }
    # Preserve process identity on timeout so stop/restart can recover a slow or failed startup.
    Show-LauncherStartupFailure $outputLog $errorLog
    throw "Readiness was not reached within $Timeout seconds (PID $($launched.Id) retained). Logs: $outputLog ; $errorLog"
}

# Parse launcher options while preserving Spring Boot arguments as individual, unmodified values.
function Invoke-ConfigsMigrateLauncher([string[]]$InputArguments) {
    $mutex = $null
    $ownsMutex = $false
    try {
        $action = $null
        $requestedJavaHome = ''
        $applicationArguments = @()
        foreach ($argument in $InputArguments) {
            if ($argument -in 'start', 'stop', 'restart' -and -not $action) { $action = $argument; continue }
            if ($argument.StartsWith('--java-home=')) { $requestedJavaHome = $argument.Substring(12); continue }
            # Reject stdio before restart can stop a healthy HTTP service.
            if ($argument -match '^--spring\.ai\.mcp\.server\.stdio(?:=|$)' -and
                $argument -ine '--spring.ai.mcp.server.stdio=false') {
                throw 'These scripts manage HTTP only. Launch Java directly with --spring.ai.mcp.server.stdio=true from the MCP client.'
            }
            if ($argument -ieq '--spring.ai.mcp.server.stdio=false') { continue }
            if ($argument.StartsWith('--')) { $applicationArguments += $argument; continue }
            throw "Unknown argument: $argument. Use start, stop or restart, plus --key=value overrides."
        }
        if (-not $action) {
            $action = if (-not $InputArguments) { Select-LauncherAction } else { 'restart' }
        }
        Write-Host "Action: $action"
        # Serialize operations for this directory without blocking independently deployed instances.
        $hash = [Security.Cryptography.SHA256]::Create()
        try { $mutexId = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($AppDirectory.ToLowerInvariant())))).Replace('-', '') }
        finally { $hash.Dispose() }
        $mutex = [Threading.Mutex]::new($false, "Local\ConfigsMigrate-$mutexId")
        try { $ownsMutex = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $ownsMutex = $true }
        if (-not $ownsMutex) { throw 'Another launcher operation is already running for this directory.' }
        $stopTimeout = Get-TimeoutSeconds 'MIGRATE_STOP_TIMEOUT' 30
        if ($action -eq 'stop') {
            Stop-LauncherService $stopTimeout
        } else {
            # Validate replacement files and Java before stopping a healthy service during restart.
            $jar = Resolve-LauncherJar
            if (-not (Test-Path -LiteralPath (Join-Path $AppDirectory 'application.yml') -PathType Leaf)) { throw "application.yml is missing from $AppDirectory" }
            $java = Resolve-LauncherJava $requestedJavaHome
            $startTimeout = Get-TimeoutSeconds 'MIGRATE_START_TIMEOUT' 60
            if ($action -eq 'restart') { Stop-LauncherService $stopTimeout }
            Start-LauncherService $java $jar $applicationArguments $startTimeout
        }
    } catch {
        Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
        Wait-LauncherExit -Failure
        return 1
    } finally {
        if ($ownsMutex) { $mutex.ReleaseMutex() }
        if ($mutex) { $mutex.Dispose() }
    }
    Wait-LauncherExit -ServiceRunning:($action -ne 'stop')
    return 0
}

# Dot-sourcing exposes helpers for focused validation without starting or stopping a service.
if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-ConfigsMigrateLauncher $Arguments) }
