param([switch]$Package, [switch]$Integration, [string]$JdkHome = $env:JAVA_HOME, [string]$Proxy = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not $JdkHome) {
        $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($javacCommand) { $JdkHome = Split-Path (Split-Path $javacCommand.Source -Parent) -Parent }
        elseif (Test-Path 'C:\Program Files\Java\jdk-21') { $JdkHome = 'C:\Program Files\Java\jdk-21' }
    }
    if (-not $JdkHome -or -not (Test-Path (Join-Path $JdkHome 'bin/javac.exe'))) { throw 'Set JAVA_HOME to JDK 21 or newer.' }
    $env:JAVA_HOME = $JdkHome
    $mavenVersion = '3.9.16'
    $mavenRoot = Join-Path $projectRoot ".build-tools/apache-maven-$mavenVersion"
    if (-not (Test-Path "$mavenRoot/bin/mvn.cmd")) {
        New-Item -ItemType Directory -Force .build-tools | Out-Null
        $mavenUrl = "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$mavenVersion/apache-maven-$mavenVersion-bin.zip"
        $requestArgs = @{}
        if ($Proxy) { $requestArgs.Proxy = $Proxy }
        Invoke-WebRequest $mavenUrl -OutFile .build-tools/maven.zip @requestArgs
        $checksum = (Invoke-WebRequest "$mavenUrl.sha512" @requestArgs).Content.Trim().Split(' ')[0]
        if ((Get-FileHash .build-tools/maven.zip -Algorithm SHA512).Hash -ne $checksum) { throw 'Maven checksum mismatch.' }
        Expand-Archive .build-tools/maven.zip .build-tools -Force
    }
    $mavenArguments = @('-B', '-ntp', 'verify')
    if ($Integration) { $mavenArguments += '-Dhdsl.integration=true' }
    & "$mavenRoot/bin/mvn.cmd" @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
    if ($Package) { & "$PSScriptRoot/package-windows.ps1" -JdkHome $JdkHome -Proxy $Proxy }
} finally { Pop-Location }
