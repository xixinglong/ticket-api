-- 仅用于已经创建、尚无这两张表的独立练习库。
-- 在 DBeaver 确认当前数据库后，依次执行两条完整的 CREATE TABLE。
-- 不删除旧表；如果表已存在，先 SHOW CREATE TABLE 核对结构，不重复执行。
CREATE TABLE tickets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工单编号，由数据库生成',
    title VARCHAR(100) NOT NULL COMMENT '工单标题，最多100个字符',
    description TEXT NULL COMMENT '问题描述，可为空',
    creator_id BIGINT NOT NULL COMMENT '创建人编号，来自登录身份',
    assignee_id BIGINT NULL COMMENT '处理人编号，未接单时为空',
    status VARCHAR(20) NOT NULL COMMENT '状态：OPEN、IN_PROGRESS、RESOLVED、CANCELLED',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    INDEX idx_creator_id (creator_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单';

CREATE TABLE ticket_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '操作记录编号',
    ticket_id BIGINT NOT NULL COMMENT '关联工单编号',
    actor_id BIGINT NOT NULL COMMENT '操作者编号，来自登录身份',
    action VARCHAR(30) NOT NULL COMMENT '操作类型：CANCEL、CLAIM、RESOLVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '操作时间',
    INDEX idx_event_ticket (ticket_id, id),
    CONSTRAINT fk_event_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单操作记录';
