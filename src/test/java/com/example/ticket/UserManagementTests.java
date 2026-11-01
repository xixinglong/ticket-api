package com.example.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserManagementTests {
    @Autowired WebApplicationContext context;
    @Autowired UserMapper users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    MockMvc mvc;
    MockHttpSession admin;

    @BeforeEach void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = login("admin", "test-admin-password");
    }

    MockHttpSession login(String name, String password) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/login").with(csrf())
                        .param("username", name).param("password", password))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
    }

    @Test void adminIdentityDoesNotGrantTicketAccess() throws Exception {
        mvc.perform(get("/api/me").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
        mvc.perform(get("/api/tickets").session(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets").session(admin).with(csrf()).contentType("application/json")
                .content("{\"title\":\"denied\"}")).andExpect(status().isForbidden());
    }

    @Test void allManagementRoutesRejectNonAdmins() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        for (String name : new String[]{"alice", "agent1"}) {
            var session = login(name, "test-" + name + "-password");
            mvc.perform(get("/api/admin/users").session(session)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/users").session(session).with(csrf()).contentType("application/json")
                    .content("{}")).andExpect(status().isForbidden());
            mvc.perform(patch("/api/admin/users/1002/enabled").session(session).with(csrf())
                    .contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/users/1002/reset-password").session(session).with(csrf())
                    .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        }
    }

    @Test void paginationNeverExposesCredentials() throws Exception {
        mvc.perform(get("/api/admin/users?size=1").session(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].authVersion").doesNotExist());
        mvc.perform(get("/api/admin/users?page=0").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users?size=101").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users?page=2147483647&size=100").session(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void creationGeneratesIdHashesPasswordAndSupportsLogin() throws Exception {
        mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType("application/json")
                .content("{\"username\":\"created-user\",\"password\":\"created-user-password\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var row = users.findByUsername("created-user");
        assertTrue(row.id() > 2002);
        assertTrue(encoder.matches("created-user-password", row.passwordHash()));
        login("created-user", "created-user-password");
        mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType("application/json")
                .content("{\"username\":\"created-user\",\"password\":\"created-user-password\",\"role\":\"USER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));
    }

    @Test void invalidInputAndPrivilegeEscalationAreRejected() throws Exception {
        for (String body : new String[]{"{}",
                "{\"username\":\"valid-name\",\"password\":\"long-test-password\",\"role\":\"ADMIN\"}",
                "{\"username\":\"bad name\",\"password\":\"long-test-password\",\"role\":\"USER\"}",
                "{\"username\":\"valid-name\",\"password\":\"short\",\"role\":\"USER\"}",
                "{\"username\":\"valid-name\",\"password\":\"" + "密".repeat(25) + "\",\"role\":\"USER\"}"}) {
            mvc.perform(post("/api/admin/users").session(admin).with(csrf()).contentType("application/json")
                    .content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(patch("/api/admin/users/1001/enabled").session(admin).with(csrf())
                .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }

    @Test void mutationsRequireCsrfAndProtectAdmin() throws Exception {
        mvc.perform(post("/api/admin/users").session(admin).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        long id = users.findByUsername("admin").id();
        mvc.perform(patch("/api/admin/users/" + id + "/enabled").session(admin).with(csrf())
                .contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/users/" + id + "/reset-password").session(admin).with(csrf())
                .contentType("application/json").content("{\"password\":\"replacement-password\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/users/9223372036854775807/enabled").session(admin).with(csrf())
                .contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/users/9223372036854775807/reset-password").session(admin).with(csrf())
                .contentType("application/json").content("{\"password\":\"replacement-password\"}"))
                .andExpect(status().isNotFound());
    }

    void enabled(boolean value) throws Exception {
        mvc.perform(patch("/api/admin/users/1001/enabled").session(admin).with(csrf())
                .contentType("application/json").content("{\"enabled\":" + value + "}"))
                .andExpect(status().isNoContent());
    }

    @Test void disablingRevokesExistingSessionsAndNewLogin() throws Exception {
        var alice = login("alice", "test-alice-password");
        enabled(false);
        mvc.perform(get("/api/me").session(alice)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").with(csrf()).param("username", "alice")
                .param("password", "test-alice-password")).andExpect(status().isUnauthorized());
        assertTrue(alice.isInvalid());
    }

    @Test void reenablingDoesNotReviveAnUnusedOldSession() throws Exception {
        var alice = login("alice", "test-alice-password");
        enabled(false);
        enabled(true);
        mvc.perform(get("/api/me").session(alice)).andExpect(status().isUnauthorized());
        login("alice", "test-alice-password");
    }

    @Test void resettingPasswordRevokesEveryOldSession() throws Exception {
        var first = login("alice", "test-alice-password");
        var second = login("alice", "test-alice-password");
        mvc.perform(post("/api/admin/users/1001/reset-password").session(admin).with(csrf())
                .contentType("application/json").content("{\"password\":\"replacement-password\"}"))
                .andExpect(status().isNoContent());
        for (var session : new MockHttpSession[]{first, second}) {
            mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/login").with(csrf()).param("username", "alice")
                .param("password", "test-alice-password")).andExpect(status().isUnauthorized());
        login("alice", "replacement-password");
    }

    @Test void changedRoleRevokesSession() throws Exception {
        var alice = login("alice", "test-alice-password");
        jdbc.update("UPDATE users SET role='AGENT' WHERE id=1001");
        mvc.perform(get("/api/me").session(alice)).andExpect(status().isUnauthorized());
    }
}
