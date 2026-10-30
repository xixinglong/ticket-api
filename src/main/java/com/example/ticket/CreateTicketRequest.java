package com.example.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建工单的请求数据，由 Spring 将请求 JSON 转换为这个 record。
 * record 自动提供 title()、description() 等访问方法。
 * 标题约束由 Controller 参数上的 @Valid 触发；描述可省略，此时为 null。
 */
public record CreateTicketRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 100, message = "标题不能超过 100 个字符")
        String title,
        String description // 可选描述，当前按原样保存，不做长度或空白校验
) {}