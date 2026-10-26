package com.logpilot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：走完整的 Controller → Service → Drain → Repository 链路。
 * 用内存 H2，不碰 ./data 下的文件库。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:logpilot-it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class LogPilotIntegrationTest {

    @Autowired
    private MockMvc mvc;

    private static final String BODY = """
            {"lines":[
              "2026-10-24 20:00:01 ERROR [inventory-service] Connection refused to redis 10.0.0.21:6379",
              "2026-10-24 20:00:02 ERROR [inventory-service] Connection refused to redis 10.0.0.21:6379",
              "2026-10-24 20:00:03 ERROR [order-service] java.lang.OutOfMemoryError: Java heap space"
            ]}
            """;

    @Test
    void 上报日志后能聚类并给出根因() throws Exception {
        mvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(3));

        mvc.perform(get("/api/v1/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(2)));

        mvc.perform(post("/api/v1/analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.rootCause", containsString("疑似")))
                .andExpect(jsonPath("$.hits.length()").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void 空批次不报错() throws Exception {
        mvc.perform(post("/api/v1/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(0));
    }

    @Test
    void 首页可以打开() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk());
    }

    @Test
    void 健康检查端点可用且不因Redis缺失而判定为down() throws Exception {
        // K8s 的 readiness/liveness 探针打的就是这个地址；
        // Redis 是可选依赖，没有它也必须返回 UP，否则 Pod 会被反复重启。
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
