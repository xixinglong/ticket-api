package com.example.ticket;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 单元测试确保非法参数不会查库，且偏移量在乘法前转为 long。 */
class TicketPaginationUnitTests {
    @Test
    void invalidPaginationNeverQueriesMapper() {
        TicketMapper mapper = mock(TicketMapper.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.requireReader()).thenReturn(TestIdentity.alice());
        TicketService service = new TicketService(mapper, mock(TicketEventMapper.class), currentUser);
        assertThrows(InvalidPaginationException.class, () -> service.list(0, 20));
        assertThrows(InvalidPaginationException.class, () -> service.list(1, 0));
        assertThrows(InvalidPaginationException.class, () -> service.list(1, 101));
        verifyNoInteractions(mapper);
    }

    @Test
    void offsetUsesLongAndRoleComesFromAuthenticatedIdentity() {
        TicketMapper mapper = mock(TicketMapper.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.requireReader()).thenReturn(TestIdentity.agent(2001L));
        long offset = 214748364600L;
        when(mapper.findVisible(2001L, false, 100, offset)).thenReturn(List.of());
        TicketService service = new TicketService(mapper, mock(TicketEventMapper.class), currentUser);
        var page = service.list(Integer.MAX_VALUE, 100);
        assertTrue(page.items().isEmpty());
        verify(mapper).countVisible(2001L, false);
        verify(mapper).findVisible(2001L, false, 100, offset);
    }
}
