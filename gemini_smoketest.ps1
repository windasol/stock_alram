# Gemini 스모크 테스트 — 키가 유효하고 모델이 한국어 요약을 내는지 1회 확인한다.
# 실행:  ! powershell -ExecutionPolicy Bypass -File gemini_smoketest.ps1
#
# 사전: $env:GEMINI_API_KEY 설정 (https://aistudio.google.com/apikey 에서 무료 발급).
# 모델 바꾸려면:  $env:GEMINI_MODEL="gemini-2.0-flash" 후 실행.

$key   = $env:GEMINI_API_KEY
$model = if ($env:GEMINI_MODEL) { $env:GEMINI_MODEL } else { "gemini-2.5-flash" }
if (-not $key) {
    Write-Host "[실패] 이 셸에 GEMINI_API_KEY 환경변수가 없습니다." -ForegroundColor Red
    Write-Host "       발급: https://aistudio.google.com/apikey  →  `$env:GEMINI_API_KEY='키' 후 다시 실행." -ForegroundColor Yellow
    exit 1
}

# 회사망 SSL 검사 프록시 우회(curl -k 와 동일) — KIS 스모크테스트와 동일 패턴.
add-type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllGemini : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllGemini
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

$base = "https://generativelanguage.googleapis.com/v1beta"
$headers = @{ "x-goog-api-key" = $key }

# --- 1) 사용 가능한 모델 목록 (키 유효성 + 모델명 확인) ---
try {
    $models = Invoke-RestMethod -Method Get -Uri "$base/models" -Headers $headers -TimeoutSec 15
    $flash = $models.models | Where-Object { $_.name -match "flash" } | ForEach-Object { $_.name -replace "^models/","" }
    Write-Host "[OK] 키 유효. 사용 가능한 flash 모델(일부):" -ForegroundColor Green
    Write-Host ("  " + (($flash | Select-Object -First 8) -join ", "))
    if (($models.models.name -replace "^models/","") -notcontains $model) {
        Write-Host "[주의] '$model' 이 목록에 없습니다 — 위 목록의 이름으로 GEMINI_MODEL 을 맞추세요." -ForegroundColor Yellow
    }
} catch {
    Write-Host "[실패] 모델 목록 조회 오류(키·네트워크 확인): $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# --- 2) 실제 요약 1회 (자바 GeminiClient와 동일한 generateContent) ---
$body = @{
    system_instruction = @{ parts = @(@{ text = "너는 한국 주식시장 분석가다. 한국어로 간결하게 답한다." }) }
    contents = @(@{ role = "user"; parts = @(@{ text = "반도체 거래대금 3조, 2차전지 1.8조, 바이오 1.1조. 한 문장으로 장 흐름을 요약해줘." }) })
    generationConfig = @{ temperature = 0.3 }
} | ConvertTo-Json -Depth 8

Write-Host "`n요약 테스트 중..." -ForegroundColor Cyan
try {
    $r = Invoke-RestMethod -Method Post -Uri "$base/models/$model`:generateContent" -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 60
    $text = $r.candidates[0].content.parts[0].text
    Write-Host "[OK] 응답:" -ForegroundColor Green
    Write-Host $text
} catch {
    Write-Host "[실패] generateContent 오류: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
