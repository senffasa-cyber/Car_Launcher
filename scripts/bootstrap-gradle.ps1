# Creates the Gradle wrapper for this project without Android Studio (Windows / PowerShell).
#
# Mirrors scripts/bootstrap-gradle.sh. Run from the repository root:
#   powershell -ExecutionPolicy Bypass -File scripts\bootstrap-gradle.ps1
#
# Needs JDK 17 on PATH (Android Studio's embedded JDK works: "C:\Program Files\Android\Android Studio\jbr").

# Note: `gradle wrapper` runs Gradle's configuration phase, so the Android Gradle plugin has to resolve
# once. Have internet available, or do one Android Studio "Sync" first — that fills %USERPROFILE%\.gradle
# and writes gradlew.bat for you, in which case this script is unnecessary.

$ErrorActionPreference = 'Stop'
$GradleVersion = if ($env:GRADLE_VERSION) { $env:GRADLE_VERSION } else { '8.2' }
$Root = Split-Path -Parent $PSScriptRoot
$Cache = Join-Path $env:USERPROFILE ".gradle\bootstrap\gradle-$GradleVersion"

if (-not (Test-Path $Cache)) {
  Write-Host "==> downloading Gradle $GradleVersion"
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Cache) | Out-Null
  $Zip = "$Cache.zip"
  $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  (New-Object Net.WebClient).DownloadFile($Url, $Zip)
  Expand-Archive -Path $Zip -DestinationPath "$Cache-tmp" -Force
  Move-Item -Path "$Cache-tmp\gradle-$GradleVersion" -Destination $Cache -Force
  Remove-Item -Recurse -Force "$Cache-tmp", $Zip
}

$GradleBat = Join-Path $Cache 'bin\gradle.bat'
if (-not (Test-Path $GradleBat)) { throw "gradle launcher not found at $GradleBat" }

Push-Location $Root
try {
  Write-Host "==> generating the wrapper in $Root"
  & $GradleBat --no-daemon wrapper --gradle-version $GradleVersion --distribution-type bin
  if ($LASTEXITCODE -ne 0) { throw "gradle wrapper failed ($LASTEXITCODE)" }
  Write-Host ""
  Write-Host "done. now build with:"
  Write-Host "  .\gradlew.bat assembleRelease"
  Write-Host "APK lands in app\build\outputs\apk\release\app-release.apk"
} finally {
  Pop-Location
}
