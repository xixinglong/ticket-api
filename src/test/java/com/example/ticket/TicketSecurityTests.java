package com.example.ticket;

import java.util.ArrayList;
import java.util.List;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实安全过滤器、表单登录、Session、CSRF 与 MySQL 验证用户隔离。 */
@SpringBootTest
@ActiveProfiles("test")
class TicketSecurityTests {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    private MockMvc mvc;
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestIdentity.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void cleanUp() {
        TestIdentity.clear();
        for (long id : createdIds) {
            jdbc.update("DELETE FROM ticket_events WHERE ticket_id = ?", id);
            jdbc.update("DELETE FROM tickets WHERE id = ?", id);
        }
    }

    @Test
    void anonymousRequestsReturn401WithoutLoginRedirect() throws Exception {
        mvc.perform(get("/api/tickets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(header().exists("X-Request-Id"));
        mvc.perform(post("/api/tickets").contentType("application/json")
                .content("{\"title\":\"Unauthorized\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tickets/1/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordAndMissingLoginCsrfAreRejected() throws Exception {
        Client anonymous = fetchCsrf(null);
        mvc.perform(post("/api/login").session(anonymous.session())
                .header(anonymous.header(), anonymous.token())
                .param("username", "alice").param("password", "wrong-password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").session(anonymous.session())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/login").param("username", "alice")
                .param("password", "test-alice-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void twoUsersCanOnlyReadAndCancelTheirOwnTickets() throws Exception {
        Client alice = login("alice", "test-alice-password");
        Client bob = login("bob", "test-bob-password");
        mvc.perform(get("/api/me").session(alice.session())).andExpect(jsonPath("$.id").value(1001));
        mvc.perform(get("/api/me").session(bob.session())).andExpect(jsonPath("$.id").value(1002));
        long aliceId = createTicket(alice);
        long bobId = createTicket(bob);
        assertEquals(Long.valueOf(1001L), jdbc.queryForObject(
                "SELECT creator_id FROM tickets WHERE id = ?", Long.class, aliceId));
        assertEquals(Long.valueOf(1002L), jdbc.queryForObject(
                "SELECT creator_id FROM tickets WHERE id = ?", Long.class, bobId));
        mvc.perform(get("/api/tickets/{id}", aliceId).session(alice.session())).andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", bobId).session(bob.session())).andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", aliceId).session(bob.session())).andExpect(status().isNotFound());
        mvc.perform(get("/api/tickets/{id}", bobId).session(alice.session())).andExpect(status().isNotFound());
        // 伪造 actorId 参数不能切换身份。
        mvc.perform(post("/api/tickets/{id}/cancel", aliceId).session(bob.session())
                .header(bob.header(), bob.token()).queryParam("actorId", "1001"))
                .andExpect(status().isNotFound());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, aliceId));
        assertEquals(Long.valueOf(0L), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_events WHERE ticket_id = ?", Long.class, aliceId));
        mvc.perform(post("/api/tickets/{id}/cancel", bobId).session(bob.session())
                .header(bob.header(), bob.token())).andExpect(status().isNoContent());
        assertEquals(Long.valueOf(1002L), jdbc.queryForObject(
                "SELECT actor_id FROM ticket_events WHERE ticket_id = ?", Long.class, bobId));
    }

    @Test
    void authenticatedWritesRequireValidCsrfToken() throws Exception {
        Client alice = login("alice", "test-alice-password");
        long id = createTicket(alice);
        mvc.perform(post("/api/tickets/{id}/cancel", id).session(alice.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.requestId").isString());
        mvc.perform(post("/api/tickets/{id}/cancel", id).session(alice.session())
                .header(alice.header(), "invalid-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets").session(alice.session()).contentType("application/json")
                .content("{\"title\":\"Missing CSRF\"}"))
                .andExpect(status().isForbidden());
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, id));
    }

    @Test
    void logoutInvalidatesSessionAndUserCanLogInAgain() throws Exception {
        Client alice = login("alice", "test-alice-password");
        mvc.perform(post("/api/logout").session(alice.session())
                .header(alice.header(), alice.token())).andExpect(status().isNoContent());
        assertTrue(alice.session().isInvalid());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        Client again = login("alice", "test-alice-password");
        mvc.perform(get("/api/me").session(again.session())).andExpect(status().isOk());
    }

    @Test
    void agentRoleCannotUseUserEndpoints() throws Exception {
        TicketUser agent = new TicketUser(2001L, "agent1", "unused", "AGENT");
        mvc.perform(post("/api/tickets").with(user(agent)).with(csrf())
                .contentType("application/json").content("{\"title\":\"Forbidden creation\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/1/cancel").with(user(agent)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void bothConfiguredAgentsCanLogInAndReadTheirOwnIdentity() throws Exception {
        for (int number = 1; number <= 2; number++) {
            Client agent = login("agent" + number, "test-agent" + number + "-password");
            MvcResult result = mvc.perform(get("/api/me").session(agent.session()))
                    .andExpect(status().isOk()).andReturn();
            String json = result.getResponse().getContentAsString();
            assertEquals("agent" + number, JsonPath.<String>read(json, "$.username"));
            assertEquals(2000 + number, JsonPath.<Number>read(json, "$.id").intValue());
        }
    }

    @Test
    void identityExposesRolesWithoutPasswordOrCredentials() throws Exception {
        for (String name : new String[] { "alice", "agent1" }) {
            Client client = login(name, "test-" + name + "-password");
            mvc.perform(get("/api/me").session(client.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value(name.equals("alice") ? "USER" : "AGENT"))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.authorities").doesNotExist());
        }
    }

    private Client login(String username, String password) throws Exception {
        Client anonymous = fetchCsrf(null);
        MvcResult result = mvc.perform(post("/api/login").session(anonymous.session())
                .header(anonymous.header(), anonymous.token())
                .param("username", username).param("password", password))
                .andExpect(status().isNoContent()).andReturn();
        // 登录后旧 token 会被清除，必须在同一会话中重新获取。
        return fetchCsrf((MockHttpSession) result.getRequest().getSession(false));
    }

    private Client fetchCsrf(MockHttpSession session) throws Exception {
        var request = get("/api/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        String json = result.getResponse().getContentAsString();
        return new Client((MockHttpSession) result.getRequest().getSession(false),
                JsonPath.read(json, "$.headerName"), JsonPath.read(json, "$.token"));
    }

    private long createTicket(Client client) throws Exception {
        MvcResult result = mvc.perform(post("/api/tickets").session(client.session())
                .header(client.header(), client.token()).queryParam("creatorId", "9999")
                .contentType("application/json")
                .content("{\"title\":\"Security isolation test\",\"description\":\"Test fixture\"}"))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        long ticketId = Long.parseLong(id);
        createdIds.add(ticketId);
        return ticketId;
    }

    private record Client(MockHttpSession session, String header, String token) {}
}
