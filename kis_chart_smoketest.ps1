# KIS 당일 분봉(1분봉) 스모크 테스트 — inquire-time-itemchartprice(FHKST03010200)가
# 공시 후 주가추적 재설계(10분 뒤 한 번에 [공시-2분 ~ 공시+10분] 차트 분석)에 쓸 데이터를 주는지 실측한다.
# 확인 포인트:
#   (1) rt_cd=0 으로 분봉이 오는지, 필드명이 stck_cntg_hour/stck_oprc/stck_hgpr/stck_lwpr/stck_prpr 인지
#   (2) 한 번 호출에 30개(=30분) 1분봉을 끝시각(FID_INPUT_HOUR_1) 기준 과거로 주는지 → 12분 창 1콜로 충분한지
#   (3) NXT 연장세션(프리 08:00~09:00 · 애프터 15:30~20:00) 시간대에 J 코드로 분봉이 비는지/오는지
# 실행:  ! powershell -ExecutionPolicy Bypass -File kis_chart_smoketest.ps1 [종목코드] [끝시각HHMMSS]
#   - 끝시각 미지정 시 현재시각. 장중/연장세션에 돌려야 그 시간대 커버리지를 알 수 있다.
#   - 장 마감 후엔 직전 세션 분봉으로 응답하므로 필드명/포맷 검증은 언제든 가능.

param([string]$Code = "005930", [string]$EndHour = "")

$ak = $env:KIS_APP_KEY
$as = $env:KIS_APP_SECRET
if (-not $ak -or -not $as) {
    Write-Host "[실패] 이 셸에 KIS_APP_KEY / KIS_APP_SECRET 환경변수가 없습니다." -ForegroundColor Red
    Write-Host "       사용자 환경변수로 추가했다면 셸(또는 IDE)을 재시작한 뒤 다시 실행하세요."
    exit 1
}
if (-not $EndHour) { $EndHour = (Get-Date).ToString("HHmmss") }

# 이 머신은 회사 SSL 검사 프록시가 있어 인증서 검증을 우회해야 함(curl -k 와 동일).
add-type @"
using System.Net;
using System.Security.Cryptography.X509Certificates;
public class TrustAllKisChart : ICertificatePolicy {
    public bool CheckValidationResult(ServicePoint sp, X509Certificate cert, WebRequest req, int problem) { return true; }
}
"@
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllKisChart
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12

$base = "https://openapi.koreainvestment.com:9443"

try {
    $body = @{ grant_type = "client_credentials"; appkey = $ak; appsecret = $as } | ConvertTo-Json
    $tok = Invoke-RestMethod -Method Post -Uri "$base/oauth2/tokenP" -ContentType "application/json" -Body $body
    Write-Host "[OK] 토큰 발급 성공" -ForegroundColor Green
} catch {
    Write-Host "[실패] 토큰 발급 오류: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

$headers = @{
    authorization = "Bearer $($tok.access_token)"
    appkey        = $ak
    appsecret     = $as
    tr_id         = "FHKST03010200"   # 주식당일분봉조회
    custtype      = "P"
}

# J(KRX)·UN(통합)·NX(NXT) 비교. 핵심: NXT 연장세션(프리 08~09시·애프터 15:30~20시)에 돌려서
# UN 이 KRX+NXT 분봉을 연속으로 주는지(이게 되면 애프터마켓 공시도 분석 가능). UN 거부 시 NX 도 확인.
foreach ($mkt in @("J", "UN", "NX")) {
    $q = "FID_ETC_CLS_CODE=&FID_COND_MRKT_DIV_CODE=$mkt&FID_INPUT_ISCD=$Code" +
         "&FID_INPUT_HOUR_1=$EndHour&FID_PW_DATA_INCU_YN=N"
    try {
        $r = Invoke-RestMethod -Method Get -Headers $headers `
            -Uri "$base/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice?$q"
        Write-Host "`n[$mkt] 끝시각=$EndHour rt_cd=$($r.rt_cd) msg=$($r.msg1)" -ForegroundColor Cyan
        if ($r.rt_cd -eq "0") {
            $rows = $r.output2
            Write-Host ("       분봉 {0}개 (시각/시가/고가/저가/종가/거래량)" -f $rows.Count) -ForegroundColor Green
            $rows | Select-Object -First 14 `
                @{n='일자';e={$_.stck_bsop_date}}, @{n='시각';e={$_.stck_cntg_hour}},
                @{n='시가';e={$_.stck_oprc}}, @{n='고가';e={$_.stck_hgpr}},
                @{n='저가';e={$_.stck_lwpr}}, @{n='종가';e={$_.stck_prpr}},
                @{n='거래량';e={$_.cntg_vol}} | Format-Table -AutoSize
        }
    } catch {
        Write-Host "`n[$mkt] [실패] 호출 오류: $($_.Exception.Message)" -ForegroundColor Red
    }
}

Write-Host "`n확인: (1) 필드명 일치, (2) 30개가 끝시각 기준 1분 간격 과거로 오는지," -ForegroundColor Magenta
Write-Host "      (3) NXT 시간대에 J/NX 중 어느 코드가 그 시간대 분봉을 주는지(연장세션 공시 분석 가능 여부)." -ForegroundColor Magenta
