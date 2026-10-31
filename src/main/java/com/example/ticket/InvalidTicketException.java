package com.example.ticket;

/** 可预期的输入错误；与程序内部的 IllegalArgumentException 区分，避免误报 400。 */
public class InvalidTicketException extends IllegalArgumentException {
    public InvalidTicketException() {
        super("标题不能为空，且不能超过 100 个字符");
    }
}
