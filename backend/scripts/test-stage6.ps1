param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAi,
    [string]$Subject = "Java",
    [string]$KnowledgePointName = "HashMap",
    [string]$StudentAnswer = "HashMap stores key-value pairs and uses the key to quickly find the value.",
    [string]$AdminToken = ""
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step {
    param([string]$Message)
    Write-Host "[Stage6] $Message"
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
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST", "PUT", "DELETE")][string]$Method,
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

function Add-KnowledgePoint {
    param($Point)

    $script:flatPoints += $Point
    foreach ($child in @($Point.children)) {
        Add-KnowledgePoint $child
    }
}

Write-Step "Testing backend at $BaseUrl"

$health = Invoke-JsonApi -Method GET -Path "/api/health"
Assert-Code $health 200 "health check"

$stamp = Get-Date -Format "yyyyMMddHHmmssfff"
$username = "stage6_test_$stamp"
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

Write-Step "Checking knowledge point tree"
$tree = Invoke-JsonApi -Method GET -Path "/api/knowledge-points/tree?subject=$Subject" -Headers $authHeaders
Assert-Code $tree 200 "knowledge point tree"

$script:flatPoints = @()
foreach ($root in @($tree.data)) {
    Add-KnowledgePoint $root
}
Assert-True ($flatPoints.Count -gt 0) "knowledge point tree is not empty"

$targetPoint = @($flatPoints) | Where-Object { $_.name -eq $KnowledgePointName } | Select-Object -First 1
if ($null -eq $targetPoint) {
    $targetPoint = @($flatPoints) | Select-Object -First 1
}
Assert-True ($null -ne $targetPoint) "target knowledge point exists"
Write-Step "Using knowledge point $($targetPoint.name) #$($targetPoint.id)"

Write-Step "Checking student cannot maintain knowledge points"
$forbiddenCreate = Invoke-JsonApi -Method POST -Path "/api/admin/knowledge-points" -Headers $authHeaders -Body @{
    subject   = $Subject
    name      = "Student Forbidden $stamp"
    parentId  = 0
    sortOrder = 999
}
Assert-Code $forbiddenCreate 403 "student create knowledge point forbidden"

if (-not [string]::IsNullOrWhiteSpace($AdminToken)) {
    Write-Step "Checking admin knowledge point maintenance"
    $adminHeaders = @{ Authorization = "Bearer $AdminToken" }
    $adminName = "Stage6 Admin Test $stamp"
    $created = Invoke-JsonApi -Method POST -Path "/api/admin/knowledge-points" -Headers $adminHeaders -Body @{
        subject   = $Subject
        name      = $adminName
        parentId  = 0
        sortOrder = 998
    }
    Assert-Code $created 200 "admin create knowledge point"
    $createdId = $created.data.id
    Assert-True ($createdId -gt 0) "admin created id"

    $updated = Invoke-JsonApi -Method PUT -Path "/api/admin/knowledge-points/$createdId" -Headers $adminHeaders -Body @{
        subject   = $Subject
        name      = "$adminName Updated"
        parentId  = 0
        sortOrder = 997
    }
    Assert-Code $updated 200 "admin update knowledge point"

    $deleted = Invoke-JsonApi -Method DELETE -Path "/api/admin/knowledge-points/$createdId" -Headers $adminHeaders
    Assert-Code $deleted 200 "admin delete knowledge point"
    Assert-True ($deleted.data -eq $true) "admin delete result"
}

$recordsBefore = Invoke-JsonApi -Method GET -Path "/api/teaching/records" -Headers $authHeaders
Assert-Code $recordsBefore 200 "learning records before teaching"

if ($SkipAi) {
    Write-Step "Skipping AI teaching because -SkipAi was provided"
    Write-Step "Stage 6 non-AI test passed"
    exit 0
}

Write-Step "Starting teaching mode"
$start = Invoke-JsonApi -Method POST -Path "/api/teaching/start" -Headers $authHeaders -Body @{
    knowledgePointId = $targetPoint.id
}
Assert-Code $start 200 "start teaching"
$conversationId = $start.data.conversationId
Assert-True ($conversationId -gt 0) "teaching conversation id exists"
Assert-True (-not [string]::IsNullOrWhiteSpace($start.data.teachingContent)) "teaching content exists"
Assert-True ($start.data.learningStatus -eq "in_progress") "learning status in progress"

Write-Step "Checking teaching messages"
$messages = Invoke-JsonApi -Method GET -Path "/api/conversations/$conversationId/messages" -Headers $authHeaders
Assert-Code $messages 200 "teaching messages"
$messageList = @($messages.data)
Assert-True ($messageList.Count -ge 2) "teaching conversation contains messages"

Write-Step "Submitting teaching answer for AI evaluation"
$evaluation = Invoke-JsonApi -Method POST -Path "/api/teaching/evaluate" -Headers $authHeaders -Body @{
    conversationId     = $conversationId
    knowledgePointId   = $targetPoint.id
    studentAnswer      = $StudentAnswer
}
Assert-Code $evaluation 200 "teaching evaluation"
Assert-True (-not [string]::IsNullOrWhiteSpace($evaluation.data.feedback)) "teaching feedback exists"
Assert-True ($evaluation.data.masteryLevel -ge 0 -and $evaluation.data.masteryLevel -le 100) "mastery level range"

Write-Step "Checking learning record updated"
$recordsAfter = Invoke-JsonApi -Method GET -Path "/api/teaching/records" -Headers $authHeaders
Assert-Code $recordsAfter 200 "learning records after teaching"
$matchedRecord = @($recordsAfter.data) | Where-Object { $_.knowledgePointId -eq $targetPoint.id } | Select-Object -First 1
Assert-True ($null -ne $matchedRecord) "learning record exists"
Assert-True ($matchedRecord.masteryLevel -ge 0 -and $matchedRecord.masteryLevel -le 100) "record mastery level range"

Write-Step "Stage 6 integration test passed"
