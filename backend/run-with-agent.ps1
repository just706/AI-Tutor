param(
    [string]$Python = 'python',
    [ValidateRange(1024,65535)][int]$AgentPort = 8091
)

$ErrorActionPreference = 'Stop'
$agentRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'agent-service'
$previousEnvironment = @{}
$agentProcess = $null
function Set-RunEnvironment([string]$Name, [string]$Value) {
    if (-not $previousEnvironment.ContainsKey($Name)) {
        $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

try {
    # Reuse Java configuration; existing process environment takes precedence.
    $configPath = Join-Path $PSScriptRoot '.env'
    if (Test-Path -LiteralPath $configPath) {
        foreach ($line in Get-Content -LiteralPath $configPath -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
                $settingName = $Matches[1]
                $settingValue = $Matches[2].Trim().Trim('"').Trim("'")
                if (-not [Environment]::GetEnvironmentVariable($settingName, 'Process')) {
                    Set-RunEnvironment $settingName $settingValue
                }
            }
        }
    }
    $pythonCommand = (Get-Command $Python -ErrorAction Stop).Source
    & $pythonCommand -c 'import fastapi, httpx, pydantic, uvicorn'
    if ($LASTEXITCODE -ne 0) { throw 'Install agent-service dependencies before starting.' }
    if (-not $env:DEEPSEEK_API_KEY -and -not $env:AGENT_MODEL_API_KEY) {
        throw 'Configure DEEPSEEK_API_KEY or AGENT_MODEL_API_KEY first.'
    }
    $javaPort = if ($env:SERVER_PORT) { [int]$env:SERVER_PORT } else { 8080 }
    foreach ($port in @($javaPort, $AgentPort)) {
        $probe = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $probe.ConnectAsync('127.0.0.1', $port)
            if ($connect.Wait(300) -and $probe.Connected) { throw "Port $port is in use. Stop the existing service first." }
        } catch [System.AggregateException] {
            # Connection refusal means the port has no running service.
        } finally { $probe.Dispose() }
    }
    if ($javaPort -eq $AgentPort) { throw 'Java and Python require different ports.' }
    if (-not $env:AGENT_SERVICE_TOKEN) { Set-RunEnvironment 'AGENT_SERVICE_TOKEN' ([guid]::NewGuid().ToString('N')) }
    if (-not $env:AGENT_JAVA_SERVICE_TOKEN) { Set-RunEnvironment 'AGENT_JAVA_SERVICE_TOKEN' ([guid]::NewGuid().ToString('N')) }
    Set-RunEnvironment 'PYTHON_AGENT_ENABLED' 'true'
    Set-RunEnvironment 'PYTHON_AGENT_BASE_URL' "http://127.0.0.1:$AgentPort"
    Set-RunEnvironment 'AGENT_JAVA_BASE_URL' "http://127.0.0.1:$javaPort"
    Set-RunEnvironment 'AGENT_MODE' 'live'
    Set-RunEnvironment 'AGENT_ENABLE_REAL_MODEL' 'true'
    Set-RunEnvironment 'PYTHONUTF8' '1'
    if (-not $env:AGENT_MODEL_API_KEY) { Set-RunEnvironment 'AGENT_MODEL_API_KEY' $env:DEEPSEEK_API_KEY }
    if (-not $env:AGENT_MODEL_BASE_URL) {
        Set-RunEnvironment 'AGENT_MODEL_BASE_URL' $(if ($env:DEEPSEEK_BASE_URL) { $env:DEEPSEEK_BASE_URL } else { 'https://api.deepseek.com' })
    }
    if (-not $env:AGENT_MODEL_NAME) {
        Set-RunEnvironment 'AGENT_MODEL_NAME' $(if ($env:DEEPSEEK_MODEL_NAME) { $env:DEEPSEEK_MODEL_NAME } else { 'deepseek-v4-flash' })
    }
    $agentLogs = Join-Path $PSScriptRoot ('target\agent-run-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $agentLogs | Out-Null
    $agentProcess = Start-Process -FilePath $pythonCommand -ArgumentList @('-m','uvicorn','tutor_agent.app:build_app','--factory',
        '--host','127.0.0.1','--port',"$AgentPort",'--workers','1','--no-access-log') -WorkingDirectory $agentRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $agentLogs 'stdout.log') -RedirectStandardError (Join-Path $agentLogs 'stderr.log')
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($agentProcess.HasExited) { throw "Python Agent exited. Inspect $agentLogs" }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$AgentPort/health" -TimeoutSec 1
            if ($health.mode -eq 'live') { $ready = $true; break }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw "Python Agent startup timeout. Inspect $agentLogs" }
    Write-Host "Python Agent ready on port $AgentPort. Java will use it for new textbook questions."
    Write-Host 'New questions may use up to 4 model calls. Starting services does not generate answers.'
    & (Join-Path $PSScriptRoot 'run.cmd')
    if ($LASTEXITCODE -ne 0) { throw 'Java service exited with an error.' }
} finally {
    if ($agentProcess -and -not $agentProcess.HasExited) { Stop-Process -Id $agentProcess.Id -ErrorAction SilentlyContinue }
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process')
    }
}
