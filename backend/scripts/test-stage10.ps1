param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step {
    param([string]$Message)
    Write-Host "[Stage10] $Message"
}

function Convert-ApiResponse {
    param($Response)

    if ($Response.RawContentStream -and $Response.RawContentStream.CanRead) {
        $bytes = $Response.RawContentStream.ToArray()
        return [System.Text.Encoding]::UTF8.GetString($bytes) | ConvertFrom-Json
    }

    if ($Response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Response.Content) | ConvertFrom-Json
    }

    return ([string]$Response.Content) | ConvertFrom-Json
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST", "PUT")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )

    $params = @{
        Method          = $Method
        Uri             = "$BaseUrl$Path"
        Headers         = $Headers
        UseBasicParsing = $true
    }

    if ($null -ne $Body) {
        $jsonBody = $Body | ConvertTo-Json -Depth 20 -Compress
        $params["ContentType"] = "application/json; charset=utf-8"
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
    }

    $response = Invoke-WebRequest @params
    return Convert-ApiResponse $response
}

function Assert-Code {
    param($Response, [int]$ExpectedCode, [string]$Name)

    if ($Response.code -ne $ExpectedCode) {
        throw ("{0} failed: expected code {1}, got {2}, message={3}" -f $Name, $ExpectedCode, $Response.code, $Response.message)
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Name)

    if (-not $Condition) {
        throw ("{0} failed" -f $Name)
    }
}

Write-Step "Testing backend at $BaseUrl"

$health = Invoke-JsonApi -Method GET -Path "/api/health"
Assert-Code $health 200 "health check"

$stamp = Get-Date -Format "yyyyMMddHHmmssfff"
$username = "stage10_test_$stamp"
$password = "123456"

Write-Step "Registering and logging in student"
$register = Invoke-JsonApi -Method POST -Path "/api/auth/register" -Body @{
    username = $username
    password = $password
}
Assert-Code $register 200 "register"

$login = Invoke-JsonApi -Method POST -Path "/api/auth/login" -Body @{
    username = $username
    password = $password
}
Assert-Code $login 200 "login"
$token = $login.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($token)) "login token exists"
$authHeaders = @{ Authorization = "Bearer $token" }

Write-Step "Checking protected Agent endpoint rejects missing token"
$suggestionsNoToken = Invoke-JsonApi -Method GET -Path "/api/agents/suggestions"
Assert-Code $suggestionsNoToken 401 "agent suggestions without token"

Write-Step "Saving learning profile"
$profileBody = @{
    learningDirection  = "Java"
    learningGoal       = "Build a Java backend MVP"
    currentLevel       = "basic"
    learningPreference = "step by step"
}
$profile = Invoke-JsonApi -Method PUT -Path "/api/profile" -Headers $authHeaders -Body $profileBody
Assert-Code $profile 200 "save profile"

Write-Step "Generating all Agent suggestions"
$generated = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/generate" -Headers $authHeaders -Body @{
    agentType = "all"
}
Assert-Code $generated 200 "generate agent suggestions"
$generatedSuggestions = @($generated.data)
Assert-True ($generatedSuggestions.Count -eq 4) "generated four agent suggestions"
Assert-True (($generatedSuggestions | Where-Object { $_.agentType -eq "planning" } | Measure-Object).Count -eq 1) "planning agent generated"
Assert-True (($generatedSuggestions | Where-Object { $_.agentType -eq "teaching" } | Measure-Object).Count -eq 1) "teaching agent generated"
Assert-True (($generatedSuggestions | Where-Object { $_.agentType -eq "practice" } | Measure-Object).Count -eq 1) "practice agent generated"
Assert-True (($generatedSuggestions | Where-Object { $_.agentType -eq "analysis" } | Measure-Object).Count -eq 1) "analysis agent generated"

$confirmRequired = $generatedSuggestions | Where-Object { $_.requiresConfirmation -eq $true } | Select-Object -First 1
Assert-True ($null -ne $confirmRequired) "confirmation required suggestion exists"
Assert-True ($confirmRequired.status -eq "pending") "generated suggestion pending"
Assert-True (-not [string]::IsNullOrWhiteSpace($confirmRequired.actionPayload)) "generated action payload exists"

Write-Step "Checking pending suggestion list"
$pending = Invoke-JsonApi -Method GET -Path "/api/agents/suggestions?status=pending" -Headers $authHeaders
Assert-Code $pending 200 "pending suggestions"
Assert-True (@($pending.data).Count -ge 4) "pending suggestion count"

Write-Step "Checking completion requires confirmation"
$completeBeforeConfirm = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/$($confirmRequired.id)/complete" -Headers $authHeaders -Body @{
    note = "should fail before confirmation"
}
Assert-Code $completeBeforeConfirm 400 "complete before confirm"

Write-Step "Checking Agent event log"
$events = Invoke-JsonApi -Method GET -Path "/api/agents/suggestions/$($confirmRequired.id)/events" -Headers $authHeaders
Assert-Code $events 200 "agent suggestion events"
Assert-True ((@($events.data) | Where-Object { $_.eventType -eq "generated" } | Measure-Object).Count -ge 1) "generated event exists"

Write-Step "Confirming and completing suggestion"
$confirmed = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/$($confirmRequired.id)/confirm" -Headers $authHeaders -Body @{
    note = "confirmed by stage 10 test"
}
Assert-Code $confirmed 200 "confirm suggestion"
Assert-True ($confirmed.data.status -eq "confirmed") "confirmed status"

$completed = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/$($confirmRequired.id)/complete" -Headers $authHeaders -Body @{
    note = "completed by stage 10 test"
}
Assert-Code $completed 200 "complete suggestion"
Assert-True ($completed.data.status -eq "completed") "completed status"

Write-Step "Generating low-impact analysis suggestion"
$analysisGenerated = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/generate" -Headers $authHeaders -Body @{
    agentType = "analysis"
}
Assert-Code $analysisGenerated 200 "generate analysis agent suggestion"
Assert-True (@($analysisGenerated.data).Count -eq 1) "single analysis suggestion generated"
$analysisSuggestion = @($analysisGenerated.data) | Select-Object -First 1
Assert-True ($analysisSuggestion.requiresConfirmation -eq $false) "analysis suggestion does not require confirmation"
$analysisCompleted = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/$($analysisSuggestion.id)/complete" -Headers $authHeaders -Body @{
    note = "directly completed low-impact suggestion"
}
Assert-Code $analysisCompleted 200 "complete low-impact suggestion"

Write-Step "Checking invalid Agent type validation"
$invalidAgent = Invoke-JsonApi -Method POST -Path "/api/agents/suggestions/generate" -Headers $authHeaders -Body @{
    agentType = "invalid"
}
Assert-Code $invalidAgent 400 "invalid agent type"

Write-Step "Checking user data isolation"
$otherUsername = "stage10_other_$stamp"
$otherRegister = Invoke-JsonApi -Method POST -Path "/api/auth/register" -Body @{
    username = $otherUsername
    password = $password
}
Assert-Code $otherRegister 200 "other register"
$otherLogin = Invoke-JsonApi -Method POST -Path "/api/auth/login" -Body @{
    username = $otherUsername
    password = $password
}
Assert-Code $otherLogin 200 "other login"
$otherHeaders = @{ Authorization = "Bearer $($otherLogin.data.token)" }
$otherEvents = Invoke-JsonApi -Method GET -Path "/api/agents/suggestions/$($confirmRequired.id)/events" -Headers $otherHeaders
Assert-Code $otherEvents 404 "other user cannot read suggestion events"

Write-Step "Stage 10 integration test passed"
