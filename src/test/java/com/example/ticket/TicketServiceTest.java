package com.example.ticket;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Service 单元测试：直接 new 对象，不启动 Spring，也不发送 HTTP 请求。
 * 每个测试创建独立的 Service，避免内存数据和编号相互影响。
 * 这些测试检查业务行为，不验证 Controller 的 201、400、404 等状态码。
 */
class TicketServiceTest {
    // 创建后可查到，并且标题两端空白已去除、初始状态为 OPEN。
    @Test
    void createdTicketCanBeFound() {
        TicketService service = new TicketService();
        TicketResponse created = service.create("  Broken page  ", null);
        TicketResponse found = service.find(created.id()).orElseThrow();
        assertEquals("Broken page", found.title());
        assertEquals("OPEN", found.status());
    }

    // assertThrows 验证调用会抛出指定异常；这里只检查 Service 的校验。
    @Test
    void blankTitleIsRejected() {
        TicketService service = new TicketService();
        assertThrows(IllegalArgumentException.class,
                () -> service.create("   ", null));
    }

    // 1. null 标题应该被拒绝
    @Test
    void nullTitleIsRejected() {
        TicketService service = new TicketService();

        assertThrows(IllegalArgumentException.class,
                () -> service.create(null, null));
    }

    // 2. 超过 100 个字符的标题应该被拒绝
    @Test
    void titleLongerThan100CharactersIsRejected() {
        TicketService service = new TicketService();
        String title = "a".repeat(101);

        assertThrows(IllegalArgumentException.class,
                () -> service.create(title, null));
    }

    // 3. 查询不存在的工单，应该返回空 Optional
    @Test
    void missingTicketReturnsEmpty() {
        TicketService service = new TicketService();

        assertTrue(service.find("not-found").isEmpty());
    }

    // 4. 连续创建两张工单，编号不同，并且分别能查询到
    @Test
    void twoCreatedTicketsHaveDifferentIdsAndCanBeFound() {
        TicketService service = new TicketService();

        TicketResponse first = service.create("First ticket", null);
        TicketResponse second = service.create("Second ticket", null);

        assertNotEquals(first.id(), second.id());

        // orElseThrow 保证结果存在；assertEquals 再按 record 字段比较数据是否一致。
        TicketResponse foundFirst =
                service.find(first.id()).orElseThrow();
        TicketResponse foundSecond =
                service.find(second.id()).orElseThrow();

        assertEquals(first, foundFirst);
        assertEquals(second, foundSecond);
    }

    // 同时验证创建响应和查询结果，确保 description 在保存、读取过程中没有丢失。
    @Test
    void descriptionIsPreserved() {
        TicketService service = new TicketService();

        TicketResponse created =
                service.create("Login page is blank", "Blank after signing in");
        TicketResponse found = service.find(created.id()).orElseThrow();

        assertEquals("Blank after signing in", created.description());
        assertEquals("Blank after signing in", found.description());
    }
}
