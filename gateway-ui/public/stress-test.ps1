# Gateway 压力测试脚本
# 用法: .\stress-test.ps1 [-Duration 60] [-Threads 10] [-Url "http://localhost:9090/api/email/status"]

param(
    [int]$Duration = 60,          # 持续时间(秒)
    [int]$Threads = 10,           # 并发线程数
    [string]$Url = "http://localhost:9090/api/email/status"  # 测试URL
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Gateway 压力测试工具" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "测试URL: $Url" -ForegroundColor Yellow
Write-Host "持续时间: $Duration 秒" -ForegroundColor Yellow
Write-Host "并发线程: $Threads" -ForegroundColor Yellow
Write-Host ""

$totalRequests = 0
$totalErrors = 0
$totalResponseTime = 0
$startTime = Get-Date
$running = $true

# 创建runsace用于并发
$runspacePool = [runspacefactory]::CreateRunspacePool(1, $Threads)
$runspacePool.Open()

# 脚本块 - 发送请求
$scriptBlock = {
    param($url)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5
        $sw.Stop()
        return @{ success = $true; time = $sw.ElapsedMilliseconds }
    } catch {
        $sw.Stop()
        return @{ success = $false; time = $sw.ElapsedMilliseconds }
    }
}

# 创建作业列表
$jobs = [System.Collections.ArrayList]::new()

Write-Host "开始压测..." -ForegroundColor Green
Write-Host ""

# 主循环
$endTime = $startTime.AddSeconds($Duration)
$iteration = 0

while ((Get-Date) -lt $endTime) {
    $iteration++
    $remaining = ($endTime - (Get-Date)).TotalSeconds
    
    # 显示进度
    $progress = [math]::Round(($Duration - $remaining) / $Duration * 100)
    Write-Host "`r[$(Get-Date -Format 'HH:mm:ss')] 进度: $progress% | 剩余: $([math]::Round($remaining))秒 | 已发送: $totalRequests 请求 | 错误: $totalErrors" -NoNewline -ForegroundColor White
    
    # 启动并发请求
    for ($i = 0; $i -lt $Threads; $i++) {
        $powershell = [powershell]::Create()
        $powershell.RunspacePool = $runspacePool
        $powershell.AddScript($scriptBlock) | Out-Null
        $powershell.AddArgument($Url) | Out-Null
        
        $job = @{
            PowerShell = $powershell
            Handle = $powershell.BeginInvoke()
        }
        [void]$jobs.Add($job)
    }
    
    # 收集结果
    Start-Sleep -Milliseconds 100
    
    $completedJobs = @()
    foreach ($job in $jobs) {
        if ($job.Handle.IsCompleted) {
            $result = $job.PowerShell.EndInvoke($job.Handle)
            $totalRequests++
            if ($result.success) {
                $totalResponseTime += $result.time
            } else {
                $totalErrors++
            }
            $job.PowerShell.Dispose()
            $completedJobs += $job
        }
    }
    
    foreach ($job in $completedJobs) {
        [void]$jobs.Remove($job)
    }
}

# 清理剩余作业
foreach ($job in $jobs) {
    if ($job.Handle.IsCompleted) {
        $result = $job.PowerShell.EndInvoke($job.Handle)
        $totalRequests++
        if (-not $result.success) {
            $totalErrors++
        }
        $job.PowerShell.Dispose()
    }
}

$runspacePool.Close()

$actualDuration = ((Get-Date) - $startTime).TotalSeconds
$avgResponseTime = if ($totalRequests -gt $totalErrors) { [math]::Round($totalResponseTime / ($totalRequests - $totalErrors), 2) } else { 0 }
$qps = [math]::Round($totalRequests / $actualDuration, 2)
$errorRate = [math]::Round($totalErrors / $totalRequests * 100, 2)

Write-Host ""
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "   压测完成!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""
Write-Host "统计结果:" -ForegroundColor Cyan
Write-Host "  总请求数:   $totalRequests" -ForegroundColor White
Write-Host "  成功请求:   $($totalRequests - $totalErrors)" -ForegroundColor Green
Write-Host "  失败请求:   $totalErrors" -ForegroundColor Red
Write-Host "  错误率:     $errorRate%" -ForegroundColor $(if ($errorRate -gt 5) { "Red" } else { "Green" })
Write-Host "  持续时间:   $([math]::Round($actualDuration, 2)) 秒" -ForegroundColor White
Write-Host "  QPS:        $qps 请求/秒" -ForegroundColor Yellow
Write-Host "  平均响应:   $avgResponseTime ms" -ForegroundColor Yellow
Write-Host ""
Write-Host "现在可以查看 Prometheus 指标和 AI 分析结果!" -ForegroundColor Cyan
Write-Host ""