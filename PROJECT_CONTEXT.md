---
type: project
status: active
created: 2026-11-01
updated: 2026-11-01
tags:
  - career/java
---

# 项目上下文

本文件是项目根目录的交接入口，不放入 docs 或 Obsidian 课件目录。当前提交状态以本文件及实际 Git 状态为准。课件名称引用对应 docs 下的同名 Markdown 文件。

目录约定：docs 仅放课件、练习与教学操作说明；项目进度、交接清单、Mac 环境验收及用户迁移实施记录放在根目录 project-records。Obsidian 的课件保留在 java快速转岗 顶层，对应项目记录放在其 project-records 子目录。课件与项目记录分别复制同步；Obsidian 按唯一文件名解析的双链仍可指向移动后的记录。

记录日期：2026-11-01，北京时间。本记录补充 Windows 2026-10-31 上下文，不覆盖原记录，不是对后续构建、数据库修改或远程推送的永久授权。执行前以用户最新指令和实际工作区为准。

## 1. 学习方向与稳定边界

- 优先后端 Java 和适合初学者的课件，前端继续暂停。
- 公共课件保持稳定，机器路径、部署和验收结果写到独立记录。
- 文档复制同步，不移动原笔记；不保存真实口令、令牌或会话值。
- Windows 与 Mac 是并存环境，不把 Windows 降为历史弃用环境。
- 一台电脑测试通过，不代表另一台电脑已经通过。

## 2. 路径与版本

| 项目 | Mac | Windows |
| --- | --- | --- |
| 项目 | /Users/mac/xixl-work/xixl-study/ticket-api | C:\xixl-study\java-labs\ticket-api |
| 项目笔记 | 项目下 docs | 项目下 docs |
| Obsidian | /Users/mac/xixl-work/obsidian-md/java快速转岗 | C:\obsidian-md\java快速转岗 |
| 终端 | zsh | PowerShell |

Mac 本轮开始于提交 `fbc5ca0ac19a94a43909f490767fdcc513226083`，当前分支为 main。用户模块、迁移脚本、测试及阶段文档已本地提交为 `91aa164`，提交说明为 `feat: persist application users and document dual-machine migration`。尚未进行本轮远程推送，也未核实此刻远程是否有其他更新。

该提交使用 Git 自动生成身份 `MAC <mac@MACdeMacBook-Pro.local>`，推送前应由用户确认是否改成常用身份，不擅自修改身份或重写提交。本根目录上下文是在该提交之后新增，尚未提交。

Windows 上下文记录的工作分支是 codex/backend-course-notes。切换机器时先检查 Git 状态，不假设两边分支、文件或 HEAD 相同。Windows 未跟踪的 static 前端草稿应保留，不能误删或混入后端提交。

## 3. 本阶段新增实现

- 新增 users 表、UserRow、UserMapper，将 UserDetailsService 改为查询数据库。
- 保留 alice=1001、bob=1002、agent1=2001、agent2=2002 和既有业务数据关联。
- 使用 `{bcrypt}` 格式哈希，不在数据库保存明文密码。
- enabled 阻止新登录；已有 Session 不会自动失效。密码或角色修改也不即时刷新已有会话。
- 创建人、处理人、事件操作者增加到 users.id 的外键，外键不替代业务角色检查。
- 新增用户模块测试，分页测试补入临时数据库用户以满足外键。
- users 表及字段均补中文备注。

关键文件：

| 文件 | 用途 |
| --- | --- |
| sql/01-schema.sql | 原有两张业务表，仅供尚未建表的库 |
| sql/02-users.sql | 用户表定义；IF NOT EXISTS 不会升级已有表 |
| sql/03-users-comments.sql | 给已有用户表补备注 |
| scripts/MigrateUsers.java | 检查旧用户引用、初始化四账号、添加外键 |
| scripts/migrate-users-macos.py | 本机凭据读取、备份及业务库迁移包装脚本 |
| scripts/verify-macos.py | 创建独立库并运行测试、HTTP、重启与故障验收 |

新版应用只需要 DB_URL、DB_USER、DB_PASSWORD。LAB_* 仅供迁移初始化，不再是运行时登录密码来源。重复迁移不会重置已有密码或启用状态。

## 4. 已验证的事实

Mac 使用 Java 21、MySQL 8.4.13，Spring Boot 4.1.2、MyBatis Starter 4.1.0。

用户模块完整回归：96 项，失败 0、错误 0、跳过 0。独立验收库为 `ticket_verify_20261101_135446`。真实 HTTP 状态流转、权限错误、重启持久化、缺少 DB_URL 和数据库不可用均已验证。迁移工具在该库连续执行两次成功。

当时生成的 Jar SHA-256：`0717a1c5e9e7573d213758f3016e41e465c9af80423014ea4fee78d67dc4228d`。后续仅补 SQL 备注、文档以及 application.properties 末尾空行整理，没有再运行全套测试；此指纹标识当时产物，不是未来重新打包的保证。

Mac 业务库 ticket_lab 已备份并迁移，四账号保留原密码并登录成功。原有 3 条工单、1 条事件保留。当前应用地址为 `http://127.0.0.1:8080`，它是后端接口，不是前端页面。

报告在 target/surefire-reports 和 target/macos-acceptance；另已归档到 `~/.local/share/ticket-api/acceptance/20261101-users`。迁移前备份为 `~/.local/share/ticket-api/backups/ticket_lab_20261101_135604.sql`。归档与备份仅在 Mac 本机，未纳入 Git，不应公开。

本机服务管理、凭据文件位置见 [[03-10-macOS环境与验收记录]]。该文记录的是用户模块迁移前快照；其中内存账号、LAB_* 必需等旧行为以 [[03-用户模块实施与双机迁移记录]] 为准。

## 5. 新增课件与操作记录

- [[03-11-数据库用户模块：登录、密码哈希与数据关联]]：公共补充课件，讲原理、代码、练习、排错和完成标准。
- [[03-用户模块实施与双机迁移记录]]：用户迁移、Mac 与 Windows 操作步骤和验收证据。
- [[03-10-macOS环境与验收记录]]：前一阶段本机部署验收快照。
- 本文件：换电脑或新对话时的交接入口。

原主课件与 03-08 部署课件已撤回本轮机器进度插入，保持原样。项目进度、交接记录属于动态记录，保留阶段补充。

## 6. 下一台电脑的执行顺序

1. 检查 Windows Git 分支、HEAD 和未提交文件，保留前端草稿及其他本地修改。不要直接 reset、clean 或覆盖工作区。
2. Mac 本地实现提交已完成；检查根目录上下文等后续改动，确认提交身份。远程同步另按用户明确指令执行，不能沿用过去的推送授权。
3. Windows 取得同一版本后先阅读独立迁移记录。不要照旧上下文假定数据库仍只有两张表。
4. 在获准执行后，创建独立测试库，初始化业务表和用户模块，再运行完整测试。测试口令只用于测试库。
5. 业务迁移前停止写入并备份；用该电脑自己的初始化密码迁移用户，核对用户编号，执行备注脚本。未知旧编号应人工确认，不能强行关闭外键。
6. 切回受限数据库账号启动服务，验证四账号、权限、工单状态流转和事件，再验证重启后的数据。
7. 将 Windows 的日期、代码版本、测试结果和数据库核对结果单独记录，同步 docs 与 Obsidian。

MySQL DDL 隐式提交，迁移失败不代表所有更改已撤销。先检查现场再修复；恢复备份前应停止写入，并避免覆盖迁移后新增数据。

## 7. 提交范围与排除项

建议纳入：用户模块业务代码、对应测试和配置调整、迁移与验收脚本、用户 SQL、独立课件与阶段记录。不要使用未检查的全量 git add 来替代逐项审查。

不纳入：root.cnf、ticket_app.cnf、accounts.json、数据库备份、原始测试日志、Session/Cookie、target 产物，以及 Windows 本地前端草稿。脚本只引用外部凭据文件，不携带实际口令。

当前待办：实现已本地提交，根目录上下文尚未提交，均未进行本轮远程推送；Windows 尚未独立验收。注册、找回密码、前端、即时会话撤销、全新机器安装复现和生产部署均不在已完成范围内。
