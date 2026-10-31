---
type: note
status: growing
created: 2026-10-31
updated: 2026-10-31
tags:
  - career/java
aliases: []
---

# 03-03-MyBatis与环境配置

本节把 Java 与数据库连起来。先在 PowerShell A 设置配置，再读 IDE 中的 Mapper，最后在 PowerShell B 和 DBeaver 交叉验证。命令供后续手动执行；本轮整理没有启动 Java 或执行 Maven。

## 1. 为什么配置里写变量

application.properties 已包含 spring.datasource.url、username、password，值引用 DB_URL、DB_USER、DB_PASSWORD 环境变量。配置文件负责声明“读取哪个参数”，终端负责提供“这台机器的具体值”。这样不会把真实密码提交到仓库，同一份代码也能连接不同练习库。

DB_PASSWORD 是 MySQL 的 ticket_app 密码；LAB_ALICE_PASSWORD 等是登录工单 API 的密码。二者不可混用。DBeaver 保存的密码不会自动传给 Java。

## 2. 一次准备完整启动环境

在 PowerShell A 的项目目录执行。不要输出环境变量中的密码值：

~~~powershell
Set-Location 'C:\xixl-study\java-labs\ticket-api'
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/ticket_lab'
$env:DB_USER = 'ticket_app'

function Set-LabSecret([string]$Name, [string]$Prompt) {
    $secretValue = Read-Host $Prompt -AsSecureString
    [Environment]::SetEnvironmentVariable(
        $Name, [System.Net.NetworkCredential]::new('', $secretValue).Password, 'Process')
    Remove-Variable secretValue
}
Set-LabSecret 'DB_PASSWORD' '输入 ticket_app 的数据库密码'
Set-LabSecret 'LAB_ALICE_PASSWORD' '设置 alice 登录密码'
Set-LabSecret 'LAB_BOB_PASSWORD' '设置 bob 登录密码'
Set-LabSecret 'LAB_AGENT1_PASSWORD' '设置 agent1 登录密码'
Set-LabSecret 'LAB_AGENT2_PASSWORD' '设置 agent2 登录密码'
Remove-Item Function:\Set-LabSecret
~~~

环境变量在运行进程中仍是明文，这种做法是避免把口令写进文件和命令历史，并非密码保险箱。关闭窗口后需重新设置；不要把这段改成带真实密码的固定赋值保存进笔记。

只检查是否缺失，不展示内容：

~~~powershell
$required = 'DB_URL','DB_USER','DB_PASSWORD','LAB_ALICE_PASSWORD','LAB_BOB_PASSWORD','LAB_AGENT1_PASSWORD','LAB_AGENT2_PASSWORD'
$required | ForEach-Object {
    [pscustomobject]@{ Name = $_; Present = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) }
}
~~~

测试 profile 使用测试配置中的演示登录密码；数据库地址仍来自 DB_*。集成测试会读写数据库，应使用专用练习库。正式启动使用你刚设置的四个 LAB_* 密码。

## 3. Mapper 接口与 XML 怎么配对

打开 TicketMapper.java 与 resources/mapper/TicketMapper.xml。

~~~java
TicketRow findById(@Param("id") long id);
~~~

XML 的 namespace 是接口全名 com.example.ticket.TicketMapper；select 的 id 是方法名 findById。@Param("id") 给参数命名，SQL 中使用 #{id} 绑定值。名称不一致会让 MyBatis 找不到参数或语句。

不要自己拼接用户输入到 SQL。#{...} 是参数值绑定；不能拿它作为任意表名、列名或排序方向替换。本项目固定排序 id DESC，不接收客户端任意 SQL 片段。

XML resultMap 明确把 creator_id 映射到 creatorId，assignee_id 映射到 assigneeId。数据库习惯下划线，Java 字段习惯驼峰，显式映射把二者连接起来。

## 4. 创建后为什么立刻能拿到 id

insert 语句使用 useGeneratedKeys 和 keyProperty="id"。MySQL 创建自增编号，MyBatis 将编号回填进同一个 TicketRow 对象。Service 再把这个 id 转成字符串放进 TicketResponse。

mapper.insert 的返回值是受影响行数，不是新编号。误把返回的 1 当成工单 id，会让每次创建都查同一个工单。

description 可省略，数据库保存 NULL；当前没有描述长度的应用层上限，超大文本仍可能被数据库拒绝。这是已知限制，不要写成“任何长度都支持”。

## 5. 后续手动验证顺序

1. 确认 MySQL 正在运行，两张表存在，DB_* 指向练习库。
2. 获准运行测试后，在 A 执行 .\mvnw.cmd test，看整体结果，而不是只看某个类通过。
3. 在 A 执行 .\mvnw.cmd spring-boot:run，等待 Started TicketApiApplication 与端口 8080 启动信息。
4. 在 B 按 [[03-04-登录权限与Session练习]] 登录、创建并记下 id。
5. 在 DBeaver 查相同 id，核对 title、description、creator_id、status。
6. 停止并重新启动 Java；重新登录，再查询原 id。不要重新创建来代替重启验证。

Session 是内存状态，服务重启后需要重新登录；工单在 MySQL 中，仍应保留。会话失效返回 401，不代表工单丢失。

## 6. 常见报错的检查顺序

| 现象 | 先检查 |
| --- | --- |
| 缺少 DB_URL 或 LAB_* 占位符 | 是否在同一个启动终端设置；IDE 是否有自己的环境配置 |
| Access denied | ticket_app 密码、主机授权、连接的 MySQL 实例 |
| Communications link failure | 服务是否运行、地址端口是否正确 |
| 表不存在或 description 列不存在 | 当前库名与实际结构，禁止先删表 |
| Invalid bound statement | XML 是否被 mapper-locations 加载、namespace 和方法名是否对应 |
| 8080 already in use | 是否旧 Java 仍在 A 运行，先确认后停止旧实例 |

练习题：DBeaver 能查询但 Java 无法连接，能否证明 MyBatis SQL 写错？不能。先确认 Java 读到的连接环境正确，再检查日志的第一个有效异常。

来源：pom.xml、application.properties、TicketMapper.java、TicketMapper.xml；关联 [[03-08-测试排错与部署操作手册]]。
