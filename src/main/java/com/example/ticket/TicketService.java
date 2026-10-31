package com.example.ticket;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** 业务层：校验输入并调用 Mapper，HTTP 状态码仍由 Controller 处理。 */
@Service
public class TicketService {
    private final TicketMapper mapper;
    private final TicketEventMapper eventMapper;
    private final CurrentUser currentUser;

    // 构造器注入：Spring 提供 MyBatis 生成的 Mapper 实现。
    public TicketService(TicketMapper mapper, TicketEventMapper eventMapper, CurrentUser currentUser) {
        this.mapper = mapper;
        this.eventMapper = eventMapper;
        this.currentUser = currentUser;
    }

    /** 创建工单；编号由 MySQL 生成，数据保存到 tickets 表。 */
    public TicketResponse create(String title, String description) {
        long actorId = currentUser.requireUser().getId();
        // 先校验原始长度，再去掉两端空白，保留上一课的标题规则。
        if (title == null || title.isBlank() || title.length() > 100) {
            throw new InvalidTicketException();
        }
        TicketRow row = new TicketRow();
        row.setTitle(title.strip());
        row.setDescription(description);
        row.setCreatorId(actorId);
        row.setStatus("OPEN");
        mapper.insert(row);
        return toResponse(row);
    }

    /** 同一个只读快照内查询总数和当前页，防止一次响应中的两者来自不同数据版本。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TicketPageResponse list(int page, int size) {
        TicketUser reader = currentUser.requireReader();
        if (page < 1 || size < 1 || size > 100) {
            throw new InvalidPaginationException();
        }
        // 先转 long 再乘，避免较大页码在 int 运算中溢出。
        long offset = ((long) page - 1) * size;
        boolean userRole = CurrentUser.hasRole(reader, "USER");
        long total = mapper.countVisible(reader.getId(), userRole);
        var items = mapper.findVisible(reader.getId(), userRole, size, offset).stream()
                .map(TicketService::toResponse).toList();
        return new TicketPageResponse(items, page, size, total);
    }

    /** 查不到或编号格式不合法时返回空，由 Controller 返回 404。 */
    public Optional<TicketResponse> find(String id) {
        TicketUser reader = currentUser.requireReader();
        return findRow(id)
                .filter(row -> canRead(row, reader))
                .map(TicketService::toResponse);
    }

    /**
     * 完整的取消动作共用一个事务：更新工单，然后写操作记录。
     * Controller 调用 Spring 管理的 Service 代理，事务在方法返回前提交。
     * 写记录抛出运行时异常时，不吞掉异常，让 Spring 撤销之前的更新。
     */
    @Transactional
    public void cancel(String id) {
        long actorId = currentUser.requireUser().getId();
        TicketRow row = findRow(id)
                .filter(ticket -> Long.valueOf(actorId).equals(ticket.getCreatorId()))
                .orElseThrow(TicketNotFoundException::new);
        // 前面的查询只检查可见性，能否更新以这条 SQL 的结果为准。
        int changed = mapper.cancelOpenTicket(row.getId(), actorId);
        if (changed != 1) {
            throw new TicketConflictException();
        }
        if (eventMapper.insert(row.getId(), actorId, "CANCEL") != 1) {
            throw new IllegalStateException("取消记录写入失败");
        }
    }

    /** 接单与操作记录在同一事务提交；任何一步失败都不能留下半条业务结果。 */
    @Transactional
    public void claim(String id) {
        TicketUser agent = currentUser.requireAgent();
        TicketRow row = findRow(id)
                .filter(ticket -> canRead(ticket, agent))
                .orElseThrow(TicketNotFoundException::new);
        // 查询仅判断可见性；并发请求可能在此之后改变状态，以 UPDATE 行数为准。
        if (mapper.claimOpenTicket(row.getId(), agent.getId()) != 1) {
            throw new TicketConflictException();
        }
        if (eventMapper.insert(row.getId(), agent.getId(), "CLAIM") != 1) {
            throw new IllegalStateException("接单记录写入失败");
        }
    }

    /** 完成工单与 RESOLVE 记录一起提交，写记录失败时恢复原状态。 */
    @Transactional
    public void resolve(String id) {
        TicketUser agent = currentUser.requireAgent();
        TicketRow row = findRow(id)
                .filter(ticket -> canRead(ticket, agent))
                .orElseThrow(TicketNotFoundException::new);
        // 可见的待接单工单也不能直接完成；处理人和状态必须同时满足 SQL 条件。
        if (mapper.resolveAssignedTicket(row.getId(), agent.getId()) != 1) {
            throw new TicketConflictException();
        }
        if (eventMapper.insert(row.getId(), agent.getId(), "RESOLVE") != 1) {
            throw new IllegalStateException("完成记录写入失败");
        }
    }

    private static boolean canRead(TicketRow row, TicketUser reader) {
        if (CurrentUser.hasRole(reader, "USER")) {
            return Long.valueOf(reader.getId()).equals(row.getCreatorId());
        }
        // 处理人可查看未分配的 OPEN 工单，以及已经分配给自己的工单。
        return ("OPEN".equals(row.getStatus()) && row.getAssigneeId() == null)
                || Long.valueOf(reader.getId()).equals(row.getAssigneeId());
    }

    private Optional<TicketRow> findRow(String id) {
        // 先验证再转数字，避免 MySQL 将 "1abc" 隐式转换为 1。
        if (id == null || !id.matches("[0-9]+")) {
            return Optional.empty();
        }
        final long numericId;
        try {
            numericId = Long.parseLong(id);
        } catch (NumberFormatException exception) {
            return Optional.empty(); // 超出 BIGINT / Java long 的范围。
        }
        if (numericId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findById(numericId));
    }

    // 数据库编号是 Long，对外仍返回字符串，保持已有接口格式。
    private static TicketResponse toResponse(TicketRow row) {
        return new TicketResponse(row.getId().toString(), row.getTitle(),
                row.getStatus(), row.getDescription());
    }
}
