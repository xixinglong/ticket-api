# 完成工单练习：新建专用工单，保留 CLAIM 和 RESOLVE 记录供 DBeaver 核对。
# 在第二个 PowerShell 窗口执行，先按文档重启已更新的服务。
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

    $json = @{ title = 'Ticket resolution practice'; description = 'Created by alice, claimed and resolved by agent1' } | ConvertTo-Json
    $ticket = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/tickets" -WebSession $alice.Session -Headers $alice.Headers -ContentType 'application/json' -Body $json
    $ticketUrl = "$BaseUrl/api/tickets/$($ticket.id)"
    Write-Host "已创建工单：$($ticket.id)，预期创建人为 1001，接单与完成人为 2001"

    Assert-HttpStatus -Expected 401 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve" }
    Assert-HttpStatus -Expected 403 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve"; WebSession = $alice.Session; Headers = $alice.Headers }
    # 处理人看得见 OPEN 工单，但必须先接单，不能跳过中间状态。
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 404 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve?actorId=2001"; WebSession = $agent2.Session; Headers = $agent2.Headers }

    # WebSession 会记住请求头；显式清理后才是在验证缺少 CSRF token。
    foreach ($headerName in $agent1.Headers.Keys) {
        [void]$agent1.Session.Headers.Remove($headerName)
    }
    Assert-HttpStatus -Expected 403 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve"; WebSession = $agent1.Session }
    $before = Invoke-RestMethod -Method Get -Uri $ticketUrl -WebSession $agent1.Session
    if ($before.status -ne 'IN_PROGRESS') { throw '失败请求不应改变 IN_PROGRESS 状态' }

    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve?actorId=2002&status=OPEN"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/resolve"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/claim"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 409 -Request @{ Method = 'Post'; Uri = "$ticketUrl/cancel"; WebSession = $alice.Session; Headers = $alice.Headers }
    Assert-HttpStatus -Expected 404 -Request @{ Method = 'Get'; Uri = $ticketUrl; WebSession = $agent2.Session }
    $found = Invoke-RestMethod -Method Get -Uri $ticketUrl -WebSession $agent1.Session
    $creatorView = Invoke-RestMethod -Method Get -Uri $ticketUrl -WebSession $alice.Session
    if ($found.status -ne 'RESOLVED' -or $creatorView.status -ne 'RESOLVED') { throw '处理人与创建人都应看到 RESOLVED' }

    Write-Host "完成接口验证通过。在 DBeaver 查询 tickets.id = $($ticket.id) 和 ticket_events.ticket_id = $($ticket.id)。"
    Write-Host '预期：RESOLVED、assignee_id = 2001；依次有 CLAIM、RESOLVE 两条记录，actor_id 均为 2001。'
    $found
} finally {
    foreach ($client in $clients) {
        try {
            Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/logout"; WebSession = $client.Session; Headers = $client.Headers }
        } catch {
            Write-Warning "退出练习会话失败：$($_.Exception.Message)"
        }
    }
}
