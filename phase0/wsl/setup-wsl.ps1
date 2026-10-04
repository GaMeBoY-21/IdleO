#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Phase 0: prepare the dedicated WSL2 distribution for GPU containers.

.DESCRIPTION
    Checks the Windows build and that the NVIDIA Windows driver supports WSL,
    installs or updates WSL, and creates an Ubuntu 24.04 distribution named
    gpu-host-dev from Canonical's WSL image (checksum verified).

    Safe to run again: each step checks before it acts. It never stops,
    changes or removes any other distribution, never runs "wsl --shutdown",
    and never changes the default distribution.

    Run from an admin PowerShell:
        powershell -ExecutionPolicy Bypass -File .\setup-wsl.ps1
#>
[CmdletBinding()]
param(
    [string]$DistroName = 'gpu-host-dev',
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'WSL\gpu-host-dev'),
    [string]$ImageBaseUrl = 'https://cloud-images.ubuntu.com/wsl/releases/24.04/current',
    [string]$ImageName = 'ubuntu-noble-wsl-amd64-wsl.rootfs.tar.gz',
    # The CUDA 12.8 container used by setup-docker-nvidia.sh needs a driver
    # that reports at least this CUDA version.
    [version]$MinCudaVersion = '12.8'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$env:WSL_UTF8 = '1'   # make wsl.exe print UTF-8 instead of UTF-16
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Step([string]$Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok([string]$Message) { Write-Host "    ok: $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "    warning: $Message" -ForegroundColor Yellow }
function Stop-Setup([string]$Message) {
    Write-Host "    FAILED: $Message" -ForegroundColor Red
    exit 1
}

# Runs a native program and returns its exit code and output as text, without
# letting stderr output turn into a terminating PowerShell error.
function Invoke-Native([string]$Exe, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $text = (($lines | Out-String) -replace "`0", '').Trim()
    return [pscustomobject]@{ Code = $code; Output = $text }
}

function Get-WslDistros {
    $result = Invoke-Native 'wsl.exe' @('--list', '--quiet')
    if ($result.Code -ne 0) { return @() }
    return @($result.Output -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function ConvertTo-WslPath([string]$WindowsPath) {
    $full = [IO.Path]::GetFullPath($WindowsPath)
    $drive = $full.Substring(0, 1).ToLowerInvariant()
    return '/mnt/' + $drive + ($full.Substring(2) -replace '\\', '/')
}

# 1. Windows build -----------------------------------------------------------
Write-Step 'Checking the Windows build'
$build = [int](Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion').CurrentBuildNumber
if ($build -ge 22000) {
    Write-Ok "Windows 11, build $build"
} elseif ($build -ge 19044) {
    Write-Warn "Windows 10 build $build. GPU in WSL works, but Phase 0 is planned on Windows 11."
} else {
    Stop-Setup "Windows build $build is too old for GPU support in WSL. Update to Windows 11 (build 22000 or later)."
}

# 2. NVIDIA Windows driver ---------------------------------------------------
Write-Step 'Checking the NVIDIA Windows driver'
$smi = Get-Command 'nvidia-smi.exe' -ErrorAction SilentlyContinue
if (-not $smi) {
    $candidate = Join-Path $env:SystemRoot 'System32\nvidia-smi.exe'
    if (Test-Path $candidate) { $smi = Get-Item $candidate }
}
if (-not $smi) {
    Stop-Setup 'nvidia-smi.exe not found. Install the current NVIDIA Game Ready or Studio driver for Windows from nvidia.com, then run this again.'
}
$smiPath = if ($smi -is [System.Management.Automation.CommandInfo]) { $smi.Path } else { $smi.FullName }
$gpu = Invoke-Native $smiPath @('--query-gpu=name,driver_version', '--format=csv,noheader')
if ($gpu.Code -ne 0 -or -not $gpu.Output) {
    Stop-Setup "nvidia-smi failed: $($gpu.Output)"
}
Write-Ok "GPU and driver: $($gpu.Output)"
$header = Invoke-Native $smiPath @()
if ($header.Output -match 'CUDA Version:\s*([0-9]+\.[0-9]+)') {
    $cuda = [version]$Matches[1]
    if ($cuda -lt $MinCudaVersion) {
        Stop-Setup "The driver supports CUDA $cuda; CUDA $MinCudaVersion or later is needed. Update the NVIDIA Windows driver."
    }
    Write-Ok "Driver supports CUDA $cuda"
} else {
    Stop-Setup 'Could not read the CUDA version from nvidia-smi. Update the NVIDIA Windows driver.'
}
# The driver's Linux libraries for WSL live here and appear as /usr/lib/wsl/lib
# inside every distribution. setup-docker-nvidia.sh checks the Linux side.
$wslLib = Join-Path $env:SystemRoot 'System32\lxss\lib\libcuda.so'
if (Test-Path $wslLib) {
    Write-Ok 'Driver ships its WSL libraries (System32\lxss\lib)'
} else {
    Write-Warn 'System32\lxss\lib\libcuda.so not found. If the GPU check inside WSL fails later, reinstall the NVIDIA driver.'
}

# 3. WSL itself ---------------------------------------------------------------
Write-Step 'Checking WSL'
$version = Invoke-Native 'wsl.exe' @('--version')
if ($version.Code -ne 0) {
    Write-Host '    WSL is missing or is the old built-in version; installing the current one.'
    $install = Invoke-Native 'wsl.exe' @('--install', '--no-distribution')
    if ($install.Code -ne 0) { Stop-Setup "wsl --install failed: $($install.Output)" }
    Write-Host ''
    Write-Host 'WSL is installed. Restart Windows, then run this script again.' -ForegroundColor Yellow
    exit 0
}
$update = Invoke-Native 'wsl.exe' @('--update')
if ($update.Code -ne 0) { Stop-Setup "wsl --update failed: $($update.Output)" }
$version = Invoke-Native 'wsl.exe' @('--version')
Write-Ok (($version.Output -split "`r?`n" | Select-Object -First 1))

# 4. The gpu-host-dev distribution -------------------------------------------
Write-Step "Checking the $DistroName distribution"
if ((Get-WslDistros) -contains $DistroName) {
    Write-Ok "$DistroName already exists; leaving it as it is"
} else {
    if ((Test-Path $InstallDir) -and (Get-ChildItem $InstallDir -Force | Select-Object -First 1)) {
        Stop-Setup "$InstallDir is not empty but $DistroName is not registered. Move that folder away, then run this again."
    }
    $download = Join-Path $env:TEMP $ImageName
    Write-Host "    Downloading $ImageName (several hundred MB)"
    Invoke-WebRequest -UseBasicParsing -Uri "$ImageBaseUrl/$ImageName" -OutFile $download
    $sums = (Invoke-WebRequest -UseBasicParsing -Uri "$ImageBaseUrl/SHA256SUMS").Content
    if ($sums -is [byte[]]) { $sums = [Text.Encoding]::ASCII.GetString($sums) }
    $expected = $null
    foreach ($line in ($sums -split "`n")) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -eq 2 -and $parts[1].TrimStart('*') -eq $ImageName) { $expected = $parts[0].ToLowerInvariant() }
    }
    if (-not $expected) { Stop-Setup "No checksum for $ImageName in SHA256SUMS." }
    $actual = (Get-FileHash -Algorithm SHA256 $download).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        Remove-Item $download -Force
        Stop-Setup "Checksum mismatch for $ImageName (expected $expected, got $actual). Run again to re-download."
    }
    Write-Ok "Checksum verified: $actual"

    New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
    $import = Invoke-Native 'wsl.exe' @('--import', $DistroName, $InstallDir, $download, '--version', '2')
    Remove-Item $download -Force
    if ($import.Code -ne 0) { Stop-Setup "wsl --import failed: $($import.Output)" }
    Write-Ok "Created $DistroName in $InstallDir"
}

# 5. Quick look inside --------------------------------------------------------
Write-Step "Checking the GPU from inside $DistroName"
$os = Invoke-Native 'wsl.exe' @('-d', $DistroName, '-u', 'root', '--', 'sh', '-c', '. /etc/os-release && echo $PRETTY_NAME')
if ($os.Code -ne 0) { Stop-Setup "Could not start $DistroName : $($os.Output)" }
Write-Ok "Running $($os.Output)"
$inside = Invoke-Native 'wsl.exe' @('-d', $DistroName, '-u', 'root', '--', '/usr/lib/wsl/lib/nvidia-smi', '-L')
if ($inside.Code -ne 0) {
    Stop-Setup "nvidia-smi does not work inside $DistroName : $($inside.Output)"
}
Write-Ok "GPU visible in WSL: $($inside.Output)"

# 6. Next steps ---------------------------------------------------------------
$dockerScript = ConvertTo-WslPath (Join-Path $PSScriptRoot 'setup-docker-nvidia.sh')
Write-Host ''
Write-Host 'Done. Next steps:' -ForegroundColor Green
Write-Host "  1. Install Docker Engine and the NVIDIA Container Toolkit inside $DistroName :"
Write-Host "       wsl -d $DistroName -u root -- bash '$dockerScript'"
Write-Host '     The first run turns on systemd and asks you to restart the distribution with:'
Write-Host "       wsl --terminate $DistroName"
Write-Host '     then run the same command again.'
Write-Host '  2. Then follow phase0/README.md: the QLoRA run and resume test, with gpu_logger.py running.'
