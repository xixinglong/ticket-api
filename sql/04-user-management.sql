-- Upgrade existing users tables while application writers are stopped.
-- MySQL DDL commits implicitly. Back up first; this file is a one-time migration.
ALTER TABLE tickets DROP FOREIGN KEY fk_ticket_creator, DROP FOREIGN KEY fk_ticket_assignee;
ALTER TABLE ticket_events DROP FOREIGN KEY fk_event_actor;
ALTER TABLE users
    MODIFY id BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户编号，由数据库生成，保留旧编号及业务关联',
    MODIFY role VARCHAR(10) NOT NULL COMMENT '角色：USER普通用户、AGENT处理人、ADMIN账号管理员',
    MODIFY enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用；配合认证版本号在下次请求撤销旧会话',
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0 COMMENT '认证版本号；启停账号或重置密码时递增，使旧会话失效',
    DROP CHECK chk_users_role,
    ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'AGENT', 'ADMIN'));
ALTER TABLE tickets
    ADD CONSTRAINT fk_ticket_creator FOREIGN KEY (creator_id) REFERENCES users(id),
    ADD CONSTRAINT fk_ticket_assignee FOREIGN KEY (assignee_id) REFERENCES users(id);
ALTER TABLE ticket_events ADD CONSTRAINT fk_event_actor FOREIGN KEY (actor_id) REFERENCES users(id);
