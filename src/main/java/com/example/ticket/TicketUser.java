package com.example.ticket;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** 登录身份包含稳定的业务用户编号；编号来自服务端账号配置，不来自请求。 */
public class TicketUser extends User {
    private final long id;

    public TicketUser(long id, String username, String encodedPassword, String role) {
        super(username, encodedPassword, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.id = id;
    }

    public long getId() {
        return id;
    }
}
