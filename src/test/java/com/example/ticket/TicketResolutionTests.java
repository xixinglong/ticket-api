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

/** 真实数据库验证完成工单、权限与回滚；不使用包住整条测试的事务。 */
@SpringBootTest
@ActiveProfiles("test")
class TicketResolutionTests {
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
    void assigneeResolvesOnceAndCreatorCanStillRead() throws Exception {
        long id = createClaimedTicket();
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L)))
                .with(csrf()).queryParam("actorId", "2002").queryParam("status", "OPEN"))
                .andExpect(status().isNoContent());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertResolved(id);
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.alice())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2001L))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tickets/{id}", id).with(user(TestIdentity.agent(2002L))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tickets/{id}/claim", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tickets/{id}/cancel", id).with(user(TestIdentity.alice())).with(csrf()))
                .andExpect(status().isConflict());
        assertResolved(id);
    }

    @Test
    void authenticationRoleCsrfAndOwnershipAreAllRequired() throws Exception {
        long id = createClaimedTicket();
        mvc.perform(post("/api/tickets/{id}/resolve", id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.alice())).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L)))
                .with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        // 客户端伪造 actorId 不能代替已登录的 agent2。
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2002L)))
                .with(csrf()).queryParam("actorId", "2001"))
                .andExpect(status().isNotFound());
        assertStillInProgress(id);
    }

    @Test
    void directServiceCallAlsoChecksRoleAndAssignee() {
        long id = createClaimedTicket();
        TestIdentity.signInAsAlice();
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.resolve(Long.toString(id)));
        TestIdentity.signIn(TestIdentity.agent(2002L));
        assertThrows(TicketNotFoundException.class, () -> service.resolve(Long.toString(id)));
        assertStillInProgress(id);
    }

    @Test
    void visibleOpenTicketCannotSkipClaimStep() throws Exception {
        long id = createTicket();
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isConflict());
        assertEquals("OPEN", mapper.findById(id).getStatus());
        assertNull(mapper.findById(id).getAssigneeId());
        assertEquals(0L, eventCount(id));
    }

    @Test
    void cancelledTicketIsInvisibleAndUnchanged() throws Exception {
        long id = createTicket();
        TestIdentity.signInAsAlice();
        service.cancel(Long.toString(id));
        TestIdentity.clear();
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
        assertEquals("CANCELLED", mapper.findById(id).getStatus());
        assertNull(mapper.findById(id).getAssigneeId());
        assertEquals(List.of("CANCEL"), actions(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-found", "1abc", "0", "-1", "9223372036854775808"})
    void invalidIdReturnsNotFound(String id) throws Exception {
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingTicketReturnsNotFound() throws Exception {
        long id = createTicket();
        jdbc.update("DELETE FROM tickets WHERE id = ?", id);
        mvc.perform(post("/api/tickets/{id}/resolve", id).with(user(TestIdentity.agent(2001L))).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void eventFailureRollsBackOnlyResolutionAndPreservesClaim() {
        long id = createClaimedTicket();
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("RESOLVED", mapper.findById(id).getStatus());
            throw new DataIntegrityViolationException("模拟完成记录写入失败");
        }).when(eventMapper).insert(id, 2001L, "RESOLVE");
        TestIdentity.signIn(TestIdentity.agent(2001L));
        assertThrows(DataIntegrityViolationException.class, () -> service.resolve(Long.toString(id)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        // 之前接单事务已提交，不能被这次失败一起撤销。
        assertStillInProgress(id);
    }

    @Test
    void zeroInsertedEventsAlsoRollsBackResolution() {
        long id = createClaimedTicket();
        doReturn(0).when(eventMapper).insert(id, 2001L, "RESOLVE");
        TestIdentity.signIn(TestIdentity.agent(2001L));
        assertThrows(IllegalStateException.class, () -> service.resolve(Long.toString(id)));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertStillInProgress(id);
    }

    @Test
    void concurrentDuplicateRequestsWriteOnlyOneResolveEvent() throws Exception {
        long id = createClaimedTicket();
        var bothReadInProgress = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> raceResolve(id, bothReadInProgress));
            var second = pool.submit(() -> raceResolve(id, bothReadInProgress));
            boolean firstSucceeded = first.get(25, TimeUnit.SECONDS);
            boolean secondSucceeded = second.get(25, TimeUnit.SECONDS);
            assertTrue(firstSucceeded != secondSucceeded, "并发完成必须恰好一个成功");
            assertResolved(id);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS), "并发线程未结束");
        }
    }

    private boolean raceResolve(long id, CyclicBarrier bothReadInProgress) {
        TestIdentity.signIn(TestIdentity.agent(2001L));
        try {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(15);
            return Boolean.TRUE.equals(transaction.execute(ignored -> {
                assertEquals("IN_PROGRESS", service.find(Long.toString(id)).orElseThrow().status());
                try {
                    bothReadInProgress.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("另一完成请求未就绪", exception);
                }
                service.resolve(Long.toString(id));
                return true;
            }));
        } catch (TicketConflictException expectedLoser) {
            return false;
        } finally {
            TestIdentity.clear();
        }
    }

    private long createTicket() {
        TestIdentity.signInAsAlice();
        try {
            long id = Long.parseLong(service.create("Resolution test", "Test fixture").id());
            createdIds.add(id);
            return id;
        } finally {
            TestIdentity.clear();
        }
    }

    private long createClaimedTicket() {
        long id = createTicket();
        TestIdentity.signIn(TestIdentity.agent(2001L));
        try {
            service.claim(Long.toString(id));
            return id;
        } finally {
            TestIdentity.clear();
        }
    }

    private long eventCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id = ?", Long.class, id);
    }

    private List<String> actions(long id) {
        return jdbc.queryForList("SELECT action FROM ticket_events WHERE ticket_id = ? ORDER BY id", String.class, id);
    }

    private void assertStillInProgress(long id) {
        assertEquals("IN_PROGRESS", mapper.findById(id).getStatus());
        assertEquals(Long.valueOf(2001L), mapper.findById(id).getAssigneeId());
        assertEquals(List.of("CLAIM"), actions(id));
    }

    private void assertResolved(long id) {
        assertEquals("RESOLVED", mapper.findById(id).getStatus());
        assertEquals(Long.valueOf(2001L), mapper.findById(id).getAssigneeId());
        assertEquals(Long.valueOf(1001L), mapper.findById(id).getCreatorId());
        assertEquals(List.of("CLAIM", "RESOLVE"), actions(id));
        assertEquals(List.of(2001L, 2001L), jdbc.queryForList(
                "SELECT actor_id FROM ticket_events WHERE ticket_id = ? ORDER BY id", Long.class, id));
    }
}
