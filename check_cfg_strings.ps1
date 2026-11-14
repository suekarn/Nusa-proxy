# Cek semua string berawalan cfg_ yang dipakai tapi belum didefinisikan.
# Jalankan dari folder utama proyek (yang ada settings.gradle):
#   powershell -ExecutionPolicy Bypass -File .\check_cfg_strings.ps1

$files = Get-ChildItem -Path . -Recurse -File -Include *.kt,*.java,*.xml |
    Where-Object { $_.FullName -notmatch '\\(build|\.git|\.gradle)\\' }

# String yang dipakai di kode, layout, atau manifest
$used = $files |
    Select-String -Pattern '(?:R\.string\.|@string/)(cfg_\w+)' -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

# String yang sudah didefinisikan di res/values*
$defined = $files |
    Where-Object { $_.FullName -match '\\res\\values[^\\]*\\' -and $_.Extension -eq '.xml' } |
    Select-String -Pattern 'name="(cfg_\w+)"' -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

$missing = $used | Where-Object { $defined -notcontains $_ }

Write-Host ""
Write-Host "Dipakai   : $($used.Count)"
Write-Host "Terdefinisi: $($defined.Count)"
Write-Host ""

if ($missing.Count -eq 0) {
    Write-Host "Semua string cfg_ sudah terdefinisi." -ForegroundColor Green
} else {
    Write-Host "String yang HILANG, salin ke cfg_strings.xml:" -ForegroundColor Yellow
    Write-Host ""
    foreach ($name in $missing) {
        $label = ($name -replace '^cfg_', '') -replace '_', ' '
        $label = (Get-Culture).TextInfo.ToTitleCase($label)
        Write-Host "    <string name=`"$name`">$label</string>"
    }
}
