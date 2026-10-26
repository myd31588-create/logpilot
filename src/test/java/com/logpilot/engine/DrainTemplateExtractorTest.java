package com.logpilot.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrainTemplateExtractorTest {

    @Test
    void 相同的日志形状应该聚成一个模板() {
        DrainTemplateExtractor extractor = new DrainTemplateExtractor(0.6d, 500);

        extractor.match("Connection to 10.0.0.11:8080 failed after 3000ms");
        extractor.match("Connection to 10.0.0.12:8080 failed after 5120ms");
        extractor.match("Connection to 10.0.0.13:8080 failed after 2980ms");

        assertEquals(1, extractor.clusterCount());
    }

    @Test
    void 形状不同的日志应该分开() {
        DrainTemplateExtractor extractor = new DrainTemplateExtractor(0.6d, 500);

        extractor.match("Connection to 10.0.0.11:8080 failed after 3000ms");
        extractor.match("java.lang.OutOfMemoryError: Java heap space");
        extractor.match("Slow query detected: select * from t_order where id = 1 took 2100ms");

        assertEquals(3, extractor.clusterCount());
    }

    @Test
    void 模板里的变量应该被替换成通配符() {
        DrainTemplateExtractor extractor = new DrainTemplateExtractor(0.6d, 500);

        String t1 = extractor.match("order 10086 created, userId=2001, amount=99.00").template;
        String t2 = extractor.match("order 10087 created, userId=2002, amount=129.50").template;

        assertEquals(t1, t2);
        assertTrue(t1.contains("<*>"), "模板应包含通配符: " + t1);
    }

    @Test
    void 脱敏应该覆盖手机号和身份证() {
        String masked = LogMasker.desensitize("user 13812345678 id 110101199001011234");
        assertTrue(masked.contains("<phone>"));
        assertTrue(masked.contains("<id>"));
    }
}
