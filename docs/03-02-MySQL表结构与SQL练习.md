---
type: note
status: growing
created: 2026-10-31
updated: 2026-10-31
tags:
  - career/java
aliases: []
---

# 03-02-MySQL表结构与SQL练习

本节在 DBeaver 操作。目标是认识数据保存在哪儿、知道 SQL 修改了哪一行，并理解“更新 0 行”为什么不等于成功。已有练习库不用重新安装 MySQL，也不要删表重建。

## 1. 先区分三个东西

MySQL 是保存和查询数据的服务；DBeaver 是连接服务的客户端；Java 也是另一个数据库客户端。DBeaver 连接成功，只能证明它自己的地址、账号和密码有效，不会把连接配置自动写进 Java。

在 DBeaver 打开 SQL 编辑器，先执行下面的只读检查，确认当前数据库：

~~~sql
SELECT DATABASE() AS current_database, VERSION() AS mysql_version;
SHOW TABLES FROM ticket_lab;
SHOW CREATE TABLE ticket_lab.tickets;
SHOW FULL COLUMNS FROM ticket_lab.tickets;
SHOW FULL COLUMNS FROM ticket_lab.ticket_events;
~~~

预期能看到 tickets 和 ticket_events，两张表都有字段说明。表存在但导航树没显示时刷新当前库；不要因为列表没有刷新就反复 CREATE TABLE。

## 2. 两张表各解决什么问题

| tickets 字段 | 含义 | 为什么这样设计 |
| --- | --- | --- |
| id | 工单编号，BIGINT 自增主键 | 唯一定位一张工单 |
| title | 最长 100 的标题，非空 | 简短表达问题 |
| description | TEXT，可空 | 保存问题详情；不是状态说明 |
| creator_id | 创建人编号，非空 | 数据归属和用户隔离 |
| assignee_id | 处理人编号，可空 | OPEN 时尚未分配 |
| status | OPEN / IN_PROGRESS / RESOLVED / CANCELLED | 表达业务进度 |
| created_at | 数据库默认创建时间 | 留下创建时间 |

| ticket_events 字段 | 含义 |
| --- | --- |
| id | 每条事件的独立编号 |
| ticket_id | 关联工单，外键指向 tickets.id |
| actor_id | 实际操作者 |
| action | CANCEL、CLAIM、RESOLVE |
| created_at | 事件发生时间 |

创建接口当前不写 CREATE 事件。一张新 OPEN 工单没有事件是正常的；接单后应有 CLAIM，完成后应再有 RESOLVE。不能把“缺少 CREATE”判断为事务丢数据。

字段 COMMENT 是帮助人读表的备注，不会代替权限和状态校验。当前没有用户表，creator_id、actor_id 只与服务中的练习账号编号约定对应，没有用户外键。

## 3. 新环境才执行建表

完整、带备注的建表脚本在项目根目录 sql/01-schema.sql，先 tickets，再 ticket_events。第二张表引用第一张表，所以顺序不能交换。脚本只用于空练习库，不是自动升级脚本，也没有删除旧表语句。

如果新机器没有库，由数据库管理账号在 DBeaver 创建：

~~~sql
CREATE DATABASE ticket_lab CHARACTER SET utf8mb4;
~~~

再切换到这个库，执行 sql/01-schema.sql 中两条完整语句。应用账号 ticket_app 的创建与授权使用本机管理流程设置，不把实际口令写进课程笔记。现有用户无需重复建库和授权。

已有表缺少 description 时，先查 SHOW FULL COLUMNS，确认确实不存在，再在自己的练习库执行这一次性变更：

~~~sql
ALTER TABLE ticket_lab.tickets
ADD COLUMN description TEXT NULL COMMENT '问题描述，可为空' AFTER title;
~~~

其他字段只补备注时，需要先读取 SHOW CREATE TABLE，保留原字段类型、NULL、默认值、自增属性后再修改。不要为了 COMMENT 丢掉原属性。

## 4. 为什么之前在 INDEX 附近报 1064

INDEX idx_creator_id (creator_id, id) 是 CREATE TABLE 括号内的一部分，不能单独作为完整语句执行。还要检查上一列后面的逗号。DBeaver 选中了片段时，可能只发送片段；执行前清掉局部选择，选中完整语句或按完整脚本执行。

~~~sql
-- 已有表需要单独建索引时，语法长这样；先 SHOW INDEX 确认没有同名索引。
CREATE INDEX idx_creator_id ON tickets (creator_id, id);
~~~

这只是语法解释，不要求在已有正确索引的库上再执行一次。

## 5. 独立练习条件更新

下面会在当前练习库开启事务，最后回滚。请使用一个独立 SQL 编辑器连接，确认没有待提交的其他工作后按顺序执行。不要把它用于生产库，也不要和接口练习的工单混用。

~~~sql
START TRANSACTION;
INSERT INTO ticket_lab.tickets (title, creator_id, status)
VALUES ('SQL条件更新练习', 1001, 'OPEN');
SET @practice_id = LAST_INSERT_ID();

UPDATE ticket_lab.tickets SET status = 'CANCELLED'
WHERE id = @practice_id AND creator_id = 1001 AND status = 'OPEN';
SELECT ROW_COUNT() AS first_changed;

UPDATE ticket_lab.tickets SET status = 'CANCELLED'
WHERE id = @practice_id AND creator_id = 1001 AND status = 'OPEN';
SELECT ROW_COUNT() AS second_changed;

ROLLBACK;
~~~

预期第一次 1，第二次 0。每次 ROW_COUNT() 紧跟 UPDATE，中间不要插别的语句。最后回滚使练习数据不保留，但编号可能被消耗。这个 SQL 练习没有事件写入和登录判断，不能用来替代接口取消。

## 6. 接口练习结束后怎么核对

将 123 替换成脚本实际输出的编号，执行：

~~~sql
SELECT id, title, description, creator_id, assignee_id, status, created_at
FROM ticket_lab.tickets WHERE id = 123;
SELECT id, ticket_id, actor_id, action, created_at
FROM ticket_lab.ticket_events WHERE ticket_id = 123 ORDER BY id;
~~~

如果接口与 SQL 不一致，先检查连接的主机、库名和工单编号，再怀疑代码。不要用 DELETE 清空全表来“重新开始”。

练习题：为什么 ticket_events 要有自己的 id？参考答案：同一工单有多条动作记录，需要分别标识和排序；ticket_id 不是该表的唯一编号。

关联：[[03-03-MyBatis与环境配置]]、[[03-05-取消工单与事务回滚详解]]。来源：项目 Mapper、sql/01-schema.sql 和课程 03 的表结构。
