# 在第二个 PowerShell 窗口执行；先确保服务已启动并设置过两个账号的密码。
# 本脚本会创建并取消一张专用练习工单，保留它及其操作记录供 DBeaver 查看。
param([string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'

function Connect-LabUser {
    param([string]$Username)
    $loginCsrf = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/csrf" -SessionVariable loginSession
    $securePassword = Read-Host "输入 $Username 的登录密码（不是 MySQL 密码）" -AsSecureString
    $loginBody = @{
        username = $Username
        password = [System.Net.NetworkCredential]::new('', $securePassword).Password
    }
    $loginHeaders = @{}
    $loginHeaders[$loginCsrf.headerName] = $loginCsrf.token
    try {
        $loginResult = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$BaseUrl/api/login" -WebSession $loginSession -Headers $loginHeaders -ContentType 'application/x-www-form-urlencoded' -Body $loginBody
        if ($loginResult.StatusCode -ne 204) { throw '登录未返回 204' }
    } finally {
        $loginBody.Clear()
        $securePassword.Dispose()
    }
    # 登录后重新获取 token，继续复用同一个 WebSession，以便携带 Session Cookie。
    $csrf = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/csrf" -WebSession $loginSession
    $headers = @{}
    $headers[$csrf.headerName] = $csrf.token
    return [PSCustomObject]@{ Session = $loginSession; Headers = $headers }
}

function Assert-HttpStatus {
    param([hashtable]$Request, [int]$Expected)
    try {
        $response = Invoke-WebRequest -UseBasicParsing @Request
        $actual = [int]$response.StatusCode
    } catch {
        if ($null -eq $_.Exception.Response) { throw }
        $actual = [int]$_.Exception.Response.StatusCode
    }
    if ($actual -ne $Expected) { throw "预期 HTTP $Expected，实际 $actual" }
    Write-Host "验证通过：HTTP $actual"
}

$alice = Connect-LabUser 'alice'
$bob = Connect-LabUser 'bob'
$json = @{ title = 'User isolation practice'; description = 'Created by alice' } | ConvertTo-Json
$ticket = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/tickets" -WebSession $alice.Session -Headers $alice.Headers -ContentType 'application/json' -Body $json
Write-Host "已创建工单：$($ticket.id)，预期创建人编号 1001"
Assert-HttpStatus -Expected 200 -Request @{ Method = 'Get'; Uri = "$BaseUrl/api/tickets/$($ticket.id)"; WebSession = $alice.Session }
Assert-HttpStatus -Expected 404 -Request @{ Method = 'Get'; Uri = "$BaseUrl/api/tickets/$($ticket.id)"; WebSession = $bob.Session }
Assert-HttpStatus -Expected 404 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($ticket.id)/cancel"; WebSession = $bob.Session; Headers = $bob.Headers }
# WebSession 会保留先前通过 -Headers 传入的头；省略参数不会自动清除它。
# 只移除 CSRF 头，保留登录 Cookie，才能验证“已登录但缺少 token”。
foreach ($headerName in $alice.Headers.Keys) {
    [void]$alice.Session.Headers.Remove($headerName)
}
Assert-HttpStatus -Expected 403 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($ticket.id)/cancel"; WebSession = $alice.Session }
Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($ticket.id)/cancel"; WebSession = $alice.Session; Headers = $alice.Headers }
$found = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/tickets/$($ticket.id)" -WebSession $alice.Session
if ($found.status -ne 'CANCELLED') { throw '取消后的状态不是 CANCELLED' }
Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/logout"; WebSession = $alice.Session; Headers = $alice.Headers }
Assert-HttpStatus -Expected 401 -Request @{ Method = 'Get'; Uri = "$BaseUrl/api/me"; WebSession = $alice.Session }
Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/logout"; WebSession = $bob.Session; Headers = $bob.Headers }
Write-Host "练习完成：在 DBeaver 查询 tickets.id = $($ticket.id) 和 ticket_events.ticket_id = $($ticket.id)。"
$found
