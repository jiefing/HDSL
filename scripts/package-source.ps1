param([string]$Destination)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$version = (Get-Content -LiteralPath (Join-Path $projectRoot 'VERSION') -Raw).Trim()
if (-not $Destination) { $Destination = Join-Path $projectRoot "target/hdsl-$version-source.zip" }
$Destination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path (Split-Path $Destination -Parent) -Force | Out-Null

# Explicit source roots keep portable instances, credentials, caches and reference clones out.
$sourceRoots = @('src', 'test', 'scripts', 'docs', 'assets', 'LICENSES', '.github')
$rootFiles = @('pom.xml', 'VERSION', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'README.md', 'README_EN.md', '.gitignore', '.gitattributes')
$files = @($rootFiles | ForEach-Object { Get-Item -LiteralPath (Join-Path $projectRoot $_) })
foreach ($sourceRoot in $sourceRoots) {
    $directory = Join-Path $projectRoot $sourceRoot
    if (Test-Path -LiteralPath $directory) {
        $entries = @(Get-ChildItem -LiteralPath $directory -Recurse -Force)
        if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) {
            throw "Source tree contains a link: $sourceRoot"
        }
        $files += @($entries | Where-Object { -not $_.PSIsContainer })
    }
}
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($Destination, [IO.FileMode]::Create, [IO.FileAccess]::Write)
$archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($file in ($files | Sort-Object FullName -Unique)) {
        $relative = $file.FullName.Substring($projectRoot.Length + 1).Replace('\', '/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, "HDSL-$version/$relative", [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $archive.Dispose(); $stream.Dispose() }
$checksum = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
"$checksum  $([IO.Path]::GetFileName($Destination))" | Set-Content -LiteralPath "$Destination.sha256" -Encoding ascii
Write-Output "Source archive: $Destination"
