<#
.SYNOPSIS
    Gateway Stress Test Tool (Windows)

.EXAMPLE
    双击 stress_test.bat 运行
#>

Add-Type -AssemblyName Microsoft.VisualBasic

# 读取 URL
$URL = [Microsoft.VisualBasic.Interaction]::InputBox("Enter target URL:", "Gateway Stress Test", "http://127.0.0.1/api/headers")
if ([string]::IsNullOrWhiteSpace($URL)) {
    Write-Host "Cancelled." -ForegroundColor Yellow
    exit
}

# 读取 QPS
$QPS = [Microsoft.VisualBasic.Interaction]::InputBox("Requests per second (QPS):", "Gateway Stress Test", "100")
if ([string]::IsNullOrWhiteSpace($QPS)) { exit }
$QPS = [int]$QPS
if ($QPS -le 0) { $QPS = 100 }

# 读取时长
$Duration = [Microsoft.VisualBasic.Interaction]::InputBox("Test duration (seconds):", "Gateway Stress Test", "10")
if ([string]::IsNullOrWhiteSpace($Duration)) { exit }
$Duration = [int]$Duration
if ($Duration -le 0) { $Duration = 10 }

# 读取并发线程数
$Threads = [Microsoft.VisualBasic.Interaction]::InputBox("Concurrent threads:", "Gateway Stress Test", "10")
if ([string]::IsNullOrWhiteSpace($Threads)) { exit }
$Threads = [int]$Threads
if ($Threads -le 0) { $Threads = 10 }
if ($Threads -gt 100) { $Threads = 100 }

$Total = $QPS * $Duration

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "       Gateway Stress Test Tool        " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  URL:       $URL"
Write-Host "  QPS:       $QPS req/s"
Write-Host "  Duration:  ${Duration}s"
Write-Host "  Threads:   $Threads"
Write-Host "  Total:     $Total requests"
Write-Host ""
Write-Host "Running..." -ForegroundColor Yellow

# 创建运行空间池（兼容所有 PowerShell 版本）
$RunspacePool = [runspacefactory]::CreateRunspacePool(1, $Threads)
$RunspacePool.Open()

$Jobs = @()
$ScriptBlock = {
    param($Url)
    try {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $Response = Invoke-WebRequest -Uri $Url -Method GET -TimeoutSec 3 -UseBasicParsing
        $sw.Stop()
        return @{ Success = $true; Duration = $sw.ElapsedMilliseconds; Status = $Response.StatusCode }
    }
    catch {
        return @{ Success = $false; Duration = 0; Status = 0; Error = $_.Exception.Message }
    }
}

$Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$IntervalMs = [math]::Round(1000 / $QPS)

# 发送请求
for ($i = 1; $i -le $Total; $i++) {
    # 计算目标发送时间
    $TargetMs = $i * $IntervalMs
    $CurrentMs = $Stopwatch.ElapsedMilliseconds
    $WaitMs = $TargetMs - $CurrentMs

    if ($WaitMs -gt 0) {
        [System.Threading.Thread]::Sleep($WaitMs)
    }

    # 创建 PowerShell 作业
    $PS = [powershell]::Create()
    $PS.RunspacePool = $RunspacePool
    [void]$PS.AddScript($ScriptBlock).AddArgument($URL)

    $Jobs += @{
        PowerShell = $PS
        Handle = $PS.BeginInvoke()
    }

    # 进度显示
    if ($i % 50 -eq 0) {
        Write-Host "." -NoNewline
    }
}

Write-Host ""
Write-Host "Waiting for responses..." -ForegroundColor Yellow

# 收集结果
$Success = 0
$Fail = 0
$TotalTime = 0

foreach ($Job in $Jobs) {
    $Result = $Job.PowerShell.EndInvoke($Job.Handle)
    if ($Result.Success) {
        $Success++
        $TotalTime += $Result.Duration
    } else {
        $Fail++
    }
    $Job.PowerShell.Dispose()
}

$Stopwatch.Stop()
$RunspacePool.Close()

$Elapsed = [math]::Round($Stopwatch.Elapsed.TotalSeconds, 2)
$ActualQPS = [math]::Round($Total / $Elapsed, 2)
if ($Success -gt 0) {
    $AvgTime = [math]::Round($TotalTime / $Success, 2)
} else {
    $AvgTime = 0
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "            TEST REPORT                " -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Total Requests:  $Total"
Write-Host "  Successful:      $Success ($([math]::Round($Success / $Total * 100, 1))%)"
Write-Host "  Failed:          $Fail ($([math]::Round($Fail / $Total * 100, 1))%)"
Write-Host ""
Write-Host "  Elapsed Time:    ${Elapsed}s"
Write-Host "  Actual QPS:      $ActualQPS req/s"
Write-Host "  Avg Response:    ${AvgTime}ms"
Write-Host "  Threads:         $Threads"
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

[Microsoft.VisualBasic.Interaction]::MsgBox(
    "Total: $Total`nSuccess: $Success`nFailed: $Fail`n`nTime: ${Elapsed}s`nQPS: $ActualQPS`nAvg: ${AvgTime}ms",
    [Microsoft.VisualBasic.MsgBoxStyle]::OkOnly,
    "Test Completed"
)