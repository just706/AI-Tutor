param(
    [string]$BaseUrl = "http://localhost:8080",
    [switch]$SkipAi
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Net.Http

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
        [Parameter(Mandatory = $true)][ValidateSet("GET", "POST", "DELETE")][string]$Method,
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
        $contentType = if ([System.IO.Path]::GetExtension($FilePath).ToLowerInvariant() -eq ".pdf") {
            "application/pdf"
        } else {
            "text/plain"
        }
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($contentType)
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

function New-TestPdfFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $safeText = $Text.Replace("\", "\\").Replace("(", "\(").Replace(")", "\)")
    $objects = @(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length PLACEHOLDER >>`nstream`nBT /F1 18 Tf 72 720 Td ($safeText) Tj ET`nendstream"
    )
    $contentStream = "BT /F1 18 Tf 72 720 Td ($safeText) Tj ET`n"
    $objects[4] = $objects[4].Replace("PLACEHOLDER", ([System.Text.Encoding]::ASCII.GetByteCount($contentStream)).ToString())

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append("%PDF-1.4`n")
    $offsets = New-Object System.Collections.Generic.List[int]
    for ($i = 0; $i -lt $objects.Count; $i++) {
        $offsets.Add([System.Text.Encoding]::ASCII.GetByteCount($builder.ToString()))
        [void]$builder.Append("$($i + 1) 0 obj`n$($objects[$i])`nendobj`n")
    }
    $xrefOffset = [System.Text.Encoding]::ASCII.GetByteCount($builder.ToString())
    [void]$builder.Append("xref`n0 6`n")
    [void]$builder.Append("0000000000 65535 f `n")
    foreach ($offset in $offsets) {
        [void]$builder.Append(("{0:0000000000} 00000 n `n" -f $offset))
    }
    [void]$builder.Append("trailer`n<< /Size 6 /Root 1 0 R >>`nstartxref`n$xrefOffset`n%%EOF`n")

    [System.IO.File]::WriteAllBytes($Path, [System.Text.Encoding]::ASCII.GetBytes($builder.ToString()))
}

Write-Step "Testing backend at $BaseUrl"

$health = Invoke-JsonApi -Method GET -Path "/api/health"
Assert-Code $health 200 "health check"

$stamp = Get-Date -Format "yyyyMMddHHmmssfff"
$username = "stage8_test_$stamp"
$password = "123456"
$tempFile = Join-Path ([System.IO.Path]::GetTempPath()) "ai-tutor-rag-$stamp.md"
$tempPdfFile = Join-Path ([System.IO.Path]::GetTempPath()) "ai-tutor-rag-$stamp.pdf"

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

    Write-Step "Reading document detail and chunks"
    $detail = Invoke-JsonApi -Method GET -Path "/api/documents/$documentId" -Headers $authHeaders
    Assert-Code $detail 200 "document detail"
    Assert-True ($detail.data.id -eq $documentId) "document detail id"
    Assert-True (-not [string]::IsNullOrWhiteSpace($detail.data.preview)) "document preview exists"

    $chunks = Invoke-JsonApi -Method GET -Path "/api/documents/$documentId/chunks" -Headers $authHeaders
    Assert-Code $chunks 200 "document chunks"
    Assert-True (@($chunks.data).Count -gt 0) "document chunks exist"

    Write-Step "Reprocessing document"
    $reprocess = Invoke-JsonApi -Method POST -Path "/api/documents/$documentId/reprocess" -Headers $authHeaders
    Assert-Code $reprocess 200 "reprocess document"
    Assert-True ($reprocess.data.processStatus -eq "completed") "reprocess completed"
    Assert-True ($reprocess.data.chunkCount -gt 0) "reprocess chunks created"

    Write-Step "Uploading PDF document"
    New-TestPdfFile -Path $tempPdfFile -Text "HashMap PDF retrieval bucket collision"
    $pdfUpload = Invoke-MultipartApi -Path "/api/documents/upload" -FilePath $tempPdfFile -Token $token
    Assert-Code $pdfUpload 200 "upload pdf document"
    $pdfDocumentId = $pdfUpload.data.documentId
    Assert-True ($pdfDocumentId -gt 0) "uploaded pdf document id"
    Assert-True ($pdfUpload.data.processStatus -eq "completed") "pdf process completed"

    $pdfDetail = Invoke-JsonApi -Method GET -Path "/api/documents/$pdfDocumentId" -Headers $authHeaders
    Assert-Code $pdfDetail 200 "pdf document detail"
    Assert-True ($pdfDetail.data.fileType -eq "pdf") "pdf detail type"
    Assert-True ($pdfDetail.data.preview -like "*HashMap*") "pdf preview text"

    Write-Step "Deleting PDF document"
    $deletePdf = Invoke-JsonApi -Method DELETE -Path "/api/documents/$pdfDocumentId" -Headers $authHeaders
    Assert-Code $deletePdf 200 "delete pdf document"
    $documentsAfterDelete = Invoke-JsonApi -Method GET -Path "/api/documents" -Headers $authHeaders
    Assert-Code $documentsAfterDelete 200 "list documents after delete"
    $deletedDocument = @($documentsAfterDelete.data) | Where-Object { $_.id -eq $pdfDocumentId } | Select-Object -First 1
    Assert-True ($null -eq $deletedDocument) "deleted document no longer listed"

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
    Assert-True (-not [string]::IsNullOrWhiteSpace($noSource.data.answer)) "no-source response is explicit"

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
    if ([System.IO.File]::Exists($tempPdfFile)) {
        [System.IO.File]::Delete($tempPdfFile)
    }
}
