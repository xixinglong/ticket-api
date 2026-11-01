-- 对已有 users 表补充备注；先选择目标数据库。可重复执行，不修改账号数据。
ALTER TABLE users
    COMMENT = '系统用户，提供登录身份与业务数据关联',
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '用户编号，关联工单与操作记录',
    MODIFY COLUMN username VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '登录用户名，唯一且区分大小写',
    MODIFY COLUMN password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希，使用带{bcrypt}前缀的BCrypt格式，不保存明文',
    MODIFY COLUMN role VARCHAR(10) NOT NULL COMMENT '角色：USER普通用户、AGENT处理人',
    MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用：1启用、0禁用；禁用阻止新登录，不撤销已有会话',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '用户创建时间';
