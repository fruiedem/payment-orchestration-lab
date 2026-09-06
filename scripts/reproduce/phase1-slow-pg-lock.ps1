param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$EnvFile = ".env.example",
    [string]$GatewayMode = "DELAY_10S",
    [int]$ConcurrentRequests = 2
)

$ErrorActionPreference = "Stop"
$outputDirectory = Join-Path $PSScriptRoot "..\..\build\phase1-evidence"
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$sampleFile = Join-Path $outputDirectory "phase1-$stamp.csv"
"timestamp,active_connections,pending_connections,lock_waiters" | Set-Content $sampleFile

$stockBody = @{ sku = "phase1-shared"; stock = $ConcurrentRequests } | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$BaseUrl/api/lab/stock" -ContentType "application/json" -Body $stockBody
$orderBody = @{ sku = "phase1-shared"; quantity = 1; amount = 1000; gatewayMode = $GatewayMode } | ConvertTo-Json -Compress

$requestScript = {
    param($Url, $Body)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method Post -Uri "$Url/api/orders" -ContentType "application/json" -Body $Body
        [pscustomobject]@{ Status = $response.StatusCode; LatencyMs = $watch.ElapsedMilliseconds; Body = $response.Content }
    } catch {
        [pscustomobject]@{ Status = $_.Exception.Response.StatusCode.value__; LatencyMs = $watch.ElapsedMilliseconds; Body = $_.ErrorDetails.Message }
    }
}

$jobs = 1..$ConcurrentRequests | ForEach-Object { Start-Job -ScriptBlock $requestScript -ArgumentList $BaseUrl, $orderBody }
while ($jobs.State -contains "Running") {
    $active = (Invoke-RestMethod "$BaseUrl/actuator/metrics/hikaricp.connections.active").measurements |
        Where-Object statistic -eq "VALUE" | Select-Object -ExpandProperty value
    $pending = (Invoke-RestMethod "$BaseUrl/actuator/metrics/hikaricp.connections.pending").measurements |
        Where-Object statistic -eq "VALUE" | Select-Object -ExpandProperty value
    $lockWaiters = docker compose --env-file $EnvFile exec -T postgres psql -U payment_app -d payment_orchestration -tAc `
        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()"
    $lockWaiterCount = if ($null -eq $lockWaiters) { "measurement-error" } else { $lockWaiters.Trim() }
    "$(Get-Date -Format o),$active,$pending,$lockWaiterCount" | Add-Content $sampleFile
    Start-Sleep -Milliseconds 250
    $jobs = Get-Job -Id $jobs.Id
}

$results = $jobs | Receive-Job
$jobs | Remove-Job
$results | Format-Table -AutoSize
Write-Host "Samples: $sampleFile"
Write-Host "Transaction metric: $BaseUrl/actuator/metrics/payment.transaction.duration"
Write-Host "Request metric:     $BaseUrl/actuator/metrics/payment.request.latency"
