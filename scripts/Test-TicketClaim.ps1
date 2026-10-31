# 在第二个 PowerShell 窗口执行，先按接单练习文档启动服务。
# 保留专用工单及 CLAIM 记录，方便在 DBeaver 核对；本脚本验证顺序请求。
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

$clients = @()
try {
    $alice = Connect-LabUser 'alice'
    $clients += $alice
    $agent1 = Connect-LabUser 'agent1'
    $clients += $agent1
    $agent2 = Connect-LabUser 'agent2'
    $clients += $agent2

    $json = @{ title = 'Agent claim practice'; description = 'Created by alice, claimed by agent1' } | ConvertTo-Json
    $ticket = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/tickets" -WebSession $alice.Session -Headers $alice.Headers -ContentType 'application/json' -Body $json
    $ticketUrl = "$BaseUrl/api/tickets/$($ticket.id)"
    Write-Host "已创建工单：$($ticket.id)，创建人应为 1001，接单成功后处理人应为 2001"

    Assert-HttpStatus -Expected 401 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim" }
    Assert-HttpStatus -Expected 403 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $alice.Session; Headers = $alice.Headers }
    Assert-HttpStatus -Expected 200 -Request @{ Method = 'Get'; Uri = $ticketUrl; WebSession = $agent1.Session }
    Assert-HttpStatus -Expected 200 -Request @{ Method = 'Get'; Uri = $ticketUrl; WebSession = $agent2.Session }

    # 先清理 WebSession 保存的 CSRF 头，才能真正发送“已登录但没有 token”的请求。
    foreach ($headerName in $agent1.Headers.Keys) {
        [void]$agent1.Session.Headers.Remove($headerName)
    }
    Assert-HttpStatus -Expected 403 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $agent1.Session }
    # 伪造 assigneeId 不应生效；最终在数据库核对处理人仍为当前身份 2001。
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim?assigneeId=2002"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 404 -Request @{ Method = 'Get'; Uri = $ticketUrl; WebSession = $agent2.Session }
    Assert-HttpStatus -Expected 404 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $agent2.Session; Headers = $agent2.Headers }
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/cancel"; WebSession = $alice.Session; Headers = $alice.Headers }

    $found = Invoke-RestMethod -Method Get -Uri $ticketUrl -WebSession $agent1.Session
    if ($found.status -ne 'IN_PROGRESS') { throw '接单后的状态不是 IN_PROGRESS' }
    Assert-HttpStatus -Expected 200 -Request @{ Method = 'Get'; Uri = $ticketUrl; WebSession = $alice.Session }
    Write-Host "接单接口验证完成。在 DBeaver 查询 tickets.id = $($ticket.id) 和 ticket_events.ticket_id = $($ticket.id)。"
    Write-Host '预期：IN_PROGRESS、assignee_id = 2001，且只有一条 actor_id = 2001 的 CLAIM 记录。'
    $found
} finally {
    # 即使某步失败，也尽量结束已建立的登录会话，保留工单供排查。
    foreach ($client in $clients) {
        try {
            Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/logout"; WebSession = $client.Session; Headers = $client.Headers }
        } catch {
            Write-Warning "退出练习会话失败：$($_.Exception.Message)"
        }
    }
}
