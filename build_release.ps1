param(
    [switch]$skipGit,
    [switch]$onlyBuild
)

$ErrorActionPreference = "Stop"
$root = "C:\Users\xiali\Documents\trae_projects\BBDownAndroid"
Set-Location $root

$distDir = "$root\dist"
$apkDir = "$root\app\build\outputs\apk\debug"
$apkFF6 = "$distDir\BBDown-1.9.97-ffmpeg6.1.6-release.apk"
$apkFF8 = "$distDir\BBDown-1.9.97-ffmpeg-8.1.2-release.apk"

# 读取版本号
$buildGradle = "$root\app\build.gradle"
$vc = (Select-String -Path $buildGradle -Pattern 'versionCode (\d+)').Matches.Groups[1].Value
$vn = (Select-String -Path $buildGradle -Pattern 'versionName "([^"]+)"').Matches.Groups[1].Value
Write-Host "=== BBDown $vn (versionCode $vc) ===" -ForegroundColor Cyan

# ========== 编译 ==========
Write-Host "`n[1/5] 编译 ff6 (FFmpeg 6.1.6)..." -ForegroundColor Yellow
& ./gradlew assembleDebug -PffmpegVersion=6 --no-daemon 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw "ff6 编译失败" }

Write-Host "`n[2/5] 复制 ff6 APK..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
Copy-Item "$apkDir\app-debug-ff6.apk" $apkFF6 -Force
$sz6 = [math]::Round((Get-Item $apkFF6).Length / 1MB, 1)
Write-Host "  ff6: $apkFF6 ($sz6 MB)" -ForegroundColor Green

Write-Host "`n[3/5] 编译 ff8 (FFmpeg 8.1.2)..." -ForegroundColor Yellow
& ./gradlew assembleDebug -PffmpegVersion=8 --no-daemon 2>&1 | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { throw "ff8 编译失败" }

Write-Host "`n[4/5] 复制 ff8 APK..." -ForegroundColor Yellow
Copy-Item "$apkDir\app-debug-ff8.apk" $apkFF8 -Force
$sz8 = [math]::Round((Get-Item $apkFF8).Length / 1MB, 1)
Write-Host "  ff8: $apkFF8 ($sz8 MB)" -ForegroundColor Green

# ========== 发布 ==========
if ($onlyBuild) {
    Write-Host "`n--- onlyBuild, 跳过 GitHub Release ---" -ForegroundColor Cyan
    Write-Host "APK 已输出到: $distDir" -ForegroundColor Green
    return
}

Write-Host "`n[5/5] 更新 GitHub Release..." -ForegroundColor Yellow

# 取 release_id
$headers = @{ Authorization = "Bearer $env:GITHUB_TOKEN" }
$releases = Invoke-RestMethod -Uri "https://api.github.com/repos/$env:GITHUB_REPO/releases" -Headers $headers

$relFF8 = $releases | Where-Object { $_.tag_name -like "*ff8*" }
$relFF6 = $releases | Where-Object { $_.tag_name -like "*ff6*" }

if (-not $relFF8) { throw "未找到 ff8 release" }
if (-not $relFF6) { throw "未找到 ff6 release" }

# 删除旧 assets
foreach ($a in $relFF8.assets) {
    Invoke-RestMethod -Uri $a.url -Headers $headers -Method Delete | Out-Null
    Write-Host "  删除旧 asset: $($a.name)" -ForegroundColor Gray
}
foreach ($a in $relFF6.assets) {
    Invoke-RestMethod -Uri $a.url -Headers $headers -Method Delete | Out-Null
    Write-Host "  删除旧 asset: $($a.name)" -ForegroundColor Gray
}

# 上传新 assets
$uploadHeaders = @{ Authorization = "Bearer $env:GITHUB_TOKEN"; "Content-Type" = "application/vnd.android.package-archive" }

Write-Host "  上传 ff8..." -ForegroundColor Gray
$uploadUrl = $relFF8.upload_url -replace '\{.*\}', "?name=$(Split-Path $apkFF8 -Leaf)"
curl.exe -sS -X POST -H "Authorization: Bearer $env:GITHUB_TOKEN" `
  -H "Content-Type: application/vnd.android.package-archive" `
  --data-binary "@$apkFF8" "$uploadUrl" | Out-Null
Write-Host "  ff8 上传完成" -ForegroundColor Green

Write-Host "  上传 ff6..." -ForegroundColor Gray
$uploadUrl = $relFF6.upload_url -replace '\{.*\}', "?name=$(Split-Path $apkFF6 -Leaf)"
curl.exe -sS -X POST -H "Authorization: Bearer $env:GITHUB_TOKEN" `
  -H "Content-Type: application/vnd.android.package-archive" `
  --data-binary "@$apkFF6" "$uploadUrl" | Out-Null
Write-Host "  ff6 上传完成" -ForegroundColor Green

# 更新 release 描述
$desc = "## v$vn `u66f4`u65b0`u5185`u5bb9`n"
$descFile = Join-Path $root "RELEASE_NOTES.txt"
if (Test-Path $descFile) {
    $desc = [System.IO.File]::ReadAllText($descFile, [System.Text.Encoding]::UTF8)
}

$patchHeaders = @{ Authorization = "Bearer $env:GITHUB_TOKEN"; "Content-Type" = "application/json; charset=utf-8" }
$tmp = [System.IO.Path]::GetTempFileName()

# ff8
$body = @{ tag_name = $relFF8.tag_name; name = "BBDown v$vn ff8"; body = $desc } | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($tmp, $body, [System.Text.UTF8Encoding]::new($false))
curl.exe -sS -X PATCH -H "Authorization: Bearer $env:GITHUB_TOKEN" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@$tmp" "https://api.github.com/repos/$env:GITHUB_REPO/releases/$($relFF8.id)" | Out-Null
Write-Host "  ff8 描述已更新" -ForegroundColor Green

# ff6
$body = @{ tag_name = $relFF6.tag_name; name = "BBDown v$vn ff6"; body = $desc } | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($tmp, $body, [System.Text.UTF8Encoding]::new($false))
curl.exe -sS -X PATCH -H "Authorization: Bearer $env:GITHUB_TOKEN" `
  -H "Content-Type: application/json; charset=utf-8" `
  --data-binary "@$tmp" "https://api.github.com/repos/$env:GITHUB_REPO/releases/$($relFF6.id)" | Out-Null
Write-Host "  ff6 描述已更新" -ForegroundColor Green

Remove-Item $tmp -Force

Write-Host "`n=== 完成 ===" -ForegroundColor Cyan
Write-Host "Release: https://github.com/$env:GITHUB_REPO/releases" -ForegroundColor Green
