$ErrorActionPreference = "Stop"
$gh = "C:\Program Files\GitHub CLI\gh.exe"
$repo = "White-2002/BaroConvert"
$gitDir = "work\publish-git"
$workTree = (Get-Location).Path

if (-not (Test-Path $gh)) {
    throw "GitHub CLI를 찾을 수 없습니다: $gh"
}

& $gh auth status
if ($LASTEXITCODE -ne 0) {
    throw "GitHub 로그인이 필요합니다. gh auth login을 먼저 실행하세요."
}

& $gh repo view $repo *> $null
if ($LASTEXITCODE -ne 0) {
    & $gh repo create $repo --public --description "광고 없는 Android 파일 변환기와 개인 NAS 변환 서버"
}

$origin = git --git-dir=$gitDir --work-tree=$workTree remote get-url origin 2>$null
if (-not $origin) {
    git --git-dir=$gitDir --work-tree=$workTree remote add origin "https://github.com/$repo.git"
}
git --git-dir=$gitDir --work-tree=$workTree push -u origin main

& $gh release create v0.5.0 outputs/baroconvert-v0.5.0.apk outputs/baroconvert-server-v0.5.0.zip release/update.json --repo $repo --title "바로변환 v0.5.0" --notes "GitHub Releases 자동 업데이트, 시스템 다크 모드, 이미지·문서·PDF·영상·음원 변환을 지원합니다."

Write-Host "게시 완료: https://github.com/$repo" -ForegroundColor Green
