package com.example.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabaseUserTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserDetailsService users;
    @Autowired PasswordEncoder encoder;
    @Autowired WebApplicationContext context;

    private void login(String password, int expected) throws Exception {
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post("/api/login").with(csrf()).param("username", "alice").param("password", password))
                .andExpect(status().is(expected));
    }

    @Test void loadsStableIdentityAndHash() {
        TicketUser alice = (TicketUser) users.loadUserByUsername("alice");
        assertEquals(1001L, alice.getId());
        assertTrue(alice.getPassword().startsWith("{bcrypt}"));
        assertTrue(encoder.matches("test-alice-password", alice.getPassword()));
    }

    @Test void disabledAccountCannotLogin() throws Exception {
        jdbc.update("UPDATE users SET enabled=FALSE WHERE id=1001");
        login("test-alice-password", 401);
    }

    @Test void newlyInsertedUserCanLoginWithoutCodeChanges() throws Exception {
        jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES (3001,'new-user',?,'USER')",
                encoder.encode("new-user-password"));
        MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
                .perform(post("/api/login").with(csrf()).param("username", "new-user")
                        .param("password", "new-user-password"))
                .andExpect(status().isNoContent());
        assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                () -> users.loadUserByUsername("unknown-user"));
    }

    @Test void passwordChangeAffectsNewLogin() throws Exception {
        jdbc.update("UPDATE users SET password_hash=? WHERE id=1001", encoder.encode("replacement-password"));
        login("test-alice-password", 401);
        login("replacement-password", 204);
    }

    @Test void foreignKeysProtectAllUserReferences() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO tickets(title,creator_id,status) VALUES ('invalid',999999,'OPEN')"));
        jdbc.update("INSERT INTO tickets(id,title,creator_id,status) VALUES (9000001,'FK fixture',1001,'OPEN')");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE tickets SET assignee_id=999999 WHERE id=9000001"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ticket_events(ticket_id,actor_id,action) VALUES (9000001,999999,'CANCEL')"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM users WHERE id=1001"));
    }

    @Test void usernameAndRoleAreConstrained() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO users(id,username,password_hash,role) VALUES (3001,'alice','unused','USER')"));
        var failure = assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE users SET role='INVALID' WHERE id=1001"));
        assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause());
        assertEquals(3819, ((java.sql.SQLException) failure.getMostSpecificCause()).getErrorCode());
    }
}
