package com.example.ticket;

import java.net.URI;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP 接口层：接收 JSON 和路径参数，调用 Service，再返回响应。
 * @RestController 让返回的数据对象写入响应体；本接口使用 JSON 表示工单。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    // 构造器注入：由 Spring 提供 TicketService 实例，多个请求共用它的内存数据。
    public TicketController(TicketService service) {
        this.service = service;
    }

    // POST /api/tickets：@RequestBody 读取 JSON，@Valid 检查请求对象上的校验约束。
    @PostMapping
    public ResponseEntity<TicketResponse> create(
            @Valid @RequestBody CreateTicketRequest request
    ) {
        TicketResponse ticket = service.create(request.title(),request.description());
        // 创建成功返回 201；Location 响应头指向新工单的查询地址。
        URI location = URI.create("/api/tickets/" + ticket.id());
        return ResponseEntity.created(location).body(ticket);
    }

    // GET /api/tickets/{id}：@PathVariable 将 URL 中的编号绑定到 id 参数。
    @GetMapping("/{id}")
    public TicketResponse find(@PathVariable("id") String id) {
        // 查到时返回工单（默认 200），空 Optional 则转换为 HTTP 404。
        return service.find(id).orElseThrow(() ->
                new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "工单不存在"
                )
        );
    }
}