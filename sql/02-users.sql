-- Run against the selected database before the user migration tool.
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY COMMENT '用户编号，关联工单与操作记录',
    username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '登录用户名，唯一且区分大小写',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希，使用带{bcrypt}前缀的BCrypt格式，不保存明文',
    role VARCHAR(10) NOT NULL COMMENT '角色：USER普通用户、AGENT处理人',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用：1启用、0禁用；禁用阻止新登录，不撤销已有会话',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '用户创建时间',
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT chk_users_role CHECK (role IN ('USER', 'AGENT')),
    CONSTRAINT chk_users_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户，提供登录身份与业务数据关联';
