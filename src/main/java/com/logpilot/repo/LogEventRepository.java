package com.logpilot.repo;

import com.logpilot.domain.LogEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LogEventRepository extends JpaRepository<LogEvent, Long> {

    /** 模板聚合：按出现次数倒序 */
    @Query("select e.template as template, count(e) as cnt from LogEvent e " +
           "group by e.template order by count(e) desc")
    List<TemplateCount> findTemplateCounts(Pageable pageable);

    /** 只统计某个服务的模板 */
    @Query("select e.template as template, count(e) as cnt from LogEvent e " +
           "where e.service = :service group by e.template order by count(e) desc")
    List<TemplateCount> findTemplateCountsByService(@Param("service") String service, Pageable pageable);

    List<LogEvent> findAllByOrderByIdDesc(Pageable pageable);

    long countByService(String service);

    /** 投影接口：JPQL 的别名会自动映射成 getter */
    interface TemplateCount {
        String getTemplate();

        long getCnt();
    }
}
