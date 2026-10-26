package com.logpilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 一条入库的日志。
 *
 * 设计说明：
 *  - template 字段是冗余存储的，为的是让"按模板聚合"能直接用 SQL 完成，
 *    不依赖内存里 Drain 的状态（重启后统计不丢）。
 *  - content 是脱敏后的内容，原始日志不落库。
 */
@Entity
@Table(name = "log_event", indexes = {
        @Index(name = "idx_service_time", columnList = "service,logTime"),
        @Index(name = "idx_template", columnList = "template")
})
public class LogEvent {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String service;

    @Column(nullable = false, length = 16)
    private String level;

    @Column(nullable = false)
    private LocalDateTime logTime;

    @Column(nullable = false, length = 4000)
    private String content;

    @Column(nullable = false, length = 1000)
    private String template;

    public LogEvent() {
    }

    public LogEvent(String service, String level, LocalDateTime logTime, String content, String template) {
        this.service = service;
        this.level = level;
        this.logTime = logTime;
        this.content = content;
        this.template = template;
    }

    public Long getId() {
        return id;
    }

    public String getService() {
        return service;
    }

    public String getLevel() {
        return level;
    }

    public LocalDateTime getLogTime() {
        return logTime;
    }

    public String getContent() {
        return content;
    }

    public String getTemplate() {
        return template;
    }

    /** 给页面用的格式化时间，避免模板里依赖 #temporals */
    public String getTimeText() {
        return logTime == null ? "" : logTime.format(FMT);
    }
}
