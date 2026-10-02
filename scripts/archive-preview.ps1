param([Parameter(Mandatory=$true)][string]$AppRoot, [string]$Destination)
$ErrorActionPreference = 'Stop'
$AppRoot = (Resolve-Path -LiteralPath $AppRoot).Path
$projectRoot = Split-Path $PSScriptRoot -Parent
$version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
if (-not $Destination) { $Destination = Join-Path $projectRoot "dist/HDSL-$version-windows-x64.zip" }
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) { throw "Archive exists; choose a new destination: $Destination" }
New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null

# Never include instances, installed Harness runtimes, launcher config, logs or caches.
$rootFiles = @('HDSL.exe', 'LICENSE-HDSL.txt', 'THIRD_PARTY_NOTICES.md', '使用说明.md', 'build-info.json')
$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $AppRoot $_) })
foreach ($name in @('app', 'runtime', 'tools/node', 'tools/pnpm', 'docs', 'LICENSES', 'sources', 'assets/artwork')) {
    $entries = @(Get-ChildItem -LiteralPath (Join-Path $AppRoot $name) -Recurse -Force)
    if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
        throw "Bundled application contains a link: $name"
    }
    $files += @($entries | Where-Object { -not $_.PSIsContainer })
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object FullName -Unique)) {
        $relative = $file.FullName.Substring($AppRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "HDSL/$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }
$checksum = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  $([IO.Path]::GetFileName($Destination))" | Set-Content -LiteralPath "$Destination.sha256" -Encoding ascii
Write-Output "Portable archive: $Destination"
