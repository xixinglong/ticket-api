---
type: note
status: growing
created: 2026-10-31
updated: 2026-10-31
tags:
  - career/java
aliases: []
---

# 03-04-登录权限与Session练习

本节的目标是用两个账号证明“别人不能看和改我的工单”。操作在 PowerShell B，Java 服务保持在 A 运行。不要先在 DBeaver 手工修改 creator_id 来假装登录。

## 1. 四个账号与两种权限

| 用户名 | 编号 | 角色 | 能力 |
| --- | --- | --- | --- |
| alice | 1001 | USER | 创建、查询自己的工单、取消自己的 OPEN |
| bob | 1002 | USER | 同上，数据与 alice 隔离 |
| agent1 | 2001 | AGENT | 看待接单及自己的工单、接单、完成自己的工单 |
| agent2 | 2002 | AGENT | 同上，与 agent1 竞争接单 |

账号定义在 SecurityConfig，密码从 LAB_* 环境读取后编码。当前账号保存在内存，不是数据库用户管理系统；不存在注册接口和用户表。ticket_app 是数据库账号，不能拿它登录 /api/login。

“已登录”“角色允许”“这张工单归我可见”是三次不同判断。登录不等于能访问所有数据，AGENT 也不是管理员。

## 2. Session 与 CSRF 的最小流程

~~~text
取 CSRF token → 带 token 表单登录 → 保存 Session Cookie
            → 重新取 token → 查询 /api/me → 发送业务请求
~~~

Session 保存服务端认证状态，Cookie 让下一次请求关联同一个会话。CSRF token 用于检查携带 Cookie 的修改请求是否有预期的令牌。查询使用 GET，修改使用 POST；登录、退出也要携带有效 token。

登录成功会更新会话相关安全状态，旧 token 不应继续使用。CSRF 失败不能通过关闭防护“解决”，应先检查是否用同一个 WebSession 并重新取 token。

## 3. 手动登录 alice

在 B 中执行。密码输入你启动 A 时设置的 LAB_ALICE_PASSWORD 对应值：

~~~powershell
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod -Uri "$base/api/csrf" -SessionVariable aliceSession
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$loginSecret = Read-Host '输入 alice 登录密码' -AsSecureString
try {
    $loginBody = @{
        username = 'alice'
        password = [System.Net.NetworkCredential]::new('', $loginSecret).Password
    }
    $loginResult = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/login" -WebSession $aliceSession -Headers $headers -ContentType 'application/x-www-form-urlencoded' -Body $loginBody
    $loginResult.StatusCode
} finally {
    Remove-Variable loginSecret, loginBody -ErrorAction SilentlyContinue
}
$csrf = Invoke-RestMethod -Uri "$base/api/csrf" -WebSession $aliceSession
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod -Uri "$base/api/me" -WebSession $aliceSession
~~~

预期登录 204，/api/me 返回 id=1001、username=alice、roles 包含 USER。204 没有 JSON 响应体，不要对空内容 ConvertFrom-Json。若仍是 401，检查登录结果、密码和 WebSession；不要把“没抛异常”当作所有步骤成功。

## 4. 创建、查询并保留实际编号

~~~powershell
$json = @{ title = '登录后页面空白'; description = '登录成功后进入首页没有内容' } | ConvertTo-Json
$created = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$base/api/tickets" -WebSession $aliceSession -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
$created.StatusCode
$ticket = $created.Content | ConvertFrom-Json
$ticket
Invoke-RestMethod -Uri "$base/api/tickets/$($ticket.id)" -WebSession $aliceSession
~~~

预期创建 201，查询 200，状态 OPEN，返回同一 id、标题和描述。再用 DBeaver 查询实际 id，应有 creator_id=1001，assignee_id=NULL。接口不接收可信的创建人编号；附加 creatorId 字段也不能改变服务端身份来源。

标题不能只包含空格，长度不能超过 100。description 允许省略。JSON 的字符串 id 可以用于 URL，PowerShell 的 $($ticket.id) 是把对象属性放进字符串。

## 5. 用现有脚本完成隔离验收

在 B 的项目目录执行：

~~~powershell
.\scripts\Test-TicketPermissions.ps1
~~~

脚本分别询问 alice 和 bob 登录密码，创建测试工单，再验证未登录、跨账号查询、跨账号取消、缺少 CSRF、正确取消、退出等场景。按脚本打印的编号核对数据库，不使用上次编号代替。

| 状态码 | 本项目含义 |
| --- | --- |
| 401 | 未登录、登录失效或登录失败 |
| 403 | 角色不允许，或已登录但 CSRF 无效 |
| 404 | 工单不存在或对当前人不可见 |
| 409 | 可见工单的状态不允许该动作 |
| 204 | 动作成功，无响应体 |

对于别人的工单返回 404，可以避免通过响应区分“没有这张单”与“有但不是你的”。不能改成“先告诉对方真实状态，再拒绝操作”。

## 6. 曾经的 403 变 204 是什么原因

PowerShell WebRequestSession 可能保留请求头。做“缺少 CSRF”测试时，只是不再传 -Headers，未必已经清掉会话中的旧头。测试必须显式移除对应 header 后发送，再检查响应和数据是否改变。否则测试实际上仍带着有效 token，返回 204 并不能证明后端没校验。

这说明验证代码也会有错误。先确认请求实际携带的内容，再调整后端安全规则。已有脚本处理了这个练习问题。

## 练习与答案

题：bob 在取消 URL 后加 ?actorId=1001，是否能取消 alice 的工单？答：不能，CurrentUser 从认证上下文读取身份，Service 再检查创建人，查询参数不参与身份决策。

题：服务重启后查询返回 401，是数据库数据丢了吗？答：先重新登录；Session 与数据库持久化是两件事。

证据：用户已提供工单 34 的权限脚本成功结果；这不代表今天新增的错误响应测试已运行。关联 [[03-05-取消工单与事务回滚详解]]；来源 SecurityConfig、CurrentUser、AuthController 和权限脚本。
