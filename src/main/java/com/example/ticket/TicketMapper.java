package com.example.ticket;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据访问接口：MyBatis 根据同名 XML 语句生成实现，不需要手写实现类。 */
@Mapper
public interface TicketMapper {
    // 返回受影响行数；数据库生成的编号回填到 row.id。
    int insert(TicketRow row);

    // 条件必须放在 UPDATE 中，避免检查之后状态被其他请求改变。
    int cancelOpenTicket(@Param("id") long id, @Param("actorId") long actorId);

    // 单条条件更新决定接单胜者，不能只依赖先前查询到的 OPEN 状态。
    int claimOpenTicket(@Param("id") long id, @Param("actorId") long actorId);

    // 必须仍是当前处理人的 IN_PROGRESS 工单，重复完成时更新行数为 0。
    int resolveAssignedTicket(@Param("id") long id, @Param("actorId") long actorId);

    // 角色分支由 Service 根据登录身份计算，不能直接使用客户端传入的值。
    long countVisible(@Param("actorId") long actorId, @Param("userRole") boolean userRole);

    List<TicketRow> findVisible(@Param("actorId") long actorId,
            @Param("userRole") boolean userRole, @Param("size") int size,
            @Param("offset") long offset);

    // @Param 将参数命名为 id，供 XML 的参数占位符使用；查不到返回 null。
    TicketRow findById(@Param("id") long id);
}
