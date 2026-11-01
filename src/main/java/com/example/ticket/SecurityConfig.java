package com.example.ticket;

import java.io.IOException;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /** 身份编号和角色从数据库读取，禁用账号由认证提供者拒绝。 */
    @Bean
    UserDetailsService userDetailsService(UserMapper users) {
        return username -> {
            UserRow row = users.findByUsername(username);
            if (row == null) {
                throw new UsernameNotFoundException("用户名或密码错误");
            }
            return new TicketUser(row.id(), row.username(), row.passwordHash(), row.role(), row.enabled(), row.authVersion());
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserMapper users) throws Exception {
        http
                .addFilterBefore(new AccountSessionFilter(users), CsrfFilter.class)
                // 错误转发放行，避免原来的 404/409 被安全过滤器改写为认证错误。
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/csrf", "/api/login").permitAll()
                        .requestMatchers("/api/admin/users", "/api/admin/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/me").hasAnyRole("USER", "AGENT", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/tickets/*/claim", "/api/tickets/*/resolve").hasRole("AGENT")
                        .requestMatchers(HttpMethod.GET, "/api/tickets", "/api/tickets/*").hasAnyRole("USER", "AGENT")
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
    static void writeError(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        String requestId = RequestIdFilter.getOrCreate(request);
        response.setHeader(RequestIdFilter.HEADER, requestId);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"requestId\":\"" + requestId + "\"}");
    }
}
