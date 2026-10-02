param([string]$JdkHome = $env:JAVA_HOME, [string]$Destination, [string]$Proxy = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$previousHttpProxy = $env:HTTP_PROXY
$previousHttpsProxy = $env:HTTPS_PROXY
Push-Location $projectRoot
try {
    if (-not $JdkHome) { throw 'Set JAVA_HOME, or pass -JdkHome.' }
    $jdkMetadata = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $JdkHome 'release')) {
        if ($line -match '^(JAVA_VERSION|JAVA_RUNTIME_VERSION|IMPLEMENTOR)="(.*)"$') { $jdkMetadata[$Matches[1]] = $Matches[2] }
    }
    $version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
    $appJar = Join-Path $projectRoot "target/hdsl-$version.jar"
    if (-not (Test-Path $appJar)) { throw 'Run scripts/build.ps1 first.' }
    if (-not $Destination) { $Destination = Join-Path $projectRoot ('dist/preview-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
    $Destination = [IO.Path]::GetFullPath($Destination)
    if (Test-Path (Join-Path $Destination 'HDSL')) { throw 'Destination already contains HDSL. Choose a new directory to preserve its data.' }
    $packageInput = Join-Path $projectRoot ('target/package-input-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $packageInput -Force | Out-Null
    Copy-Item $appJar (Join-Path $packageInput 'hdsl.jar')
    Copy-Item 'target/lib' (Join-Path $packageInput 'lib') -Recurse
    & (Join-Path $JdkHome 'bin/jpackage.exe') --type app-image --input $packageInput --main-jar hdsl.jar --main-class com.hdsl.Main --name HDSL --app-version 0.2.0 --vendor jiefing --icon assets/HDSL.ico --dest $Destination
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    $appRoot = Join-Path $Destination 'HDSL'
    $nodeVersion = 'v24.21.0'
    $nodeArchive = "node-$nodeVersion-win-x64.zip"
    $nodeDir = Join-Path $projectRoot ".build-tools/node-$nodeVersion-win-x64"
    if (-not (Test-Path (Join-Path $nodeDir 'node.exe'))) {
        New-Item -ItemType Directory -Force .build-tools | Out-Null
        $requestArgs = @{}
        if ($Proxy) { $requestArgs.Proxy = $Proxy }
        Invoke-WebRequest "https://nodejs.org/dist/$nodeVersion/$nodeArchive" -OutFile ".build-tools/$nodeArchive" @requestArgs
        $checksums = (Invoke-WebRequest "https://nodejs.org/dist/$nodeVersion/SHASUMS256.txt" @requestArgs).Content
        $expected = (($checksums -split "`n" | Where-Object { $_.Trim().EndsWith($nodeArchive) }) -split '\s+')[0]
        if ((Get-FileHash ".build-tools/$nodeArchive" -Algorithm SHA256).Hash -ne $expected) { throw 'Node checksum mismatch.' }
        Expand-Archive ".build-tools/$nodeArchive" .build-tools -Force
    }
    New-Item -ItemType Directory -Force (Join-Path $appRoot 'tools') | Out-Null
    Copy-Item $nodeDir (Join-Path $appRoot 'tools/node') -Recurse
    if ($Proxy) { $env:HTTPS_PROXY = $Proxy; $env:HTTP_PROXY = $Proxy }
    & (Join-Path $nodeDir 'node.exe') (Join-Path $nodeDir 'node_modules/npm/bin/npm-cli.js') install --prefix (Join-Path $appRoot 'tools/pnpm') pnpm@10.34.0 --ignore-scripts --no-audit --no-fund --registry=https://registry.npmjs.org
    if ($LASTEXITCODE -ne 0) { throw 'Bundling pnpm failed.' }
    Copy-Item LICENSE (Join-Path $appRoot 'LICENSE-HDSL.txt')
    Copy-Item LICENSES (Join-Path $appRoot 'LICENSES') -Recurse
    Copy-Item THIRD_PARTY_NOTICES.md (Join-Path $appRoot 'THIRD_PARTY_NOTICES.md')
    Copy-Item docs/USER_GUIDE.md (Join-Path $appRoot '使用说明.md')
    New-Item -ItemType Directory -Force (Join-Path $appRoot 'docs') | Out-Null
    Copy-Item docs/pack-support.md,docs/runtime-compatibility.md,docs/VALIDATION.md,docs/HMCL_PORT.md,docs/accounts-and-downloads.md,docs/DEPENDENCY_SOURCES.md (Join-Path $appRoot 'docs')
    New-Item -ItemType Directory -Force (Join-Path $appRoot 'assets/artwork') | Out-Null
    Copy-Item assets/artwork/README.md,assets/artwork/generation-prompts.json (Join-Path $appRoot 'assets/artwork')
    $sourceArchive = Join-Path $appRoot "sources/hdsl-$version-source.zip"
    & "$PSScriptRoot/package-source.ps1" -Destination $sourceArchive
    $record = [ordered]@{ version=$version; builtAt=(Get-Date).ToString('o'); javaVendor=$jdkMetadata['IMPLEMENTOR']; javaVersion=$jdkMetadata['JAVA_VERSION']; javaRuntimeVersion=$jdkMetadata['JAVA_RUNTIME_VERSION']; node=$nodeVersion; pnpm='10.34.0'; jarSha256=(Get-FileHash $appJar -Algorithm SHA256).Hash; sourceSha256=(Get-FileHash $sourceArchive -Algorithm SHA256).Hash; iconSha256=(Get-FileHash 'assets/HDSL.ico' -Algorithm SHA256).Hash; artworkRevision=2; hmclRevision='77eee17d361996259a48cc7896006a57d2e34a2a' }
    $record | ConvertTo-Json | Set-Content (Join-Path $appRoot 'build-info.json') -Encoding utf8
    Write-Output "Ready: $appRoot\HDSL.exe"
} finally { $env:HTTP_PROXY=$previousHttpProxy; $env:HTTPS_PROXY=$previousHttpsProxy; Pop-Location }
