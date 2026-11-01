package com.example.ticket;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Validate saved identities before CSRF and authorization, including read requests. */
final class AccountSessionFilter extends OncePerRequestFilter {
    private final UserMapper users;

    AccountSessionFilter(UserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof TicketUser user) {
            final UserRow row;
            try {
                row = users.findById(user.getId());
            } catch (DataAccessException exception) {
                // Fail closed without treating a database outage as an account deletion.
                SecurityConfig.writeError(request, response, 503, "SERVICE_UNAVAILABLE", "身份校验暂不可用，请稍后重试");
                return;
            }
            if (row == null || !row.enabled() || row.authVersion() != user.getAuthVersion()
                    || !row.username().equals(user.getUsername()) || !CurrentUser.hasRole(user, row.role())) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityConfig.writeError(request, response, 401, "UNAUTHENTICATED", "账号状态已变化，请重新登录");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
