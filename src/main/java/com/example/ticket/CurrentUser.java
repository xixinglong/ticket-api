package com.example.ticket;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 从服务端登录上下文获取身份，不能信任 JSON 或 URL 传入的用户编号。 */
@Component
public class CurrentUser {
    public TicketUser requireUser() {
        return requireRole("USER");
    }

    public TicketUser requireAgent() {
        return requireRole("AGENT");
    }

    /** 查询身份和详情允许两种课程角色，具体工单的可见性仍由业务层判断。 */
    public TicketUser requireReader() {
        TicketUser user = requireAuthenticated();
        if (!hasRole(user, "USER") && !hasRole(user, "AGENT")) {
            throw new AccessDeniedException("当前角色不能执行此操作");
        }
        return user;
    }

    public TicketUser requireAdmin() {
        TicketUser user = requireAuthenticated();
        if (!hasRole(user, "ADMIN")) {
            throw new AccessDeniedException("当前角色不能执行此操作");
        }
        return user;
    }

    public TicketUser requireAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof TicketUser user)) {
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }
        return user;
    }

    private TicketUser requireRole(String role) {
        TicketUser user = requireReader();
        if (!hasRole(user, role)) {
            throw new AccessDeniedException("当前角色不能执行此操作");
        }
        return user;
    }

    static boolean hasRole(TicketUser user, String role) {
        return user.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }
}
