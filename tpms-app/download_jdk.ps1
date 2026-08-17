$ErrorActionPreference = "Stop"

# 1. Create jdk folder if it doesn't exist
if (-not (Test-Path "jdk")) {
    New-Item -ItemType Directory -Force -Path "jdk"
}

# 2. Download Microsoft OpenJDK 17
$zipPath = "jdk/jdk17.zip"
if (-not (Test-Path $zipPath)) {
    Write-Host "Downloading Microsoft OpenJDK 17 (~160MB)..."
    # Using progress bar off to speed up Invoke-WebRequest
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri "https://aka.ms/download-jdk/microsoft-jdk-17.0.11-windows-x64.zip" -OutFile $zipPath
}

# 3. Extract JDK
Write-Host "Extracting OpenJDK..."
# Expand-Archive can fail if folder exists, so we clear extraction target if it exists
$extractedDir = Get-ChildItem -Path "jdk" -Directory | Select-Object -First 1
if ($extractedDir) {
    Remove-Item -Recurse -Force $extractedDir.FullName
}
Expand-Archive -Path $zipPath -DestinationPath "jdk"

# 4. Cleanup Zip
Remove-Item $zipPath
Write-Host "JDK successfully downloaded and extracted."
