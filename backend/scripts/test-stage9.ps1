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
    Write-Host "[Stage9] $Message"
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
        throw ("{0} failed: expected code {1}, got {2}, message={3}" -f $Name, $ExpectedCode, $actual, $message)
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Name)

    if (-not $Condition) {
        throw ("{0} failed" -f $Name)
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
$username = "stage9_test_$stamp"
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

Write-Step "Checking protected analysis endpoint rejects missing token"
$overviewNoToken = Invoke-JsonApi -Method GET -Path "/api/analysis/overview"
Assert-Code $overviewNoToken 401 "analysis without token"

Write-Step "Saving learning profile"
$profileBody = @{
    learningDirection  = "Java"
    learningGoal       = "Improve Java collections"
    currentLevel       = "basic"
    learningPreference = "example based, step by step"
}
$profile = Invoke-JsonApi -Method PUT -Path "/api/profile" -Headers $authHeaders -Body $profileBody
Assert-Code $profile 200 "save profile"

Write-Step "Checking initial learning overview"
$overview = Invoke-JsonApi -Method GET -Path "/api/analysis/overview" -Headers $authHeaders
Assert-Code $overview 200 "initial overview"
Assert-True ($overview.data.learnedCount -eq 0) "initial learned count"
Assert-True ($overview.data.averageMasteryLevel -eq 0) "initial mastery"
Assert-True (@($overview.data.suggestions).Count -ge 1) "initial suggestions"
Assert-True (@($overview.data.nextActions).Count -ge 1) "initial next actions"

Write-Step "Generating initial study plan"
$initialPlanBody = @{
    period = "week"
    goal   = "Improve Java collections"
}
$plan = Invoke-JsonApi -Method POST -Path "/api/study-plans/generate" -Headers $authHeaders -Body $initialPlanBody
Assert-Code $plan 200 "generate initial plan"
Assert-True ($plan.data.period -eq "week") "initial plan period"
Assert-True (@($plan.data.steps).Count -ge 3) "initial plan steps"
Assert-True (-not [string]::IsNullOrWhiteSpace($plan.data.planContent)) "initial plan content"

if ($SkipAi) {
    Write-Step "Skipping AI question generation because -SkipAi was provided"
    Write-Step "Stage 9 non-AI test passed"
    exit 0
}

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

Write-Step "Generating objective question"
$generateQuestionBody = @{
    knowledgePointId = $targetPoint.id
    questionType     = "single_choice"
    difficulty       = "medium"
    count            = 1
}
$generated = Invoke-JsonApi -Method POST -Path "/api/questions/generate" -Headers $authHeaders -Body $generateQuestionBody
Assert-Code $generated 200 "generate objective question"
$question = @($generated.data) | Select-Object -First 1
Assert-True ($question.id -gt 0) "generated question id"

Write-Step "Submitting wrong answer to create weak point signal"
$wrongAnswerBody = @{
    answer = "Z"
}
$answer = Invoke-JsonApi -Method POST -Path "/api/questions/$($question.id)/answer" -Headers $authHeaders -Body $wrongAnswerBody
Assert-Code $answer 200 "submit wrong answer"
Assert-True ($answer.data.answerRecordId -gt 0) "answer record id"
Assert-True ($answer.data.score -eq 0) "wrong answer score"

Write-Step "Checking overview identifies weak knowledge point"
$updatedOverview = Invoke-JsonApi -Method GET -Path "/api/analysis/overview" -Headers $authHeaders
Assert-Code $updatedOverview 200 "updated overview"
Assert-True ($updatedOverview.data.learnedCount -ge 1) "updated learned count"
Assert-True ($updatedOverview.data.answeredQuestionCount -ge 1) "updated answered count"
Assert-True (@($updatedOverview.data.weakKnowledgePoints).Count -ge 1) "weak knowledge points exist"
$matchedWeakPoint = @($updatedOverview.data.weakKnowledgePoints) | Where-Object { $_.knowledgePointId -eq $targetPoint.id } | Select-Object -First 1
Assert-True ($null -ne $matchedWeakPoint) "target weak knowledge point exists"
Assert-True (@($updatedOverview.data.suggestions).Count -ge 1) "updated suggestions"

Write-Step "Generating study plan with weak point focus"
$weakPlanBody = @{
    period = "month"
    goal   = "Improve weak knowledge points"
}
$weakPlan = Invoke-JsonApi -Method POST -Path "/api/study-plans/generate" -Headers $authHeaders -Body $weakPlanBody
Assert-Code $weakPlan 200 "generate weak point plan"
Assert-True ($weakPlan.data.period -eq "month") "weak plan period"
Assert-True (@($weakPlan.data.focusKnowledgePoints).Count -ge 1) "weak plan focus points"
Assert-True (-not [string]::IsNullOrWhiteSpace($weakPlan.data.planContent)) "weak plan content"

Write-Step "Stage 9 integration test passed"
