param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAi,
    [string]$AiMessage = "Explain Java HashMap in one sentence."
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step {
    param([string]$Message)
    Write-Host "[MVP] $Message"
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
        $actual = $Response.code
        $message = $Response.message
        throw "$Name failed: expected code $ExpectedCode, got $actual, message=$message"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Name)

    if (-not $Condition) {
        throw "$Name failed"
    }
}

$stamp = Get-Date -Format "yyyyMMddHHmmssfff"
$username = "mvp_test_$stamp"
$otherUsername = "mvp_other_$stamp"
$password = "123456"

Write-Step "Testing backend at $BaseUrl"

$health = Invoke-JsonApi -Method GET -Path "/api/health"
Assert-Code $health 200 "health check"

Write-Step "Registering primary test user"
$register = Invoke-JsonApi -Method POST -Path "/api/auth/register" -Body @{
    username = $username
    password = $password
}
Assert-Code $register 200 "register"

Write-Step "Checking duplicate username validation"
$duplicateRegister = Invoke-JsonApi -Method POST -Path "/api/auth/register" -Body @{
    username = $username
    password = $password
}
Assert-Code $duplicateRegister 400 "duplicate register"

Write-Step "Checking login validation"
$wrongLogin = Invoke-JsonApi -Method POST -Path "/api/auth/login" -Body @{
    username = $username
    password = "wrong-password"
}
Assert-Code $wrongLogin 400 "wrong password login"

Write-Step "Logging in primary user"
$login = Invoke-JsonApi -Method POST -Path "/api/auth/login" -Body @{
    username = $username
    password = $password
}
Assert-Code $login 200 "login"
$token = $login.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($token)) "login token exists"
$authHeaders = @{ Authorization = "Bearer $token" }

Write-Step "Checking protected endpoint rejects missing and invalid token"
$profileNoToken = Invoke-JsonApi -Method GET -Path "/api/profile"
Assert-Code $profileNoToken 401 "profile without token"
$profileInvalidToken = Invoke-JsonApi -Method GET -Path "/api/profile" -Headers @{ Authorization = "Bearer invalid-token" }
Assert-Code $profileInvalidToken 401 "profile with invalid token"

Write-Step "Saving and reading learning profile"
$saveProfile = Invoke-JsonApi -Method PUT -Path "/api/profile" -Headers $authHeaders -Body @{
    learningDirection  = "Java"
    learningGoal       = "MVP integration test"
    currentLevel       = "beginner"
    learningPreference = "examples and step-by-step"
}
Assert-Code $saveProfile 200 "save profile"

$profile = Invoke-JsonApi -Method GET -Path "/api/profile" -Headers $authHeaders
Assert-Code $profile 200 "get profile"
Assert-True ($profile.data.learningDirection -eq "Java") "profile learning direction"

Write-Step "Creating and listing conversation"
$conversation = Invoke-JsonApi -Method POST -Path "/api/conversations" -Headers $authHeaders -Body @{
    title = "MVP Java Integration"
    mode  = "chat"
}
Assert-Code $conversation 200 "create conversation"
$conversationId = $conversation.data.conversationId
Assert-True ($conversationId -gt 0) "conversation id exists"

$conversationList = Invoke-JsonApi -Method GET -Path "/api/conversations" -Headers $authHeaders
Assert-Code $conversationList 200 "list conversations"
$matchedConversation = @($conversationList.data) | Where-Object { $_.id -eq $conversationId }
Assert-True (($matchedConversation | Measure-Object).Count -eq 1) "conversation appears in current user list"

Write-Step "Reading empty message list before AI chat"
$messagesBeforeAi = Invoke-JsonApi -Method GET -Path "/api/conversations/$conversationId/messages" -Headers $authHeaders
Assert-Code $messagesBeforeAi 200 "messages before AI"

Write-Step "Checking user data isolation"
$otherRegister = Invoke-JsonApi -Method POST -Path "/api/auth/register" -Body @{
    username = $otherUsername
    password = $password
}
Assert-Code $otherRegister 200 "register second user"

$otherLogin = Invoke-JsonApi -Method POST -Path "/api/auth/login" -Body @{
    username = $otherUsername
    password = $password
}
Assert-Code $otherLogin 200 "login second user"
$otherHeaders = @{ Authorization = "Bearer $($otherLogin.data.token)" }

$otherMessages = Invoke-JsonApi -Method GET -Path "/api/conversations/$conversationId/messages" -Headers $otherHeaders
Assert-Code $otherMessages 404 "second user cannot read first user messages"

if ($SkipAi) {
    Write-Step "Skipping AI chat because -SkipAi was provided"
} else {
    Write-Step "Calling AI chat"
    $aiChat = Invoke-JsonApi -Method POST -Path "/api/ai/chat" -Headers $authHeaders -Body @{
        conversationId = $conversationId
        message        = $AiMessage
    }
    Assert-Code $aiChat 200 "AI chat"
    Assert-True (-not [string]::IsNullOrWhiteSpace($aiChat.data.answer)) "AI answer exists"

    Write-Step "Checking chat history after AI chat"
    $messagesAfterAi = Invoke-JsonApi -Method GET -Path "/api/conversations/$conversationId/messages" -Headers $authHeaders
    Assert-Code $messagesAfterAi 200 "messages after AI"
    $messageList = @($messagesAfterAi.data)
    Assert-True ($messageList.Count -ge 2) "chat history contains user and assistant messages"
    Assert-True (($messageList | Where-Object { $_.role -eq "user" } | Measure-Object).Count -ge 1) "chat history contains user message"
    Assert-True (($messageList | Where-Object { $_.role -eq "assistant" } | Measure-Object).Count -ge 1) "chat history contains assistant message"
}

Write-Step "MVP integration test passed"
