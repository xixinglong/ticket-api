package com.example.ticket;

/**
 * 数据库行对象：保存本阶段需要的 tickets 列，与对外响应对象分开。
 * MyBatis 通过 setter 填充查询结果，并在 INSERT 后回填自增 id。
 * assignee_id 为空表示尚未分配处理人；created_at 由数据库默认值填写。
 */
public class TicketRow {
    private Long id;
    private String title;
    private String description;
    private Long creatorId;
    private Long assigneeId;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public Long getAssigneeId() { return assigneeId; }
    public void setAssigneeId(Long assigneeId) { this.assigneeId = assigneeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
