package com.example.ticket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * 业务层：校验标题、分配编号、保存和查询工单，不负责 HTTP 状态码。
 * @Service 将它交给 Spring 管理，供 Controller 注入使用。
 */
@Service
public class TicketService {
    // 原子递增保证同一 Service 实例内并发创建时编号不同；重新创建实例后从零计数。
    private final AtomicLong sequence = new AtomicLong();
    // 支持并发访问的内存存储：键是工单编号，值是工单数据；服务重启后数据丢失。
    private final Map<String, TicketResponse> tickets =
            new ConcurrentHashMap<>();

    /** 创建工单，清理标题两端空白，并将初始状态设为 OPEN。 */
    public TicketResponse create(String title, String description) {
        // Service 也校验输入，确保直接调用它的单元测试等入口仍遵守业务规则。
        // 先判断 null，利用 || 的短路求值避免调用 null.isBlank()。
        // 长度按原始字符串检查，通过后才 strip；String.length() 按 UTF-16 代码单元计数。
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new IllegalArgumentException("标题不合法");
        }
        // incrementAndGet() 先加一再取值，因此第一次创建得到编号 "1"。
        String id = Long.toString(sequence.incrementAndGet());
        TicketResponse ticket =
                new TicketResponse(id, title.strip(), "OPEN", description);
        // description 原样保留，包括 null；后续查询取回的就是这里保存的工单。
        tickets.put(id, ticket);
        return ticket;
    }

    /** 查询结果可能不存在，用 Optional 表达，由调用方决定如何处理。 */
    public Optional<TicketResponse> find(String id) {
        // Map 查不到时返回 null，ofNullable 将其转换成 Optional.empty()。
        return Optional.ofNullable(tickets.get(id));
    }
}