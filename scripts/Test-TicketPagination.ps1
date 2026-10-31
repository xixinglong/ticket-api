# 分页练习会创建五张工单并保留数据；运行期间请暂停其他创建/接单操作。
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

function Get-TicketPage {
    param($Client, [int]$Page = 1, [int]$Size = 20)
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/tickets?page=$Page&size=$Size" -WebSession $Client.Session
}

function Get-AllVisibleTickets {
    param($Client)
    $all = [System.Collections.Generic.List[object]]::new()
    $expectedTotal = $null
    for ($pageNumber = 1; $pageNumber -le 1000; $pageNumber++) {
        $result = Get-TicketPage -Client $Client -Page $pageNumber -Size 100
        if ($null -eq $expectedTotal) { $expectedTotal = [long]$result.total }
        if ([long]$result.total -ne $expectedTotal) { throw '翻页期间总数发生变化，请暂停其他操作后重新运行' }
        foreach ($item in @($result.items)) { $all.Add($item) }
        if ([long]$pageNumber * 100 -ge $expectedTotal) { break }
        if ($pageNumber -eq 1000) { throw '超过练习脚本的 1000 页上限' }
    }
    if ($all.Count -ne $expectedTotal) { throw '分页累计数量与 total 不一致' }
    for ($i = 1; $i -lt $all.Count; $i++) {
        if ([long]$all[$i - 1].id -le [long]$all[$i].id) { throw '编号未严格倒序或跨页出现重复' }
    }
    return [PSCustomObject]@{ Items = $all.ToArray(); Total = $expectedTotal }
}

function New-PracticeTicket {
    param($Client, [string]$Title)
    $json = @{ title = $Title; description = 'Pagination practice fixture' } | ConvertTo-Json
    return Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/tickets" -WebSession $Client.Session -Headers $Client.Headers -ContentType 'application/json' -Body $json
}

function Assert-VisibleFixtures {
    param($Client, [string]$Name, [object[]]$AllFixtureIds, [object[]]$ExpectedIds, [long]$BeforeTotal)
    $result = Get-AllVisibleTickets -Client $Client
    $actualIds = @($result.Items | ForEach-Object { [string]$_.id })
    foreach ($id in $AllFixtureIds) {
        $expected = @($ExpectedIds | ForEach-Object { [string]$_ }) -contains [string]$id
        $actual = $actualIds -contains [string]$id
        if ($actual -ne $expected) { throw "$Name 对工单 $id 的可见范围错误" }
    }
    if ($result.Total -ne ($BeforeTotal + $ExpectedIds.Count)) { throw "$Name 的 total 增量不符合可见范围" }
    $first = Get-TicketPage -Client $Client -Page 1 -Size 2
    $second = Get-TicketPage -Client $Client -Page 2 -Size 2
    $firstIds = @($first.items | ForEach-Object { [string]$_.id })
    $secondIds = @($second.items | ForEach-Object { [string]$_.id })
    foreach ($id in $firstIds) {
        if ($secondIds -contains $id) { throw "$Name 的第一页与第二页重复" }
    }
    $combined = @($firstIds) + @($secondIds)
    $expectedFirst = @($actualIds | Select-Object -First 4)
    if (($combined -join ',') -ne ($expectedFirst -join ',')) { throw "$Name 的不同 page size 排序不一致" }
    Write-Host "验证通过：$Name 的数据范围、总数、倒序和分页均正确（total=$($result.Total)）"
}

$clients = @()
try {
    $alice = Connect-LabUser 'alice'; $clients += $alice
    $bob = Connect-LabUser 'bob'; $clients += $bob
    $agent1 = Connect-LabUser 'agent1'; $clients += $agent1
    $agent2 = Connect-LabUser 'agent2'; $clients += $agent2
    $beforeAlice = (Get-TicketPage -Client $alice).total
    $beforeBob = (Get-TicketPage -Client $bob).total
    $beforeAgent1 = (Get-TicketPage -Client $agent1).total
    $beforeAgent2 = (Get-TicketPage -Client $agent2).total

    Assert-HttpStatus -Expected 401 -Request @{ Method = 'Get'; Uri = "$BaseUrl/api/tickets" }
    foreach ($query in @('page=0', 'page=-1', 'page=abc', 'page=2147483648', 'size=0', 'size=101', 'size=abc')) {
        Assert-HttpStatus -Expected 400 -Request @{ Method = 'Get'; Uri = "$BaseUrl/api/tickets?$query"; WebSession = $alice.Session }
    }

    $aliceOpen = New-PracticeTicket -Client $alice -Title 'Pagination Alice open'
    $bobOpen = New-PracticeTicket -Client $bob -Title 'Pagination Bob open'
    $aliceCancelled = New-PracticeTicket -Client $alice -Title 'Pagination Alice cancelled'
    $aliceResolved = New-PracticeTicket -Client $alice -Title 'Pagination Alice resolved'
    $bobAssigned = New-PracticeTicket -Client $bob -Title 'Pagination Bob assigned'
    $fixtureIds = @($aliceOpen.id, $bobOpen.id, $aliceCancelled.id, $aliceResolved.id, $bobAssigned.id)
    Write-Host "本次练习工单编号：$($fixtureIds -join ', ')"
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($aliceCancelled.id)/cancel"; WebSession = $alice.Session; Headers = $alice.Headers }
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($aliceResolved.id)/claim"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($aliceResolved.id)/resolve"; WebSession = $agent1.Session; Headers = $agent1.Headers }
    Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/tickets/$($bobAssigned.id)/claim"; WebSession = $agent2.Session; Headers = $agent2.Headers }

    Assert-VisibleFixtures -Client $alice -Name 'alice' -AllFixtureIds $fixtureIds -ExpectedIds @($aliceOpen.id, $aliceCancelled.id, $aliceResolved.id) -BeforeTotal $beforeAlice
    Assert-VisibleFixtures -Client $bob -Name 'bob' -AllFixtureIds $fixtureIds -ExpectedIds @($bobOpen.id, $bobAssigned.id) -BeforeTotal $beforeBob
    Assert-VisibleFixtures -Client $agent1 -Name 'agent1' -AllFixtureIds $fixtureIds -ExpectedIds @($aliceOpen.id, $bobOpen.id, $aliceResolved.id) -BeforeTotal $beforeAgent1
    Assert-VisibleFixtures -Client $agent2 -Name 'agent2' -AllFixtureIds $fixtureIds -ExpectedIds @($aliceOpen.id, $bobOpen.id, $bobAssigned.id) -BeforeTotal $beforeAgent2

    $normal = Get-TicketPage -Client $alice
    $forged = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/tickets?creatorId=1002&actorId=2001&userRole=false&sort=id" -WebSession $alice.Session
    if ($forged.total -ne $normal.total -or (($forged.items.id -join ',') -ne ($normal.items.id -join ','))) { throw '伪造身份参数改变了查询范围' }
    $farPage = Get-TicketPage -Client $alice -Page 2147483647 -Size 100
    if (@($farPage.items).Count -ne 0 -or $farPage.total -ne $normal.total) { throw '超出末页应返回空 items，并保留真实 total' }
    Write-Host '验证通过：伪造参数无效，较大页码无溢出。'
    Write-Host "练习完成。DBeaver 查询：SELECT id, creator_id, assignee_id, status FROM ticket_lab.tickets WHERE id IN ($($fixtureIds -join ', ')) ORDER BY id DESC;"
    $normal.items | Format-Table id, title, status
} finally {
    foreach ($client in $clients) {
        try {
            Assert-HttpStatus -Expected 204 -Request @{ Method = 'Post'; Uri = "$BaseUrl/api/logout"; WebSession = $client.Session; Headers = $client.Headers }
        } catch {
            Write-Warning "退出练习会话失败：$($_.Exception.Message)"
        }
    }
}
