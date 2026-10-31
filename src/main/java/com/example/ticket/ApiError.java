package com.example.ticket;

/** 所有接口失败都使用相同结构；requestId 用于把响应与服务端日志对应起来。 */
public record ApiError(String code, String message, String requestId) {}
