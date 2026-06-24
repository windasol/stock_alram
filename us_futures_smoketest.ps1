# 미국 선물 스모크 테스트 — 야후 파이낸스에서 선물 등락률이 조회되는지 1회 확인한다.
# 실행:  ! powershell -ExecutionPolicy Bypass -File us_futures_smoketest.ps1
#
# 키 불필요(야후 비공식·무료). 회사망 SSL 검사 프록시 환경이면 아래 인증서 우회가 필요할 수 있다.
# 자바 UsFuturesClient 와 동일한 v8/finance/chart 엔드포인트·심볼을 쓴다.

# 회사망 SSL 검사 프록시 우회(curl -k 와 동일) — 다른 스모크테스트와 동일 패턴.
add-type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllYahoo : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllYahoo
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

$symbols = [ordered]@{ "ES=F" = "S&P"; "NQ=F" = "나스닥"; "YM=F" = "다우" }
$headers = @{ "User-Agent" = "Mozilla/5.0" }   # 야후는 UA 없으면 거부(429/401)
$ok = 0
$parts = @()

foreach ($sym in $symbols.Keys) {
    $label = $symbols[$sym]
    $enc = [System.Uri]::EscapeDataString($sym)
    $uri = "https://query1.finance.yahoo.com/v8/finance/chart/$enc"
    try {
        $r = Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec 15
        $meta = $r.chart.result[0].meta
        $price = [double]$meta.regularMarketPrice
        $prev  = if ($meta.previousClose) { [double]$meta.previousClose } else { [double]$meta.chartPreviousClose }
        if ($prev -ne 0) {
            $pct = ($price - $prev) / $prev * 100.0
            $parts += ("{0} {1:+0.0;-0.0}%" -f $label, $pct)
            $ok++
            Write-Host ("[OK] {0} ({1}) 현재 {2} / 전일 {3} → {4:+0.0;-0.0}%" -f $label, $sym, $price, $prev, $pct) -ForegroundColor Green
        } else {
            Write-Host "[주의] $label ($sym): 전일가 0 — 건너뜀" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "[실패] $label ($sym) 조회 오류: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host ""
if ($ok -gt 0) {
    Write-Host ("리포트에 들어갈 한 줄:  🌎 미국 선물 | " + ($parts -join ", ")) -ForegroundColor Cyan
} else {
    Write-Host "[실패] 모든 선물 조회 실패 — 네트워크/프록시 차단 여부를 확인하세요." -ForegroundColor Red
    exit 1
}
