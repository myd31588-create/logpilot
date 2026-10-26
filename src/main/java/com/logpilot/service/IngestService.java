package com.logpilot.service;

import com.logpilot.cache.Fingerprint;
import com.logpilot.cache.RedisGuard;
import com.logpilot.domain.LogEvent;
import com.logpilot.engine.DrainTemplateExtractor;
import com.logpilot.engine.LogMasker;
import com.logpilot.repo.LogEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 日志入库：解析 → 脱敏 → 指纹去重 → 模板匹配 → 落库。
 *
 * 去重放在入库前：故障风暴时同一条错误会刷几十万次，
 * 只保留窗口期内的第一条，既能定位问题又不会把库打爆。
 * Redis 不可用时去重自动关闭（RedisGuard 返回 true），退化为全量入库。
 */
@Service
public class IngestService {

    /** 单批上限，防止一次灌爆内存 */
    private static final int MAX_BATCH = 500;

    private final DrainTemplateExtractor extractor;
    private final LogEventRepository repository;
    private final RedisGuard redis;

    /** 去重窗口（秒）：同一指纹在这个时间内只入库一次 */
    @Value("${logpilot.dedup.window-seconds:60}")
    private int dedupWindowSeconds;

    public IngestService(DrainTemplateExtractor extractor, LogEventRepository repository, RedisGuard redis) {
        this.extractor = extractor;
        this.repository = repository;
        this.redis = redis;
    }

    @Transactional
    public IngestResult ingest(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new IngestResult(0, 0, 0, extractor.clusterCount());
        }
        List<LogEvent> batch = new ArrayList<>();
        int empty = 0;
        int duplicates = 0;
        Duration window = Duration.ofSeconds(Math.max(dedupWindowSeconds, 1));

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                empty++;
                continue;
            }
            if (batch.size() >= MAX_BATCH) {
                break;
            }
            LogLineParser.ParsedLog parsed = LogLineParser.parse(line);
            // 注意：模板匹配用原始 message（含变量），归一化在 Drain 内部完成；
            // 入库用脱敏后的内容，避免敏感信息落库。
            String template = extractor.match(parsed.message()).template;
            String safeContent = LogMasker.desensitize(parsed.message());

            String fingerprint = Fingerprint.of(parsed.service(), parsed.level(), safeContent);
            if (!redis.markIfAbsent("logpilot:fp:" + fingerprint, window)) {
                duplicates++;
                continue;
            }

            batch.add(new LogEvent(parsed.service(), parsed.level(), parsed.time(), safeContent, template));
        }
        if (!batch.isEmpty()) {
            repository.saveAll(batch);
        }
        return new IngestResult(batch.size(), empty, duplicates, extractor.clusterCount());
    }

    /** 页面上整段文本按行切分 */
    public IngestResult ingestText(String text) {
        if (text == null || text.isBlank()) {
            return new IngestResult(0, 0, 0, extractor.clusterCount());
        }
        return ingest(Arrays.asList(text.split("\\R")));
    }

    public boolean isDedupActive() {
        return redis.isAvailable();
    }

    /** 入库条数 / 空行数 / 去重条数 / 当前模板数 */
    public record IngestResult(int saved, int skipped, int duplicates, int templateCount) {
    }
}
