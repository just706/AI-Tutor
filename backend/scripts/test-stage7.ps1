param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAi,
    [string]$Subject = "Java",
    [string]$KnowledgePointName = "HashMap"
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step {
    param([string]$Message)
    Write-Host "[Stage7] $Message"
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
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST")][string]$Method,
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
$username = "stage7_test_$stamp"
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

Write-Step "Finding target knowledge point"
$tree = Invoke-JsonApi -Method GET -Path "/api/knowledge-points/tree?subject=$Subject" -Headers $authHeaders
Assert-Code $tree 200 "knowledge point tree"

$script:flatPoints = @()
foreach ($root in @($tree.data)) {
    Add-KnowledgePoint $root
}
$targetPoint = @($flatPoints) | Where-Object { $_.name -eq $KnowledgePointName } | Select-Object -First 1
if ($null -eq $targetPoint) {
    $targetPoint = @($flatPoints) | Select-Object -First 1
}
Assert-True ($null -ne $targetPoint) "target knowledge point exists"
Write-Step "Using knowledge point $($targetPoint.name) #$($targetPoint.id)"

Write-Step "Checking protected question endpoint rejects missing token"
$questionsNoToken = Invoke-JsonApi -Method GET -Path "/api/questions?knowledgePointId=$($targetPoint.id)"
Assert-Code $questionsNoToken 401 "questions without token"

Write-Step "Checking question list endpoint"
$questionsBefore = Invoke-JsonApi -Method GET -Path "/api/questions?knowledgePointId=$($targetPoint.id)" -Headers $authHeaders
Assert-Code $questionsBefore 200 "question list before generation"

if ($SkipAi) {
    Write-Step "Skipping AI question generation because -SkipAi was provided"
    Write-Step "Stage 7 non-AI test passed"
    exit 0
}

Write-Step "Generating objective question"
$generatedObjective = Invoke-JsonApi -Method POST -Path "/api/questions/generate" -Headers $authHeaders -Body @{
    knowledgePointId = $targetPoint.id
    questionType     = "single_choice"
    difficulty       = "medium"
    count            = 1
}
Assert-Code $generatedObjective 200 "generate objective question"
$objectiveQuestion = @($generatedObjective.data) | Select-Object -First 1
Assert-True ($objectiveQuestion.id -gt 0) "objective question id exists"
Assert-True ($objectiveQuestion.questionType -eq "single_choice") "objective question type"
Assert-True (@($objectiveQuestion.options).Count -eq 4) "objective question options"
Assert-True (-not [string]::IsNullOrWhiteSpace($objectiveQuestion.answer)) "objective answer exists"
Assert-True (-not [string]::IsNullOrWhiteSpace($objectiveQuestion.analysis)) "objective analysis exists"

Write-Step "Reading generated objective question"
$readObjective = Invoke-JsonApi -Method GET -Path "/api/questions/$($objectiveQuestion.id)" -Headers $authHeaders
Assert-Code $readObjective 200 "read objective question"
Assert-True ($readObjective.data.id -eq $objectiveQuestion.id) "read objective id"

Write-Step "Submitting objective answer"
$objectiveAnswer = Invoke-JsonApi -Method POST -Path "/api/questions/$($objectiveQuestion.id)/answer" -Headers $authHeaders -Body @{
    answer = $objectiveQuestion.answer
}
Assert-Code $objectiveAnswer 200 "submit objective answer"
Assert-True ($objectiveAnswer.data.correct -eq $true) "objective answer correct"
Assert-True ($objectiveAnswer.data.score -eq 100) "objective answer score"
Assert-True ($objectiveAnswer.data.answerRecordId -gt 0) "objective answer record id"

Write-Step "Generating subjective question"
$generatedSubjective = Invoke-JsonApi -Method POST -Path "/api/questions/generate" -Headers $authHeaders -Body @{
    knowledgePointId = $targetPoint.id
    questionType     = "short_answer"
    difficulty       = "medium"
    count            = 1
}
Assert-Code $generatedSubjective 200 "generate subjective question"
$subjectiveQuestion = @($generatedSubjective.data) | Select-Object -First 1
Assert-True ($subjectiveQuestion.id -gt 0) "subjective question id exists"
Assert-True ($subjectiveQuestion.questionType -eq "short_answer") "subjective question type"
Assert-True (@($subjectiveQuestion.options).Count -eq 0) "subjective question options"

Write-Step "Submitting subjective answer for AI feedback"
$subjectiveAnswer = Invoke-JsonApi -Method POST -Path "/api/questions/$($subjectiveQuestion.id)/answer" -Headers $authHeaders -Body @{
    answer = $subjectiveQuestion.answer
}
Assert-Code $subjectiveAnswer 200 "submit subjective answer"
Assert-True ($subjectiveAnswer.data.answerRecordId -gt 0) "subjective answer record id"
Assert-True ($subjectiveAnswer.data.score -ge 0 -and $subjectiveAnswer.data.score -le 100) "subjective score range"
Assert-True (-not [string]::IsNullOrWhiteSpace($subjectiveAnswer.data.feedback)) "subjective feedback exists"

Write-Step "Checking learning record updated by answers"
$records = Invoke-JsonApi -Method GET -Path "/api/teaching/records" -Headers $authHeaders
Assert-Code $records 200 "learning records after questions"
$matchedRecord = @($records.data) | Where-Object { $_.knowledgePointId -eq $targetPoint.id } | Select-Object -First 1
Assert-True ($null -ne $matchedRecord) "learning record exists"
Assert-True ($matchedRecord.masteryLevel -ge 0 -and $matchedRecord.masteryLevel -le 100) "mastery level range"

Write-Step "Stage 7 integration test passed"
