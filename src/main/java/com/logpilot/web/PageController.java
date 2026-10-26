package com.logpilot.web;

import com.logpilot.repo.LogEventRepository;
import com.logpilot.service.AnalysisReport;
import com.logpilot.service.AnalysisService;
import com.logpilot.service.IngestService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 页面控制器。v0.1 用 Thymeleaf，不引前端框架 —— 一天能做完。
 */
@Controller
public class PageController {

    private final IngestService ingestService;
    private final AnalysisService analysisService;
    private final LogEventRepository repository;

    public PageController(IngestService ingestService,
                          AnalysisService analysisService,
                          LogEventRepository repository) {
        this.ingestService = ingestService;
        this.analysisService = analysisService;
        this.repository = repository;
    }

    @GetMapping("/")
    public String index(Model model) {
        fillCommon(model, null);
        return "index";
    }

    @PostMapping("/ingest")
    public String ingest(@RequestParam("text") String text, Model model) {
        IngestService.IngestResult result = ingestService.ingestText(text);
        fillCommon(model, null);
        model.addAttribute("message", "已入库 " + result.saved() + " 条，跳过空行 " + result.skipped()
                + " 条，去重 " + result.duplicates() + " 条，当前模板 " + result.templateCount() + " 个");
        return "index";
    }

    @PostMapping("/analyze")
    public String analyze(@RequestParam(value = "service", required = false) String service, Model model) {
        AnalysisReport report = analysisService.analyze(service, 20);
        fillCommon(model, report);
        model.addAttribute("message", "分析完成");
        return "index";
    }

    private void fillCommon(Model model, AnalysisReport report) {
        model.addAttribute("templates", repository.findTemplateCounts(PageRequest.of(0, 50)));
        model.addAttribute("recent", repository.findAllByOrderByIdDesc(PageRequest.of(0, 15)));
        model.addAttribute("total", repository.count());
        model.addAttribute("report", report);
        model.addAttribute("dedupActive", ingestService.isDedupActive());
    }
}
