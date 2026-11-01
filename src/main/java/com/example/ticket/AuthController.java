package com.example.ticket;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录和退出由 Security 过滤器处理；这里只提供 CSRF token 与当前身份。 */
@RestController
public class AuthController {
    private final CurrentUser currentUser;

    public AuthController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping("/api/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        // 读取 token 会触发延迟创建，将它保存到当前 Session。
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/api/me")
    public UserResponse me() {
        TicketUser user = currentUser.requireAuthenticated();
        // 只返回用于识别权限的角色名；业务操作仍在服务端独立校验。
        List<String> roles = user.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring(5))
                .sorted().toList();
        return new UserResponse(user.getId(), user.getUsername(), roles);
    }

    public record CsrfResponse(String headerName, String token) {}
    public record UserResponse(long id, String username, List<String> roles) {}
}
