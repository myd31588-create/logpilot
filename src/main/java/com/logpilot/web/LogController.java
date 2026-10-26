package com.logpilot.web;

import com.logpilot.repo.LogEventRepository;
import com.logpilot.service.AnalysisReport;
import com.logpilot.service.AnalysisService;
import com.logpilot.service.IngestService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对外 REST 接口，方便业务系统直接接入。
 */
@RestController
@RequestMapping("/api/v1")
public class LogController {

    private final IngestService ingestService;
    private final AnalysisService analysisService;
    private final LogEventRepository repository;

    public LogController(IngestService ingestService,
                         AnalysisService analysisService,
                         LogEventRepository repository) {
        this.ingestService = ingestService;
        this.analysisService = analysisService;
        this.repository = repository;
    }

    /** 批量上报日志 */
    @PostMapping("/logs")
    public IngestService.IngestResult ingest(@RequestBody LogBatchRequest request) {
        return ingestService.ingest(request == null ? null : request.lines());
    }

    /** 模板列表 */
    @GetMapping("/templates")
    public List<LogEventRepository.TemplateCount> templates(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String service) {
        var page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        return (service == null || service.isBlank())
                ? repository.findTemplateCounts(page)
                : repository.findTemplateCountsByService(service, page);
    }

    /** 触发一次根因分析 */
    @PostMapping("/analysis")
    public AnalysisReport analysis(@RequestBody(required = false) AnalysisRequest request) {
        String service = request == null ? null : request.service();
        int topN = request == null || request.topN() == null ? 20 : request.topN();
        return analysisService.analyze(service, topN);
    }

    public record LogBatchRequest(List<String> lines) {
    }

    public record AnalysisRequest(String service, Integer topN) {
    }
}
