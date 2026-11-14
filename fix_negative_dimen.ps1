# Memperbaiki pola "-@dimen/nama" di layout (tidak valid di Android).
# Skrip membuat dimen negatif "nama_negative" dari nilai aslinya
# lalu mengganti semua pemakaiannya di layout.
#
# Jalankan dari folder utama proyek (yang ada settings.gradle):
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
#   .\fix_negative_dimen.ps1

$utf8 = New-Object System.Text.UTF8Encoding($false)

$resDirs = @('design\src\main\res', 'app\src\main\res') | Where-Object { Test-Path $_ }
if ($resDirs.Count -eq 0) {
    Write-Host "Folder res tidak ditemukan. Jalankan dari folder utama proyek." -ForegroundColor Red
    return
}

$allXml = foreach ($d in $resDirs) { Get-ChildItem $d -Recurse -File -Filter *.xml }
$layouts = $allXml | Where-Object { $_.FullName -match '\\(layout|drawable|menu)[^\\]*\\' }
$valueFiles = $allXml | Where-Object { $_.FullName -match '\\values[^\\]*\\' }

# 1. Cari semua nama dimen yang dipakai dengan tanda minus
$names = $layouts |
    Select-String -Pattern '"-@dimen/(\w+)"' -AllMatches |
    ForEach-Object { $_.Matches } |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique

if (-not $names) {
    Write-Host "Tidak ada pola -@dimen yang perlu diperbaiki." -ForegroundColor Green
    return
}
Write-Host "Dimen yang dipakai dengan minus: $($names -join ', ')"

# 2. Buat dimen negatif di setiap folder values yang mendefinisikan dimen aslinya
$byFolder = @{}
foreach ($name in $names) {
    $found = $false
    foreach ($f in $valueFiles) {
        $text = [System.IO.File]::ReadAllText($f.FullName)
        $m = [regex]::Match($text, '<dimen\s+name="' + $name + '"[^>]*>\s*([^<\s]+)\s*</dimen>')
        if (-not $m.Success) { continue }
        $found = $true
        $val = $m.Groups[1].Value
        if ($val.StartsWith('@')) {
            Write-Warning "$name di $($f.Name) berupa referensi ($val), buat nilai negatifnya manual."
            continue
        }
        if ($val.StartsWith('-')) { $neg = $val.Substring(1) } else { $neg = "-$val" }
        $dir = $f.DirectoryName
        if (-not $byFolder.ContainsKey($dir)) { $byFolder[$dir] = @() }
        $byFolder[$dir] += ('    <dimen name="{0}_negative">{1}</dimen>' -f $name, $neg)
        Write-Host "  $name = $val  ->  ${name}_negative = $neg  ($dir)"
    }
    if (-not $found) {
        Write-Warning "Dimen '$name' tidak ditemukan di folder values mana pun. Tambahkan dulu definisinya."
    }
}

foreach ($dir in $byFolder.Keys) {
    $lines = @('<?xml version="1.0" encoding="utf-8"?>', '<resources>') + $byFolder[$dir] + '</resources>'
    $path = Join-Path $dir 'dimens_negative.xml'
    [System.IO.File]::WriteAllText($path, ($lines -join "`r`n") + "`r`n", $utf8)
    Write-Host "Dibuat: $path" -ForegroundColor Green
}

# 3. Ganti pemakaian di layout
foreach ($f in $layouts) {
    $t = [System.IO.File]::ReadAllText($f.FullName)
    $n = [regex]::Replace($t, '"-@dimen/(\w+)"', '"@dimen/${1}_negative"')
    if ($n -ne $t) {
        [System.IO.File]::WriteAllText($f.FullName, $n, $utf8)
        Write-Host "Diperbaiki: $($f.FullName)" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "Selesai. Lanjut: git add . ; git commit -m 'Perbaiki dimen negatif' ; git push"
