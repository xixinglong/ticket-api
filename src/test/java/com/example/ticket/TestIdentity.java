package com.example.ticket;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** 测试辅助：直接调用 Service 时建立身份；真实登录流程由安全集成测试验证。 */
final class TestIdentity {
    private TestIdentity() {}

    static TicketUser alice() {
        return new TicketUser(1001L, "alice", "unused", "USER");
    }

    static void signInAsAlice() {
        signIn(alice());
    }

    static TicketUser agent(long id) {
        return new TicketUser(id, "agent" + (id - 2000), "unused", "AGENT");
    }

    static void signIn(TicketUser user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    static void clear() {
        SecurityContextHolder.clearContext();
    }
}
