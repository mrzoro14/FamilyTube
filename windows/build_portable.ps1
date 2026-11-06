# Builds FamilyTube-Portable-Windows.exe and the portable.json that tells installed copies
# about it. Bump VERSION in familytube_portable.py first.
#
# To publish a laptop-only update without prompting phones, attach both files to the
# latest release:
#   gh release upload <latest tag> dist\FamilyTube-Portable-Windows.exe dist\portable.json --clobber
param(
    [string]$Notes = "",
    [string]$Python = "D:\FamilyTube\.venv\Scripts\python.exe"
)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$dist = Join-Path $here "dist"
$work = Join-Path $env:TEMP "familytube-portable-build"

& $Python -m PyInstaller --noconfirm --onefile --windowed --name FamilyTube-Portable-Windows `
    --add-data "$here\web;web" --distpath $dist --workpath "$work\work" --specpath $work `
    "$here\familytube_portable.py"
if ($LASTEXITCODE -ne 0) { throw "PyInstaller failed" }

$exe = Join-Path $dist "FamilyTube-Portable-Windows.exe"
$version = [regex]::Match((Get-Content "$here\familytube_portable.py" -Raw), 'VERSION = "([^"]+)"').Groups[1].Value
$manifest = [ordered]@{ version = $version; sha256 = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower(); notes = $Notes }
[IO.File]::WriteAllText((Join-Path $dist "portable.json"), ($manifest | ConvertTo-Json))
"Built version $version"
