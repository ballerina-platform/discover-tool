# Installs the prebuilt bal discover tool from a released distribution zip.
# Run from the unzipped release directory:
#   Expand-Archive bal-discover-tool-<version>.zip -DestinationPath .
#   Set-Location bal-discover-tool-<version>
#   .\install.ps1
#
# Fully offline — does not invoke gradle, does not contact the network.

$ErrorActionPreference = 'Stop'

$toolId = 'discover'
$org    = 'ballerina'
$name   = 'tool_discover'

$scriptDir = $PSScriptRoot

$versionFile = Join-Path $scriptDir 'VERSION'
if (-not (Test-Path $versionFile)) {
    Write-Error "VERSION file not found next to install.ps1. Run this script from inside the unzipped release directory."
    exit 1
}
$version = (Get-Content $versionFile -Raw).Trim()

$jar  = Join-Path $scriptDir "native-$version.jar"
$toml = Join-Path $scriptDir 'Ballerina.toml'
if (-not (Test-Path $jar) -or -not (Test-Path $toml)) {
    Write-Error "Release artifacts missing (expected $jar and $toml)."
    exit 1
}

if (-not (Get-Command bal -ErrorAction SilentlyContinue)) {
    Write-Error "'bal' not found on PATH. Install Ballerina first: https://ballerina.io/downloads/"
    exit 1
}

$balVersionLine = (& bal version | Select-String -Pattern '^Ballerina').Line
$balVersion = ($balVersionLine -split '\s+')[1]

$balaHome     = Join-Path $env:USERPROFILE '.ballerina\repositories\local\bala'
$toolBala     = Join-Path $balaHome "$org\$name\$version\any"
$toolLibs     = Join-Path $toolBala 'tool\libs'
$balToolsToml = Join-Path $env:USERPROFILE '.ballerina\.config\bal-tools.toml'

Write-Host "==> Cleaning up any prior installation of $org/$name..."
$orgNameDir = Join-Path $balaHome "$org\$name"
if (Test-Path $orgNameDir) {
    Remove-Item -Recurse -Force $orgNameDir
}

Write-Host "==> Installing JAR into $toolLibs ..."
New-Item -ItemType Directory -Force -Path $toolLibs | Out-Null
Copy-Item -Path $jar -Destination $toolLibs

Write-Host "==> Writing package metadata..."
Copy-Item -Path $toml -Destination $toolBala
$packageJson = @"
{
  "organization": "$org",
  "name": "$name",
  "version": "$version",
  "ballerina_version": "$balVersion",
  "platform": "java"
}
"@
Set-Content -Path (Join-Path $toolBala 'package.json') -Value $packageJson -NoNewline

Write-Host "==> Registering in bal-tools.toml..."
$configDir = Split-Path $balToolsToml -Parent
if (-not (Test-Path $configDir)) {
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null
}

if (Test-Path $balToolsToml) {
    $content = Get-Content $balToolsToml -Raw
    $pattern = '\[\[tool\]\][^\[]*id\s*=\s*"' + [regex]::Escape($toolId) + '"[^\[]*'
    $content = [regex]::Replace($content, $pattern, '')
    $content = $content.TrimEnd() + "`n"
    Set-Content -Path $balToolsToml -Value $content -NoNewline
}

$toolEntry = @"

[[tool]]
id = "$toolId"
org = "$org"
name = "$name"
version = "$version"
repository = "local"
active = true
"@
Add-Content -Path $balToolsToml -Value $toolEntry

Write-Host ""
Write-Host "Installed $org/$name`:$version."
Write-Host "Try:"
Write-Host "  bal discover --help"
Write-Host "  bal discover find kafka messaging"
Write-Host "  bal discover overview ballerinax/kafka"
