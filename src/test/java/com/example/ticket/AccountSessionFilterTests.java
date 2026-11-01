package com.example.ticket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.FilterChain;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSessionFilterTests {
    @AfterEach void clear() { TestIdentity.clear(); }

    @Test void databaseOutageFailsClosedWithoutDestroyingSession() throws Exception {
        UserMapper users = mock(UserMapper.class);
        when(users.findById(1001)).thenThrow(new DataAccessResourceFailureException("private JDBC details"));
        TestIdentity.signInAsAlice();
        var request = new MockHttpServletRequest();
        var session = request.getSession();
        var response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new AccountSessionFilter(users).doFilter(request, response, chain);
        assertEquals(503, response.getStatus());
        assertFalse(response.getContentAsString().contains("JDBC"));
        assertNotNull(response.getHeader("X-Request-Id"));
        assertSame(session, request.getSession(false));
        verifyNoInteractions(chain);
    }

    @Test void anonymousRequestsDoNotRequireDatabase() throws Exception {
        UserMapper users = mock(UserMapper.class);
        FilterChain chain = mock(FilterChain.class);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        new AccountSessionFilter(users).doFilter(request, response, chain);
        verifyNoInteractions(users);
        verify(chain).doFilter(request, response);
    }
}
