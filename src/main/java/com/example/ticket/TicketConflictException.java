package com.example.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 当前状态不允许本次操作，返回 409；运行时异常也会触发事务回滚。 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TicketConflictException extends RuntimeException {
    public TicketConflictException() {
        super("工单状态已变化，无法执行本次操作");
    }
}
