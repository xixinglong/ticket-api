package com.example.ticket;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * HTTP 接口层：接收 JSON 和路径参数，调用 Service，再返回响应。
 * @RestController 让返回的数据对象写入响应体；本接口使用 JSON 表示工单。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    // 构造器注入：由 Spring 提供 TicketService 实例，数据由 Service 通过 Mapper 读写。
    public TicketController(TicketService service) {
        this.service = service;
    }

    // GET /api/tickets?page=1&size=20：只接收分页参数，身份由登录会话确定。
    @GetMapping
    public TicketPageResponse list(@RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return service.list(page, size);
    }

    // POST /api/tickets：@RequestBody 读取 JSON，@Valid 检查请求对象上的校验约束。
    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request
    ) {
        TicketResponse ticket = service.create(request.title(), request.description());
        // 创建成功返回 201；Location 响应头指向新工单的查询地址。
        URI location = URI.create("/api/tickets/" + ticket.id());
        return ResponseEntity.created(location).body(ticket);
    }

    // POST /api/tickets/{id}/cancel：操作者来自服务端验证过的登录身份。
    // 请求不接收用户编号或目标状态，成功后返回 204，无响应体。
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable("id") String id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    // 接单不接收 assigneeId；处理人编号由当前登录身份确定。
    @PostMapping("/{id}/claim")
    public ResponseEntity<Void> claim(@PathVariable("id") String id) {
        service.claim(id);
        return ResponseEntity.noContent().build();
    }

    // 只有当前处理人可以完成工单；请求不接收目标状态或操作者编号。
    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable("id") String id) {
        service.resolve(id);
        return ResponseEntity.noContent().build();
    }

    // GET /api/tickets/{id}：@PathVariable 将 URL 中的编号绑定到 id 参数。
    @GetMapping("/{id}")
    public TicketResponse find(@PathVariable("id") String id) {
        // 查到时返回工单（默认 200），空 Optional 则转换为 HTTP 404。
        return service.find(id).orElseThrow(TicketNotFoundException::new);
    }
}
