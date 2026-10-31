package com.example.ticket;

import java.io.IOException;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 先用四个内存账号练习 USER / AGENT 权限；真实密码只从启动环境读取，不写进源码。 */
    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder,
            @Value("${app.security.alice-password}") String alicePassword,
            @Value("${app.security.bob-password}") String bobPassword,
            @Value("${app.security.agent1-password}") String agent1Password,
            @Value("${app.security.agent2-password}") String agent2Password) {
        if (alicePassword.isBlank() || bobPassword.isBlank()
                || agent1Password.isBlank() || agent2Password.isBlank()) {
            throw new IllegalArgumentException("练习账号密码不能为空");
        }
        String aliceHash = encoder.encode(alicePassword);
        String bobHash = encoder.encode(bobPassword);
        String agent1Hash = encoder.encode(agent1Password);
        String agent2Hash = encoder.encode(agent2Password);
        // 每次返回新身份对象，避免认证后擦除凭据影响后续登录。
        return username -> switch (username) {
            case "alice" -> new TicketUser(1001L, "alice", aliceHash, "USER");
            case "bob" -> new TicketUser(1002L, "bob", bobHash, "USER");
            case "agent1" -> new TicketUser(2001L, "agent1", agent1Hash, "AGENT");
            case "agent2" -> new TicketUser(2002L, "agent2", agent2Hash, "AGENT");
            default -> throw new UsernameNotFoundException("用户名或密码错误");
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 错误转发放行，避免原来的 404/409 被安全过滤器改写为认证错误。
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/csrf", "/api/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/claim", "/api/tickets/*/resolve").hasRole("AGENT")
                        .requestMatchers(HttpMethod.GET, "/api/tickets", "/api/tickets/*", "/api/me").hasAnyRole("USER", "AGENT")
                        .requestMatchers(HttpMethod.POST, "/api/tickets", "/api/tickets/*/cancel").hasRole("USER")
                        .anyRequest().denyAll())
                // 保留默认 CSRF 防护；登录与退出也需要有效 token。
                .formLogin(login -> login
                        .loginProcessingUrl("/api/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, exception) ->
                                writeError(request, response, 401, "UNAUTHENTICATED", "用户名或密码错误")))
                .logout(logout -> logout
                        .logoutUrl("/api/logout")
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(request, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> {
                            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                            boolean anonymous = authentication == null || !authentication.isAuthenticated()
                                    || authentication instanceof AnonymousAuthenticationToken;
                            // CSRF 过滤器也使用这个处理器，缺少身份时统一为 401。
                            writeError(request, response, anonymous ? 401 : 403,
                                    anonymous ? "UNAUTHENTICATED" : "FORBIDDEN",
                                    anonymous ? "请先登录" : "角色无权限或 CSRF 校验失败");
                        }));
        return http.build();
    }

    // 内容都是服务端固定文本，不向客户端回传底层异常或数据库信息。
    private static void writeError(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        String requestId = RequestIdFilter.getOrCreate(request);
        response.setHeader(RequestIdFilter.HEADER, requestId);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}");
    }
}
