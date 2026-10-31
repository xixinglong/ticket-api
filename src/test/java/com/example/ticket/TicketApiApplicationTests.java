package com.example.ticket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MySQL 集成测试：运行前配置 DB_URL、DB_USER、DB_PASSWORD，并完成建表。
 * 每个测试结束回滚插入记录；自增编号可能出现空缺，这是正常现象。
 * 这些测试验证真实 Mapper 与表结构；重启后仍可查询需要另外做手动验证。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TicketApiApplicationTests {
    @BeforeEach
    void signIn() { TestIdentity.signInAsAlice(); }

    @AfterEach
    void signOut() { TestIdentity.clear(); }

    @Autowired
    private TicketService service;

    @Test
    void twoCreatedTicketsHaveDifferentIdsAndCanBeFound() {
        TicketResponse first = service.create("  First ticket  ", null);
        TicketResponse second = service.create("Second ticket", null);
        assertNotEquals(first.id(), second.id());
        assertTrue(Long.parseLong(first.id()) > 0);
        assertEquals("First ticket", first.title());
        assertEquals("OPEN", first.status());
        assertEquals(first, service.find(first.id()).orElseThrow());
        assertEquals(second, service.find(second.id()).orElseThrow());
        assertNull(service.find(first.id()).orElseThrow().description());
    }

    @Test
    void descriptionIsPreservedInDatabase() {
        String description = "登录后白屏，点击按钮无反应。";
        TicketResponse created = service.create("登录页空白", description);
        assertEquals(description, created.description());
        assertEquals(created, service.find(created.id()).orElseThrow());
    }
}
