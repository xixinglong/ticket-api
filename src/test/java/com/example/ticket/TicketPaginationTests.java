package com.example.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 MySQL 过滤与分页测试；只读功能可用测试事务自动回滚本类插入的样本。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TicketPaginationTests {
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private TicketService service;
    @Autowired private TicketMapper mapper;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private TicketUser owner;
    private TicketUser other;
    private TicketUser agent;
    private TicketUser otherAgent;

    @BeforeEach
    void setUp() {
        TestIdentity.clear();
        // 避免把本机已有 alice/bob 工单数量当成固定测试数据。
        long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        owner = new TicketUser(base, "page-owner", "unused", "USER");
        other = new TicketUser(base + 1, "page-other", "unused", "USER");
        agent = new TicketUser(base + 2, "page-agent", "unused", "AGENT");
        otherAgent = new TicketUser(base + 3, "page-agent-other", "unused", "AGENT");
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void clearIdentity() {
        TestIdentity.clear();
    }

    @Test
    void userPagesHaveStableDescendingOrderAndOnlyOwnTotal() throws Exception {
        long oldest = insert(owner, "OPEN", null);
        insert(other, "OPEN", null); // 他人的数据穿插在自己的记录之间，必须先过滤再分页。
        long middle = insert(owner, "CANCELLED", null);
        long newest = insert(owner, "RESOLVED", agent.getId());
        insert(other, "IN_PROGRESS", otherAgent.getId());
        TestIdentity.signIn(owner);
        var first = service.list(1, 2);
        var second = service.list(2, 2);
        assertEquals(3L, first.total());
        assertEquals(3L, second.total());
        assertEquals(List.of(Long.toString(newest), Long.toString(middle)), ids(first));
        assertEquals(List.of(Long.toString(oldest)), ids(second));
        assertEquals(first, service.list(1, 2));
        assertTrue(service.list(3, 2).items().isEmpty());
        assertEquals(3L, service.list(3, 2).total());

        // 即便伪造 creatorId、actorId、角色和排序参数，也只能看到当前身份的数据。
        mvc.perform(get("/api/tickets").with(user(owner)).param("page", "1").param("size", "2")
                .param("creatorId", Long.toString(other.getId())).param("actorId", Long.toString(other.getId()))
                .param("userRole", "false").param("sort", "id ASC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(newest)));
    }

    @Test
    void defaultsEmptyUserAndLargePageHaveCorrectMetadata() throws Exception {
        mvc.perform(get("/api/tickets").with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        insert(owner, "OPEN", null);
        mvc.perform(get("/api/tickets").with(user(owner)).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void agentListAndDetailHaveSameVisibilityIncludingResolvedTickets() throws Exception {
        // 其他已有的 OPEN 工单也合法可见，因此核对本次样本，并遍历各页比对 total。
        long cancelled = insert(owner, "CANCELLED", null);
        long foreignResolved = insert(other, "RESOLVED", otherAgent.getId());
        long foreignAssigned = insert(owner, "IN_PROGRESS", otherAgent.getId());
        long ownResolved = insert(other, "RESOLVED", agent.getId());
        long ownAssigned = insert(owner, "IN_PROGRESS", agent.getId());
        long open = insert(other, "OPEN", null);
        TestIdentity.signIn(agent);
        var first = service.list(1, 100);
        assertEquals(List.of(Long.toString(open), Long.toString(ownAssigned), Long.toString(ownResolved)),
                ids(first).subList(0, 3));
        var allIds = new ArrayList<String>();
        for (int page = 1; ; page++) {
            var result = service.list(page, 100);
            assertEquals(first.total(), result.total());
            allIds.addAll(ids(result));
            if ((long) page * 100 >= first.total()) {
                break;
            }
        }
        assertEquals(first.total(), (long) allIds.size());
        assertEquals((long) allIds.size(), allIds.stream().distinct().count());
        for (long invisible : List.of(cancelled, foreignResolved, foreignAssigned)) {
            assertFalse(allIds.contains(Long.toString(invisible)));
            assertTrue(service.find(Long.toString(invisible)).isEmpty());
        }
        for (String id : allIds) {
            assertTrue(service.find(id).isPresent(), "列表与详情的权限应一致");
        }
        mvc.perform(get("/api/tickets").with(user(agent)).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(Long.toString(open)));
    }

    @Test
    void unknownRoleAndAnonymousCannotReadList() throws Exception {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tickets").with(user(new TicketUser(999L, "outsider", "unused", "OTHER"))))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "1.5", "2147483648", "9223372036854775808"})
    void invalidPageReturns400(String page) throws Exception {
        mvc.perform(get("/api/tickets").with(user(owner)).param("page", page))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "abc", "1.5", "2147483648"})
    void invalidSizeReturns400(String size) throws Exception {
        mvc.perform(get("/api/tickets").with(user(owner)).param("size", size))
                .andExpect(status().isBadRequest());
    }

    private long insert(TicketUser creator, String status, Long assigneeId) {
        TicketRow row = new TicketRow();
        row.setTitle("Pagination fixture");
        row.setDescription("Rolled back after test");
        row.setCreatorId(creator.getId());
        row.setStatus(status);
        mapper.insert(row);
        if (assigneeId != null) {
            // 仅设置测试样本归属，不伪造业务动作成功记录。
            jdbc.update("UPDATE tickets SET assignee_id = ? WHERE id = ?", assigneeId, row.getId());
        }
        return row.getId();
    }

    private List<String> ids(TicketPageResponse page) {
        return page.items().stream().map(TicketResponse::id).toList();
    }
}
