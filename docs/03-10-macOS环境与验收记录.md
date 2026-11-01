---
type: project
status: active
created: 2026-11-01
updated: 2026-11-01
tags:
  - career/java
---

# 03-10-macOS环境与验收记录

> 本文保留用户模块迁移前的验收快照；随后已切换数据库用户并通过 96 项测试。最新账号实现、配置变化和业务库迁移结果见 [[03-用户模块实施与双机迁移记录]]，不再以本文“内存账号、缺少 LAB_* 启动失败”描述判断新版行为。

## 1. 当前范围

这是 2026-11-01 的本机后端验收记录，补充而不覆盖 2026-10-31 的 Windows 历史记录。主线见 [[03 工单项目交付：数据库、权限、事务与部署]]。

当前项目：`/Users/mac/xixl-work/xixl-study/ticket-api`。
笔记同步目标：项目 `docs` 与 `/Users/mac/xixl-work/obsidian-md/java快速转岗`，复制同步，不移动原文件，不保存真实密码。

后端仍使用四个内存练习账号；数据库用户表、用户关联外键没有实现，不能算作完整用户模块。前端按此前安排暂停。本次没有扩展这两项，也没有做公网、HTTPS、压力或生产部署验收。

## 2. 环境与凭据

| 项目 | 当前配置 |
| --- | --- |
| 系统 | macOS ARM64 |
| Java | Microsoft JDK 21.0.12.1 |
| Maven | Wrapper 3.10.0，通过 `sh ./mvnw` 执行 |
| 应用 | Spring Boot 4.1.2、MyBatis Starter 4.1.0 |
| MySQL | 8.4.13，127.0.0.1:3306 |
| DBeaver | Community 26.2.3，`/Applications/DBeaver.app` |
| 业务库 | ticket_lab，tickets 与 ticket_events |
| 数据库账号 | ticket_app，仅业务库 SELECT/INSERT/UPDATE/DELETE |
| 应用账号 | alice、bob 为 USER；agent1、agent2 为 AGENT |

数据库账号与网页登录账号不是同一套凭据。数据库凭据位于 `~/.config/mysql84/ticket_app.cnf`；管理员配置位于 `~/.config/mysql84/root.cnf`。应用随机密码位于 `~/.config/ticket-api/accounts.json`。不要把这些文件提交到 Git 或复制进笔记。

DBeaver 使用已保存的 `ticket_lab (ticket_app)` 连接，主机 127.0.0.1、端口 3306、库 ticket_lab、用户 ticket_app，SSL 模式 REQUIRED。业务账号不能建表；建表使用管理员连接，并先确认目标库。原有三条演示工单来自 SQL 种子数据，不是 API 创建验收证据。

## 3. 当前应用启停

MySQL 登录自启配置：`~/Library/LaunchAgents/local.mysql84.plist`。
应用启动脚本：`~/.local/share/ticket-api/run.py`，读取本机凭据并运行项目 target 中的 Jar。
应用 launchd 配置：`~/.local/share/ticket-api/service.plist`，不在 LaunchAgents 目录，未设置为登录自启。

```sh
cd /Users/mac/xixl-work/xixl-study/ticket-api
# 查看已加载服务
launchctl print "gui/$(id -u)/local.ticket-api"
# 停止并卸载当前应用任务
launchctl bootout "gui/$(id -u)/local.ticket-api"
# 重新加载启动；仅在任务未加载时执行
launchctl bootstrap "gui/$(id -u)" "$HOME/.local/share/ticket-api/service.plist"
# 查看日志
tail -n 80 "$HOME/.local/share/ticket-api/server.log"
```

接口地址：`http://127.0.0.1:8080`。这是后端接口服务，不是已完成的前端页面。`/api/csrf` 可匿名访问；业务接口要求登录。重启会清除内存 Session，需要重新登录。

## 4. 可重复验收

```sh
cd /Users/mac/xixl-work/xixl-study/ticket-api
python3 scripts/verify-macos.py
```

脚本依赖上面的本机 JDK、MySQL 路径及管理员配置，并非跨平台安装器。它创建新的 `ticket_verify_时间戳` 数据库，按 `sql/01-schema.sql` 建表，执行 `sh ./mvnw -B package`（包含测试），再用 18080 端口启动独立实例。测试账号密码临时随机生成，不输出。18080 被占用时停止，不接管其他进程。

脚本不向 ticket_lab 写数据，也不停止 8080 服务；会重新生成 target 下 Jar。独立验收进程结束时停止，验收库保留便于核对。管理员仅供本机验收建库及测试，日常应用继续使用 ticket_app；这不是生产环境权限配置示范。

证据保存在 `target/surefire-reports`、`target/macos-acceptance/result.json` 及同目录日志。target 被清理后这些文件会消失，长期保留时应另行归档；日志不要未经检查公开。重复运行覆盖最近一轮摘要，但不会删除旧验收库。

## 5. 本次结果

- 当前全套测试 90 项，通过 90，失败 0、错误 0、跳过 0；包含分页、统一错误、权限、事务回滚、独立事务竞争。
- Maven package 成功。业务源码基于提交 `fbc5ca0ac19a94a43909f490767fdcc513226083`，新增验收脚本和本文未提交。Jar SHA-256 及独立库名以本次 result.json 为准。
- 真实 HTTP 创建返回 201；取消、接单、完成返回 204；详情和分页返回 200。
- 未登录 401、越权角色与缺少 CSRF 403、其他用户或处理人不可见 404、重复状态操作 409、非法页码 400；错误体含 code/message/requestId，编号与响应头一致。
- 数据库核对取消只有一个 CANCEL 事件，另一张工单依次只有 CLAIM、RESOLVE 事件。
- 停止验收实例后重新启动，原 Session 失效；重新登录可查询重启前同一张已完成工单，返回内容一致。
- 登录密码为空、登录密码环境变量缺失分别验证启动失败。
- 数据库连接指向本机无监听端口时，启动本身可能成功；实际查询返回 500 和统一错误结构，不暴露 SQL、口令或异常类。故障详情保留服务端日志。

本次是已配置 Mac 上的独立数据库与独立进程验收，不等于从全新空白电脑完成安装复现。缺少其他配置项、断线后的自动恢复、持久 Session 与生产安全均不在本次验收范围。

## 6. 索引检查与限制

独立库额外生成 10000 条 OPEN、未分配工单，创建人均匀分布在 100 个编号，执行 ANALYZE TABLE 后检查 EXPLAIN。

- 创建人过滤、id 倒序、LIMIT 20：使用 idx_creator_id，ref 访问，Backward index scan。
- 处理人可见条件（OPEN 且未分配，或自己负责）加 id 倒序：本次选择 PRIMARY 索引倒序扫描并过滤。

该分布绝大多数是待接单数据，对处理人列表有利；不能据此认定终态工单占多数、深分页或高并发场景已经优化。当前保留 OFFSET 分页，后续应按真实分布检查 COUNT 和列表查询，再决定索引或游标分页方案。

## 7. 仍未完成

- 数据库用户模块及用户关联约束，需要单独设计迁移和身份兼容方案。
- 前端页面及真实页面联调，按原安排暂缓。
- 全新机器安装复现、公网部署与生产安全验收。

因此可以记录为“本机后端测试及基础部署验收通过”，不能记录为“整个最终交付全部完成”。
