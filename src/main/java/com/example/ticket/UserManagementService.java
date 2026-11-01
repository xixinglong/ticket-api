package com.example.ticket;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagementService {
    private final UserMapper users;
    private final CurrentUser currentUser;
    private final PasswordEncoder encoder;

    public UserManagementService(UserMapper users, CurrentUser currentUser, PasswordEncoder encoder) {
        this.users = users;
        this.currentUser = currentUser;
        this.encoder = encoder;
    }

    public record Page(List<ManagedUserResponse> items, int page, int size, long total) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(int page, int size) {
        currentUser.requireAdmin();
        if (page < 1 || size < 1 || size > 100) throw new InvalidPaginationException();
        long total = users.count();
        return new Page(users.list(size, ((long) page - 1) * size), page, size, total);
    }

    @Transactional
    public ManagedUserResponse create(String username, String password, String role) {
        currentUser.requireAdmin();
        if (username == null || !username.matches("[A-Za-z0-9][A-Za-z0-9_.-]{2,63}")
                || !("USER".equals(role) || "AGENT".equals(role))) {
            throw new UserManagementException(400, "INVALID_REQUEST", "用户名须为3到64位字母、数字或_.-，首位为字母或数字；角色须为USER或AGENT");
        }
        validatePassword(password);
        try {
            users.insert(username, encoder.encode(password), role);
        } catch (DuplicateKeyException exception) {
            throw new UserManagementException(409, "USERNAME_EXISTS", "用户名已存在");
        }
        return ManagedUserResponse.from(users.findByUsername(username));
    }

    @Transactional
    public void setEnabled(long id, Boolean enabled) {
        currentUser.requireAdmin();
        if (enabled == null) throw new UserManagementException(400, "INVALID_REQUEST", "enabled不能为空");
        requireManageable(id);
        if (users.setEnabled(id, enabled) != 1) throw missing();
    }

    @Transactional
    public void resetPassword(long id, String password) {
        currentUser.requireAdmin();
        validatePassword(password);
        requireManageable(id);
        if (users.resetPassword(id, encoder.encode(password)) != 1) throw missing();
    }

    private void requireManageable(long id) {
        UserRow row = users.findById(id);
        if (row == null) throw missing();
        // Admin maintenance stays out of the public API, preventing self-lockout and escalation.
        if (!List.of("USER", "AGENT").contains(row.role())) throw new AccessDeniedException("不能通过此接口修改管理员");
    }

    private static UserManagementException missing() {
        return new UserManagementException(404, "USER_NOT_FOUND", "用户不存在");
    }

    static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new UserManagementException(400, "INVALID_REQUEST", "密码至少12个字符，UTF-8编码不超过72字节，不能全为空白");
        }
    }
}
