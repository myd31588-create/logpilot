package com.logpilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logpilot.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测的是"模型返回不听话时怎么办" —— 这是 LLM 接入最容易出问题的地方。
 */
class LlmAnalyzerParseTest {

    private final LlmAnalyzer analyzer = new LlmAnalyzer(new LlmProperties(), new ObjectMapper());

    @Test
    void 能解析标准JSON() throws Exception {
        String json = """
                {"root_cause":"下游库存服务连接池耗尽","evidence":["连接超时 12 次"],"suggestions":["扩容库存服务"],"confidence":0.82,"need_human":false}
                """;

        LlmAnalysis a = analyzer.parse(json);

        assertEquals("下游库存服务连接池耗尽", a.rootCause());
        assertEquals(1, a.evidence().size());
        assertEquals(0.82, a.confidence(), 0.001);
        assertTrue(!a.needHuman());
    }

    @Test
    void 能解析被代码块包裹的JSON() throws Exception {
        String raw = """
                好的，分析结果如下：
                ```json
                {"root_cause":"慢SQL导致连接堆积","evidence":["慢查询 3 次"],"suggestions":["加索引"],"confidence":0.6}
                ```
                """;

        LlmAnalysis a = analyzer.parse(raw);

        assertEquals("慢SQL导致连接堆积", a.rootCause());
        assertEquals(0.6, a.confidence(), 0.001);
    }

    @Test
    void confidence越界时应该被夹到0到1之间() throws Exception {
        String json = "{\"root_cause\":\"x\",\"confidence\":3.5}";
        assertEquals(1.0, analyzer.parse(json).confidence(), 0.001);

        String json2 = "{\"root_cause\":\"x\",\"confidence\":-1}";
        assertEquals(0.0, analyzer.parse(json2).confidence(), 0.001);
    }

    @Test
    void 缺少root_cause应该抛异常以便走降级() {
        assertThrows(Exception.class, () -> analyzer.parse("{\"evidence\":[]}"));
    }

    @Test
    void 非JSON内容应该抛异常() {
        assertThrows(Exception.class, () -> analyzer.parse("我觉得是数据库的问题"));
    }
}
