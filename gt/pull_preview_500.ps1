$ErrorActionPreference = "Continue"

$csvPath = "gt\photo_map_500.csv"
$outDir = "gt\preview_500"
$failedCsv = "gt\photo_map_500_failed.csv"

New-Item -ItemType Directory -Force $outDir | Out-Null

$rows = Import-Csv $csvPath
$ok = 0
$failed = @()

foreach ($r in $rows) {
    $id = [string]$r.id
    $src = [string]$r.dataPath

    if ([string]::IsNullOrWhiteSpace($src) -or $src -eq "NULL") {
        $failed += [PSCustomObject]@{
            id = $r.id
            contentUri = $r.contentUri
            dataPath = $r.dataPath
            reason = "missing_data_path"
        }
        continue
    }

    $ext = ".bin"
    if ($r.mimeType -match "jpeg|jpg") { $ext = ".jpg" }
    elseif ($r.mimeType -match "png") { $ext = ".png" }
    elseif ($r.mimeType -match "webp") { $ext = ".webp" }

    $dst = Join-Path $outDir ($id + $ext)
    if (Test-Path $dst) {
        Remove-Item $dst -Force -ErrorAction SilentlyContinue
    }

    adb pull $src $dst | Out-Null

    if ($LASTEXITCODE -eq 0 -and (Test-Path $dst) -and ((Get-Item $dst).Length -gt 0)) {
        $ok++
    }
    else {
        if (Test-Path $dst) {
            Remove-Item $dst -Force -ErrorAction SilentlyContinue
        }
        $failed += [PSCustomObject]@{
            id = $r.id
            contentUri = $r.contentUri
            dataPath = $r.dataPath
            reason = "adb_pull_failed_or_empty"
        }
    }
}

$failed | Export-Csv -Path $failedCsv -NoTypeInformation -Encoding UTF8

Write-Output ("done total={0} ok={1} failed={2}" -f $rows.Count, $ok, $failed.Count)
Write-Output ("preview_dir={0}" -f (Resolve-Path $outDir).Path)
Write-Output ("failed_csv={0}" -f (Resolve-Path $failedCsv).Path)
