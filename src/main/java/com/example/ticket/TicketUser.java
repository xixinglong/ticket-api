package com.example.ticket;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 登录身份包含稳定的数据库用户编号，不接受请求中的身份编号。 */
public class TicketUser extends User {
    private final long id;
    private final long authVersion;

    public TicketUser(long id, String username, String encodedPassword, String role) {
        this(id, username, encodedPassword, role, true);
    }

    public TicketUser(long id, String username, String encodedPassword, String role, boolean enabled) {
        this(id, username, encodedPassword, role, enabled, 0);
    }

    public TicketUser(long id, String username, String encodedPassword, String role, boolean enabled,
            long authVersion) {
        super(username, encodedPassword, enabled, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.id = id;
        this.authVersion = authVersion;
    }

    public long getId() {
        return id;
    }

    public long getAuthVersion() {
        return authVersion;
    }
}
