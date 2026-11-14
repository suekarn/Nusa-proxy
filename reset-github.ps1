# reset-github.ps1
# Menyamakan isi GitHub dengan folder lokal (file yang sudah tidak ada di lokal ikut terhapus di GitHub).
# Jalankan dari folder repo (cmfa):
#   powershell -ExecutionPolicy Bypass -File .\reset-github.ps1

param(
    [string]$Branch = "main",
    [string]$Message = "Reset: samakan GitHub dengan folder lokal"
)

function Fail($m) {
    Write-Host "BATAL: $m" -ForegroundColor Red
    exit 1
}

function Run {
    & git @args
    if ($LASTEXITCODE -ne 0) { Fail "git $($args -join ' ') gagal" }
}

# --- Pengecekan awal ---
if (-not (Test-Path ".git")) { Fail "Jalankan script ini dari folder repo (cmfa)." }

if ((Test-Path ".git\rebase-merge") -or (Test-Path ".git\rebase-apply")) {
    Fail "Rebase masih berjalan. Selesaikan dulu: git -c core.editor=notepad rebase --continue"
}

if (-not (Test-Path ".github\workflows")) {
    Fail "Folder .github\workflows tidak ada di lokal. Kalau dilanjutkan, workflow build ikut terhapus dari GitHub."
}

if (-not (Test-Path ".gitignore")) {
    Write-Host "PERINGATAN: .gitignore tidak ditemukan, file build/lokal bisa ikut ter-upload." -ForegroundColor Yellow
}

$current = (git rev-parse --abbrev-ref HEAD).Trim()
if ($current -ne $Branch) { Fail "Kamu sedang di branch '$current', bukan '$Branch'." }

# --- 1. Ambil kondisi terbaru GitHub ---
Write-Host "1/5 Mengambil data terbaru dari GitHub..." -ForegroundColor Cyan
Run fetch origin

# --- 2. Backup kondisi GitHub saat ini ke branch cadangan ---
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$backup = "backup-$stamp"
Write-Host "2/5 Membuat backup di GitHub: branch '$backup'..." -ForegroundColor Cyan
Run push origin "origin/${Branch}:refs/heads/$backup"

# --- 3. Samakan isi dengan folder lokal ---
Write-Host "3/5 Menyiapkan perubahan (hapus yang sudah tidak ada, upload yang ada)..." -ForegroundColor Cyan
$orig = (git rev-parse HEAD).Trim()
Run reset --soft "origin/$Branch"
Run add -A

$changes = git status --short
if (-not $changes) {
    Write-Host "Tidak ada perbedaan antara lokal dan GitHub. Selesai." -ForegroundColor Green
    Run reset $orig
    exit 0
}

$deleted  = ($changes | Where-Object { $_ -match '^D' }).Count
$added    = ($changes | Where-Object { $_ -match '^A' }).Count
$modified = ($changes | Where-Object { $_ -match '^M' }).Count
Write-Host ""
Write-Host "Ringkasan: $added file baru, $modified diubah, $deleted dihapus." -ForegroundColor Yellow
Write-Host "Daftar lengkap bisa dilihat dengan: git status --short"
Write-Host ""

# --- 4. Konfirmasi ---
$jawab = Read-Host "Lanjut commit dan push ke '$Branch'? Ketik YA untuk lanjut"
if ($jawab -ne "YA") {
    Run reset $orig
    Fail "Dibatalkan oleh pengguna. Tidak ada yang diubah di GitHub (backup branch tetap ada)."
}

# --- 5. Commit dan push ---
Write-Host "4/5 Commit..." -ForegroundColor Cyan
Run commit -m $Message

Write-Host "5/5 Push ke GitHub..." -ForegroundColor Cyan
Run push origin $Branch

Write-Host ""
Write-Host "Selesai. GitHub sekarang sama dengan folder lokal." -ForegroundColor Green
Write-Host "Kondisi lama GitHub tersimpan di branch: $backup"
