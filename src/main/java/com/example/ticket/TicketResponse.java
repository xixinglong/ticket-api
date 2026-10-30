package com.example.ticket;

/**
 * 创建和查询接口共用的响应数据，字段会输出到 JSON 中。
 * 当前练习也将这个不可变 record 存入内存 Map；它不是数据库实体。
 * record 自动生成按字段比较的 equals，测试中可直接比较两个工单。
 */
public record TicketResponse(
        String id,
        String title,
        String status,
        String description // 创建时传入的描述，未提供时为 null
) {}