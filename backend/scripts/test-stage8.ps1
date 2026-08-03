param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAi
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step {
    param([string]$Message)
    Write-Host "[Stage8] $Message"
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

function Convert-BytesApiResponse {
    param([byte[]]$Bytes)
    return [System.Text.Encoding]::UTF8.GetString($Bytes) | ConvertFrom-Json
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

function Invoke-MultipartApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$Token
    )

    $client = [System.Net.Http.HttpClient]::new()
    $form = [System.Net.Http.MultipartFormDataContent]::new()
    $fileStream = [System.IO.File]::OpenRead($FilePath)
    try {
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Token)
        $fileContent = [System.Net.Http.StreamContent]::new($fileStream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/plain")
        $form.Add($fileContent, "file", [System.IO.Path]::GetFileName($FilePath))

        $response = $client.PostAsync("$BaseUrl$Path", $form).Result
        $bytes = $response.Content.ReadAsByteArrayAsync().Result
        return Convert-BytesApiResponse $bytes
    } finally {
        $fileStream.Dispose()
        $form.Dispose()
        $client.Dispose()
    }
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

Write-Step "Testing backend at $BaseUrl"

$health = Invoke-JsonApi -Method GET -Path "/api/health"
Assert-Code $health 200 "health check"

$stamp = Get-Date -Format "yyyyMMddHHmmssfff"
$username = "stage8_test_$stamp"
$password = "123456"
$tempFile = Join-Path ([System.IO.Path]::GetTempPath()) "ai-tutor-rag-$stamp.md"

try {
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

    Write-Step "Creating test RAG document"
    $documentText = @"
# Java HashMap Notes

HashMap is a Java collection that stores key-value pairs.
HashMap uses a hash code from the key to find the bucket where the value is stored.
HashMap retrieval is usually fast because it does not scan every element.
When two keys map to the same bucket, HashMap handles the collision inside that bucket.
"@
    [System.IO.File]::WriteAllText($tempFile, $documentText, [System.Text.Encoding]::UTF8)

    Write-Step "Checking protected document endpoint rejects missing token"
    $documentsNoToken = Invoke-JsonApi -Method GET -Path "/api/documents"
    Assert-Code $documentsNoToken 401 "documents without token"

    Write-Step "Uploading document"
    $upload = Invoke-MultipartApi -Path "/api/documents/upload" -FilePath $tempFile -Token $token
    Assert-Code $upload 200 "upload document"
    $documentId = $upload.data.documentId
    Assert-True ($documentId -gt 0) "uploaded document id"
    Assert-True ($upload.data.processStatus -eq "completed") "document process completed"
    Assert-True ($upload.data.chunkCount -gt 0) "document chunks created"

    Write-Step "Listing documents"
    $documents = Invoke-JsonApi -Method GET -Path "/api/documents" -Headers $authHeaders
    Assert-Code $documents 200 "list documents"
    $matchedDocument = @($documents.data) | Where-Object { $_.id -eq $documentId } | Select-Object -First 1
    Assert-True ($null -ne $matchedDocument) "uploaded document appears in list"
    Assert-True ($matchedDocument.chunkCount -gt 0) "listed document chunk count"

    Write-Step "Creating RAG conversation"
    $conversation = Invoke-JsonApi -Method POST -Path "/api/conversations" -Headers $authHeaders -Body @{
        title = "Stage8 RAG Test"
        mode  = "rag"
    }
    Assert-Code $conversation 200 "create rag conversation"
    $conversationId = $conversation.data.conversationId
    Assert-True ($conversationId -gt 0) "rag conversation id"

    Write-Step "Checking no-source RAG answer avoids AI call"
    $noSource = Invoke-JsonApi -Method POST -Path "/api/ai/rag/chat" -Headers $authHeaders -Body @{
        conversationId = $conversationId
        question       = "zebra-only-topic-without-document-match"
        documentIds    = @($documentId)
    }
    Assert-Code $noSource 200 "no-source rag chat"
    Assert-True (@($noSource.data.sources).Count -eq 0) "no-source response has no sources"
    Assert-True ($noSource.data.answer -like "*资料中没有找到足够依据*") "no-source response is explicit"

    if ($SkipAi) {
        Write-Step "Skipping AI RAG chat because -SkipAi was provided"
        Write-Step "Stage 8 non-AI test passed"
        exit 0
    }

    Write-Step "Calling RAG chat with matching document context"
    $ragChat = Invoke-JsonApi -Method POST -Path "/api/ai/rag/chat" -Headers $authHeaders -Body @{
        conversationId = $conversationId
        question       = "According to my document, how does HashMap retrieve values?"
        documentIds    = @($documentId)
    }
    Assert-Code $ragChat 200 "rag chat"
    Assert-True (-not [string]::IsNullOrWhiteSpace($ragChat.data.answer)) "rag answer exists"
    Assert-True (@($ragChat.data.sources).Count -ge 1) "rag answer has sources"

    Write-Step "Checking RAG chat history"
    $messages = Invoke-JsonApi -Method GET -Path "/api/conversations/$conversationId/messages" -Headers $authHeaders
    Assert-Code $messages 200 "rag messages"
    Assert-True (@($messages.data).Count -ge 4) "rag conversation has messages"

    Write-Step "Stage 8 integration test passed"
} finally {
    if ([System.IO.File]::Exists($tempFile)) {
        [System.IO.File]::Delete($tempFile)
    }
}
