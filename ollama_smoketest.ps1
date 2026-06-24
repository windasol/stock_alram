# Ollama 스모크 테스트 — 로컬 LLM 서버가 떠 있고 모델이 응답하는지 1회 확인한다.
# 실행:  ! powershell -ExecutionPolicy Bypass -File ollama_smoketest.ps1
#
# 사전: (1) Ollama 설치·실행, (2) ollama pull exaone3.5:7.8b
# 로컬 무인증 http라 API 키 불필요. 모델 바꾸려면:  $env:OLLAMA_MODEL="qwen2.5:7b" 후 실행.

$base  = if ($env:OLLAMA_BASE_URL) { $env:OLLAMA_BASE_URL } else { "http://localhost:11434" }
$model = if ($env:OLLAMA_MODEL)    { $env:OLLAMA_MODEL }    else { "exaone3.5:7.8b" }

Write-Host "대상: $base / 모델 $model" -ForegroundColor Cyan

# --- 1) 서버 기동 + 설치된 모델 목록 ---
try {
    $tags = Invoke-RestMethod -Method Get -Uri "$base/api/tags" -TimeoutSec 5
    $names = ($tags.models | ForEach-Object { $_.name }) -join ", "
    Write-Host "[OK] Ollama 서버 응답. 설치된 모델: $names" -ForegroundColor Green
    if ($tags.models.name -notcontains $model) {
        Write-Host "[주의] '$model' 이 목록에 없습니다 — 'ollama pull $model' 먼저 실행하세요." -ForegroundColor Yellow
    }
} catch {
    Write-Host "[실패] Ollama 서버에 연결할 수 없습니다 ($base)." -ForegroundColor Red
    Write-Host "       Ollama가 설치·실행 중인지 확인하세요 (https://ollama.com/download)." -ForegroundColor Yellow
    exit 1
}

# --- 2) 실제 채팅 1회 (자바 OllamaClient와 동일한 /api/chat, stream=false) ---
$body = @{
    model    = $model
    stream   = $false
    options  = @{ temperature = 0.3 }
    messages = @(
        @{ role = "system"; content = "너는 한국 주식시장 분석가다. 한국어로 간결하게 답한다." },
        @{ role = "user";   content = "반도체 거래대금 3조, 2차전지 1.8조. 한 문장으로 장 흐름을 요약해줘." }
    )
} | ConvertTo-Json -Depth 6

Write-Host "`n채팅 테스트 중... (CPU면 수십 초~1분 걸릴 수 있음)" -ForegroundColor Cyan
try {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-RestMethod -Method Post -Uri "$base/api/chat" -ContentType "application/json" -Body $body -TimeoutSec 300
    $sw.Stop()
    Write-Host "[OK] 응답 ($([int]$sw.Elapsed.TotalSeconds)초):" -ForegroundColor Green
    Write-Host $r.message.content
} catch {
    Write-Host "[실패] 채팅 호출 오류: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
