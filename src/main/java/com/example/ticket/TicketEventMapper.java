package com.example.ticket;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 操作记录与工单使用同一个数据源，参与 Service 开启的同一个事务。 */
@Mapper
public interface TicketEventMapper {
    int insert(@Param("ticketId") long ticketId,
               @Param("actorId") long actorId,
               @Param("action") String action);
}
