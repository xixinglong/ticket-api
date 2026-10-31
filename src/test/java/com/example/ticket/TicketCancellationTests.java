package com.example.ticket;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 MySQL 和 Spring 事务代理，MockMvc 验证 HTTP 状态码，无需占用 8080。
 * 故意不在测试类上加 @Transactional：必须等业务事务结束后重新查库，
 * 避免测试自身的回滚掩盖业务事务缺失。仅清理本类创建的工单和记录。
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketCancellationTests {
    @Autowired private TicketService service;
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TicketEventMapper eventMapper;

    private final List<Long> createdIds = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(get("/").with(user(TestIdentity.alice())).with(csrf()))
                .build();
        TestIdentity.signInAsAlice();
    }

    @AfterEach
    void cleanUp() {
        TestIdentity.clear();
        for (long id : createdIds) {
            // 外键要求先删子表；只删除测试创建的编号，不清空用户数据。
            jdbc.update("DELETE FROM ticket_events WHERE ticket_id = ?", id);
            jdbc.update("DELETE FROM tickets WHERE id = ?", id);
        }
    }

    @Test
    void cancelCommitsStateAndEventAndRepeatReturnsConflict() throws Exception {
        long id = createTicket();
        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isNoContent());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("CANCELLED", ticketStatus(id));
        assertEquals(1L, eventCount(id));
        assertEquals("CANCEL", jdbc.queryForObject(
                "SELECT action FROM ticket_events WHERE ticket_id = ?", String.class, id));
        assertEquals(Long.valueOf(1001L), jdbc.queryForObject(
                "SELECT actor_id FROM ticket_events WHERE ticket_id = ?", Long.class, id));

        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isConflict());
        assertEquals(1L, eventCount(id)); // 重复取消不能多写成功记录。
    }

    @Test
    void anotherCreatorsTicketReturnsNotFoundAndIsUnchanged() throws Exception {
        long id = createTicket();
        jdbc.update("UPDATE tickets SET creator_id = ? WHERE id = ?", 2002L, id);
        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isNotFound());
        assertEquals("OPEN", ticketStatus(id));
        assertEquals(0L, eventCount(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "RESOLVED", "CANCELLED"})
    void nonOpenTicketCannotBeCancelled(String initialStatus) throws Exception {
        long id = createTicket();
        jdbc.update("UPDATE tickets SET status = ? WHERE id = ?", initialStatus, id);
        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isConflict());
        assertEquals(initialStatus, ticketStatus(id));
        assertEquals(0L, eventCount(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-found", "1abc", "0", "-1", "9223372036854775808"})
    void invalidIdReturnsNotFound(String id) throws Exception {
        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isNotFound());
    }

    @Test
    void missingTicketReturnsNotFound() throws Exception {
        long id = createTicket();
        jdbc.update("DELETE FROM tickets WHERE id = ?", id);
        mvc.perform(post("/api/tickets/{id}/cancel", id)).andExpect(status().isNotFound());
        assertEquals(0L, eventCount(id));
    }

    @Test
    void eventFailureRollsBackState() {
        long id = createTicket();
        doAnswer(invocation -> {
            // 此时处于业务事务内部，先证明第一条 SQL 确实执行成功。
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals("CANCELLED", ticketStatus(id));
            // 只在本测试注入故障，不在生产代码中添加故障开关。
            throw new DataIntegrityViolationException("测试：模拟操作记录写入失败");
        }).when(eventMapper).insert(id, 1001L, "CANCEL");

        assertThrows(DataIntegrityViolationException.class, () -> service.cancel(Long.toString(id)));
        // Service 代理已结束事务；下面的查询重新获取连接，检查数据库最终状态。
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("OPEN", ticketStatus(id));
        assertEquals(0L, eventCount(id));
    }

    private long createTicket() {
        long id = Long.parseLong(service.create("事务测试专用工单", "测试后自动清理").id());
        createdIds.add(id);
        return id;
    }

    private String ticketStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, id);
    }

    private long eventCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ticket_events WHERE ticket_id = ?", Long.class, id);
    }
}
