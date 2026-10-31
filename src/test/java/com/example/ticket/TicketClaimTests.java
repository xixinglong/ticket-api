package com.example.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真实 MySQL 验证接单、权限、回滚与并发；只清理本类创建的数据。 */
@SpringBootTest
@ActiveProfiles("test")
class TicketClaimTests {
    @Autowired private TicketService service;
    @Autowired private TicketMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private TicketEventMapper eventMapper;

    private final List<Long> createdIds = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
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
    void claimCommitsActorAndEventAndEnforcesVisibility() throws Exception {
        long id = createTicket();
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2002L))))
                .andExpect(status().isOk());
        // 即使请求伪造处理人编号，数据库仍使用已认证的 agent1 身份。
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L)))
                .with(csrf()).queryParam("assigneeId", "2002").queryParam("actorId", "2002"))
                .andExpect(status().isNoContent());
        assertClaimedBy(id, 2001L);
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2001L))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.alice())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2002L))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2002L))).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tickets/{id}/cancel", id).with(user(TestIdentity.alice())).with(csrf()))
                .andExpect(status().isConflict());
        assertClaimedBy(id, 2001L); // 失败请求不能改分配人或多写记录。
    }

    @Test
    void claimRequiresAgentLoginAndCsrf() throws Exception {
        long id = createTicket();
        mvc.perform(post("/api/tickets/{id}/claim", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.alice())).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L)))
                .with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertOpenWithoutEvents(id);
    }

    @Test
    void directServiceCallAlsoRejectsUserRole() {
        long id = createTicket();
        TestIdentity.signInAsAlice();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.claim(Long.toString(id)));
        assertOpenWithoutEvents(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-found", "1abc", "0", "-1", "9223372036854775808"})
    void invalidIdReturnsNotFound(String id) throws Exception {
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingTicketReturnsNotFound() throws Exception {
        long id = createTicket();
        jdbc.update("DELETE FROM tickets WHERE id = ?", id);
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelledTicketIsInvisibleToAgent() throws Exception {
        long id = createTicket();
        TestIdentity.signInAsAlice();
        service.cancel(Long.toString(id));
        TestIdentity.clear();
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2001L))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
        assertEquals("CANCELLED", mapper.findById(id).getStatus());
        assertNull(mapper.findById(id).getAssigneeId());
        assertEquals(0L, claimEventCount(id));
    }

    @Test
    void eventFailureRollsBackStatusAndAssignee() {
        long id = createTicket();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("IN_PROGRESS", mapper.findById(id).getStatus());
            assertEquals(Long.valueOf(2001L), mapper.findById(id).getAssigneeId());
            throw new DataIntegrityViolationException("模拟接单记录写入失败");
        }).when(eventMapper).insert(id, 2001L, "CLAIM");
        TestIdentity.signIn(TestIdentity.agent(2001L));
        // 本测试没有外层事务；必须由业务方法自己的事务撤销更新。
        assertThrows(DataIntegrityViolationException.class, () -> service.claim(Long.toString(id)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertOpenWithoutEvents(id);
    }

    @Test
    void zeroInsertedEventsAlsoRollsBackClaim() {
        long id = createTicket();
        doReturn(0).when(eventMapper).insert(id, 2001L, "CLAIM");
        TestIdentity.signIn(TestIdentity.agent(2001L));
        assertThrows(IllegalStateException.class, () -> service.claim(Long.toString(id)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertOpenWithoutEvents(id);
    }

    @Test
    void twoIndependentTransactionsHaveExactlyOneWinner() throws Exception {
        long id = createTicket();
        var bothReadOpen = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> raceClaim(id, 2001L, bothReadOpen));
            var second = pool.submit(() -> raceClaim(id, 2002L, bothReadOpen));
            Long firstWinner = first.get(25, TimeUnit.SECONDS);
            Long secondWinner = second.get(25, TimeUnit.SECONDS);
            assertTrue((firstWinner == null) != (secondWinner == null), "必须恰好一个成功");
            long winner = firstWinner != null ? firstWinner : secondWinner;
            assertClaimedBy(id, winner);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "并发线程未结束");
        }
    }

    private Long raceClaim(long id, long actorId, CyclicBarrier bothReadOpen) {
        TestIdentity.signIn(TestIdentity.agent(actorId));
        try {
            // 每个线程独立开启事务、使用独立连接，两个事务都读到 OPEN 后才开始抢单。
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(15);
            return transaction.execute(ignored -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("OPEN", service.find(Long.toString(id)).orElseThrow().status());
                try {
                    bothReadOpen.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("另一接单线程未就绪", exception);
                }
                service.claim(Long.toString(id));
                return actorId;
            });
        } catch (TicketConflictException | TicketNotFoundException expectedLoser) {
            // RR 快照下更新失败为 409；若读取到对方已提交的分配结果，则为不可见 404。
            return null;
        } finally {
            TestIdentity.clear(); // SecurityContext 是线程局部变量，不能泄漏给其他任务。
        }
    }

    private long createTicket() {
        TestIdentity.signInAsAlice();
        try {
            long id = Long.parseLong(service.create("Agent claim test", "Test fixture").id());
            createdIds.add(id);
            return id;
        } finally {
            TestIdentity.clear();
        }
    }

    private long claimEventCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id = ? AND action = 'CLAIM'",
                Long.class, id);
    }

    private void assertOpenWithoutEvents(long id) {
        TicketRow row = mapper.findById(id);
        assertEquals("OPEN", row.getStatus());
        assertNull(row.getAssigneeId());
        assertEquals(Long.valueOf(0L), jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_events WHERE ticket_id = ?", Long.class, id));
    }

    private void assertClaimedBy(long id, long actorId) {
        TicketRow row = mapper.findById(id);
        assertEquals("IN_PROGRESS", row.getStatus());
        assertEquals(Long.valueOf(actorId), row.getAssigneeId());
        assertEquals(1L, claimEventCount(id));
        assertEquals(Long.valueOf(actorId), jdbc.queryForObject(
                "SELECT actor_id FROM ticket_events WHERE ticket_id = ? AND action = 'CLAIM'", Long.class, id));
    }
}
