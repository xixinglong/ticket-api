package com.example.ticket;

import java.util.List;

/** page 从 1 开始；total 只统计当前身份可见的工单，items 固定按编号倒序。 */
public record TicketPageResponse(List<TicketResponse> items, int page, int size, long total) {}
