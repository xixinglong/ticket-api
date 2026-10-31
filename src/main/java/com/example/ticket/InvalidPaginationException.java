package com.example.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** 页码和每页数量不合法时返回 400，不能作为服务器内部错误处理。 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidPaginationException extends RuntimeException {
    public InvalidPaginationException() {
        super("page 必须大于等于 1，size 必须在 1 到 100 之间");
    }
}
