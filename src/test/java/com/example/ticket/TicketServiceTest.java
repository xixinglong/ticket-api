package com.example.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 单元测试使用模拟 Mapper，不启动 Spring、不连接数据库。
 * 这里只验证校验与数据转换，真实 SQL 的保存和查询另由集成测试验证。
 */
class TicketServiceTest {
    private final TicketMapper mapper = mock(TicketMapper.class);
    private final TicketEventMapper eventMapper = mock(TicketEventMapper.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TicketService service = new TicketService(mapper, eventMapper, currentUser);

    @BeforeEach
    void signIn() {
        when(currentUser.requireUser()).thenReturn(TestIdentity.alice());
        when(currentUser.requireReader()).thenReturn(TestIdentity.alice());
    }

    @Test
    void creationTrimsTitleAndPreservesDescription() {
        when(mapper.insert(any(TicketRow.class))).thenAnswer(invocation -> {
            TicketRow row = invocation.getArgument(0);
            assertEquals("Broken page", row.getTitle());
            assertEquals("OPEN", row.getStatus());
            assertEquals(Long.valueOf(1001L), row.getCreatorId());
            assertEquals("Blank after signing in", row.getDescription());
            row.setId(42L); // 模拟 JDBC 将数据库生成的主键回填到对象。
            return 1;
        });
        TicketResponse created = service.create("  Broken page  ", "Blank after signing in");
        assertEquals(new TicketResponse("42", "Broken page", "OPEN", "Blank after signing in"), created);
        verify(mapper).insert(any(TicketRow.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankOrNullTitleIsRejected(String title) {
        assertThrows(IllegalArgumentException.class, () -> service.create(title, null));
        verifyNoInteractions(mapper); // 非法输入不能写入数据库。
    }

    @Test
    void titleLongerThan100CharactersIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.create("a".repeat(101), null));
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-found", "1abc", "", "0", "-1", "9223372036854775808"})
    void invalidIdReturnsEmptyWithoutQueryingDatabase(String id) {
        assertTrue(service.find(id).isEmpty());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingTicketReturnsEmpty() {
        when(mapper.findById(99L)).thenReturn(null);
        assertTrue(service.find("99").isEmpty());
        verify(mapper).findById(99L);
    }

    @Test
    void queryConvertsDatabaseRowToResponse() {
        TicketRow row = new TicketRow();
        row.setId(42L);
        row.setCreatorId(1001L);
        row.setTitle("Broken page");
        row.setStatus("OPEN");
        row.setDescription("Blank after signing in");
        when(mapper.findById(42L)).thenReturn(row);
        assertEquals(new TicketResponse("42", "Broken page", "OPEN", "Blank after signing in"),
                service.find("42").orElseThrow());
    }

    // 模拟查询后状态被其他请求修改；最终 UPDATE 的行数才决定能否成功。
    @Test
    void cancellationWithNoChangedRowMustNotWriteEvent() {
        TicketRow row = new TicketRow();
        row.setId(42L);
        row.setCreatorId(1001L);
        row.setStatus("OPEN");
        when(mapper.findById(42L)).thenReturn(row);
        when(mapper.cancelOpenTicket(42L, 1001L)).thenReturn(0);
        assertThrows(TicketConflictException.class, () -> service.cancel("42"));
        verifyNoInteractions(eventMapper);
    }
}
