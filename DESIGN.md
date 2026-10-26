# LogPilot 设计文档

> 面向中小团队的 AI 日志根因分析平台：采集 → 聚类 → 归因 → 告警。
> 可私有化部署，LLM 可插拔，规则引擎兜底。
>
> 目标：2 周做出可演示 MVP，3 个月开源到 GitHub，作为可展示的代表项目。

---

## 1. 为什么做这个

### 1.1 来自真实经历

项目起点来自一段真实经历：

> 将巡检采集的运行日志与异常堆栈接入 AI 工具链做智能分类与根因初判（区分 OOM、配置错误、连接超时、慢 SQL 等类型）

这条现在只是一个 bullet。把它做成可运行、可演示、可开源的产品，它就从"我参与过"变成"我造过"。

### 1.2 真实痛点

中小团队（10~100 人研发）通常：

- 有日志，但没有 ES / 可观测平台（成本高、运维重）
- 出故障靠人肉 `grep` + `tail -f`，定位慢
- 有 ELK 也没人看，告警风暴里找不到根因

### 1.3 差异化

| 常见方案 | 问题 | LogPilot 的做法 |
|---|---|---|
| ELK / Loki | 部署重，只做检索不做归因 | 轻量部署，聚焦"归因" |
| SaaS 可观测平台 | 数据出内网，中小团队预算不够 | 私有化，单机可跑 |
| 纯 LLM 套壳 | 幻觉、成本高、不稳定 | **规则引擎 + LLM 混合**，LLM 可插拔 |
| 学术日志聚类工具 | 只有算法，没有工程 | 算法 + 微服务 + 部署，完整闭环 |

---

## 2. 架构

```
                         ┌─────────────────────────────────────────────┐
   业务系统 ──HTTP──▶    │  collector                                  │
   (或 file tail)        │  接收 / 解析 / 脱敏 / 指纹 / 写 MQ           │
                         └──────────────────┬──────────────────────────┘
                                            │ RocketMQ: log.raw
                                            ▼
                         ┌─────────────────────────────────────────────┐
                         │  core                                       │
                         │  消费 → Drain 模板提取 → 聚类 → 入库         │
                         │  检索 API / 告警规则 / 触发分析任务           │
                         └───────┬──────────────────────┬──────────────┘
                                 │ MySQL                │ RocketMQ: analysis.task
                                 ▼                      ▼
                         ┌──────────────┐      ┌──────────────────────┐
                         │   MySQL 8    │      │  ai                  │
                         │  日志/模板    │      │  Prompt 构造          │
                         │  任务/结果    │      │  LLM 调用 + 结构化解析 │
                         └──────────────┘      │  规则引擎兜底          │
                                               └──────────┬───────────┘
                                                          │
                                                          ▼
                                                    告警 / 报告
```

### 2.1 模块职责

| 模块 | 职责 | 关键点 |
|---|---|---|
| **collector** | HTTP 批量接收、格式解析（JSON / 文本 / 堆栈）、敏感信息脱敏、指纹计算、写 MQ | 幂等（同指纹去重）、背压（限流） |
| **core** | 消费日志、Drain 模板提取、模板聚类、入库、检索 API、告警规则、触发分析 | 算法核心在这里 |
| **ai** | 消费分析任务、构造 Prompt、调 LLM、解析结构化结果、规则兜底、落库 | 可插拔：OpenAI / DeepSeek / 通义 / 本地 Ollama |

### 2.2 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Java 17 | 团队主语言，生态成熟 |
| 框架 | Spring Boot 3.2 + Spring Cloud Alibaba | 与现有技术栈一致 |
| 注册配置 | Nacos（v0.2 引入，MVP 用配置文件） | 先跑通再上 |
| 网关 | Spring Cloud Gateway（可选） | 单入口、鉴权 |
| MQ | RocketMQ 5.x | 与现有技术栈一致，事务消息是核心特性 |
| 缓存 | Redis 7 | 去重、缓存、分布式锁 |
| DB | MySQL 8 | 分区表按天 |
| 前端 | Thymeleaf + ECharts（MVP）→ Vue3（v0.3） | 省时间 |
| 部署 | Docker Compose（MVP）→ K8s（v0.2） | 一键起 |
| CI/CD | GitHub Actions | 复用已有流水线 |

### 2.3 为什么不做的取舍

- **不用 Elasticsearch**：内存吃掉一半，2C2G 跑不动；MVP 用 MySQL 全文索引 + 模板匹配足够
- **不做多租户**：MVP 无意义，v0.3 再说
- **不做 9 个微服务**：3 个足够讲清楚，多了跑不起来
- **不做实时流处理**：不做 Flink，用 MQ 消费就够

---

## 3. 核心设计

### 3.1 日志处理链路

```
原始日志
  → 解析（提取 timestamp / level / service / traceId / message）
  → 脱敏（手机号、身份证、IP、token → ***）
  → 指纹（template_id + 参数哈希）
  → Redis 去重（同指纹 1 分钟内只存一次计数）
  → RocketMQ
  → core 消费
  → Drain 模板提取
  → 模板计数更新（Redis 计数 + 批量刷 MySQL）
  → 告警规则判断（阈值 / 突变）
  → 触发分析任务
```

### 3.2 日志模板提取（Drain 算法）

**为什么需要**：100 万条日志里可能只有 50 种"形状"，人看不过来。

**Drain 思路**（简化版）：

1. 按 token 数量分层（同层才比较）
2. 每层维护前缀树
3. 新日志进来，沿树找到最相似的模板
4. 相似度 > 阈值 → 归入该模板，把变化的位置替换成 `<*>`
5. 否则新建模板

示例：

```
原始:  Connection to 10.0.0.1:8080 failed after 3000ms
原始:  Connection to 10.0.0.2:9090 failed after 5000ms
模板:  Connection to <*>:<*> failed after <*>ms
```

**可展开的细节**：分层依据、相似度计算（token 相同比例）、通配符替换策略、模板爆炸问题（阈值调优）。

### 3.3 根因分析（规则 + LLM 混合）

**第一步：规则引擎（快、稳、零成本）**

| 特征 | 判定 |
|---|---|
| `java.lang.OutOfMemoryError` | 内存不足，建议扩容或排查泄漏 |
| `Connection refused` / `connect timed out` | 下游不可用，检查依赖服务与网络 |
| `Slow query` / 执行时间 > 阈值 | 慢 SQL，检查索引与执行计划 |
| 同一模板 5 分钟内突增 10 倍 | 疑似故障扩散 |
| 5xx 比例突增 + 上游超时 | 链路级故障 |

**第二步：LLM 归因（处理规则覆盖不到的情况）**

输入（结构化，不丢垃圾进去）：

```json
{
  "window": "2026-10-24 20:00 ~ 20:05",
  "service": "order-service",
  "top_templates": [
    {"template": "Connection to <*>:<*> failed after <*>ms", "count": 1280, "prev_count": 12},
    {"template": "java.lang.OutOfMemoryError: Java heap space", "count": 3, "prev_count": 0}
  ],
  "samples": ["...堆栈前 20 行..."],
  "rule_hits": ["downstream_unavailable", "memory_pressure"]
}
```

输出（强制 JSON Schema）：

```json
{
  "root_cause": "下游库存服务连接池耗尽，导致订单服务大量连接超时",
  "evidence": ["连接超时模板突增 100 倍", "同时段出现 OOM 告警"],
  "confidence": 0.82,
  "suggestion": ["检查库存服务连接池配置", "临时扩容订单服务实例"],
  "need_human": false
}
```

**兜底策略**：

- LLM 超时/失败 → 只返回规则结论，标记 `degraded: true`
- LLM 置信度 < 0.5 → 标记 `need_human: true`，不自动告警
- Prompt 注入防护：日志内容转义，不作为指令

### 3.4 数据模型

```sql
-- 日志模板
CREATE TABLE log_template (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  service       VARCHAR(64)  NOT NULL,
  template      VARCHAR(1024) NOT NULL,
  template_hash CHAR(32)     NOT NULL,
  first_seen    DATETIME     NOT NULL,
  last_seen     DATETIME     NOT NULL,
  total_count   BIGINT       DEFAULT 0,
  UNIQUE KEY uk_hash (template_hash)
);

-- 原始日志（按天分区，MVP 先普通表）
CREATE TABLE log_raw (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  service     VARCHAR(64)  NOT NULL,
  level       VARCHAR(16)  NOT NULL,
  log_time    DATETIME(3)  NOT NULL,
  template_id BIGINT,
  content     TEXT,
  trace_id    VARCHAR(64),
  KEY idx_service_time (service, log_time),
  KEY idx_template (template_id)
);

-- 分析任务
CREATE TABLE analysis_task (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  service      VARCHAR(64),
  window_start DATETIME,
  window_end   DATETIME,
  status       VARCHAR(16),  -- PENDING / RUNNING / DONE / FAILED
  created_at   DATETIME
);

-- 分析结果
CREATE TABLE analysis_result (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id     BIGINT,
  root_cause  VARCHAR(2048),
  evidence    JSON,
  confidence  DECIMAL(3,2),
  suggestion  JSON,
  degraded    TINYINT(1) DEFAULT 0,
  llm_model   VARCHAR(64),
  created_at  DATETIME
);
```

### 3.5 API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/logs` | 批量上报（≤500 条/次） |
| GET | `/api/v1/logs` | 检索（service / level / from / to / keyword） |
| GET | `/api/v1/templates` | 模板列表（按 count 排序） |
| GET | `/api/v1/templates/{id}/trend` | 模板计数趋势（图表用） |
| POST | `/api/v1/analysis` | 触发分析（传 service + 时间窗） |
| GET | `/api/v1/analysis/{id}` | 查询结果 |
| GET | `/actuator/prometheus` | 自身指标 |

---

## 4. 两周 MVP 计划

### 第 1 周：能灌能查

| 天 | 任务 | 验收 |
|---|---|---|
| D1 | 项目骨架、Docker Compose（MySQL + Redis + RocketMQ） | `docker compose up` 全绿 |
| D2 | collector：HTTP 接收 + 解析 + 脱敏 | curl 能灌进去 |
| D3 | 指纹 + Redis 去重 + 写 MQ | 重复日志只计一次 |
| D4 | core：消费 + Drain 模板提取 | 日志能聚成模板 |
| D5 | 检索 API + Thymeleaf 页面 | 浏览器能查、能看模板列表 |

### 第 2 周：能归因能演示

| 天 | 任务 | 验收 |
|---|---|---|
| D6 | 规则引擎（5 条规则） | 喂 OOM 日志能命中 |
| D7 | ai 模块：Prompt 构造 + LLM 调用 + JSON 解析 | 能出根因 |
| D8 | 降级兜底 + 置信度 | 断网时仍能出规则结论 |
| D9 | 页面：分析结果 + 趋势图 | 能截图做 README |
| D10 | README + GIF + 样例日志 + 开源准备 | 仓库可公开 |

### 砍掉（放 v0.2+）

多租户、权限体系、K8s 部署、Nacos、Vue 前端、告警通道（钉钉/企微）、日志采集 agent。

---

## 5. 开源策略

### 5.1 README 结构（决定 star）

```markdown
# LogPilot
一句话：AI 日志根因分析，5 分钟私有化部署

[架构图]
[GIF 演示：灌日志 → 出根因]

## 为什么做这个（3 行痛点）
## 快速开始（docker compose up -d，3 行命令）
## 效果（前后对比截图）
## 架构（一张图 + 3 行说明）
## 技术选型（为什么不用 ES、为什么规则 + LLM）
## Roadmap
## 贡献 & License
```

### 5.2 发布渠道

- V2EX（程序员节点）
- 掘金 / 知乎（技术文章）
- Linux.do
- 相关 GitHub Awesome 列表提 PR

### 5.3 持续运营

- 每周至少 1 次 commit（绿墙）
- 每个 issue 都回
- 打 tag：`log-analysis` `aiops` `observability` `llm` `root-cause`

---

## 6. 技术点分布

| 能力 | 项目里的体现 |
|---|---|
| Spring Cloud Alibaba 微服务 | 3 模块 + Nacos + Gateway |
| Redis 分布式锁 / 缓存穿透 | 指纹去重、任务锁、模板缓存 |
| RocketMQ 事务 / 顺序消息 | 日志异步写入、分析任务队列 |
| 算法应用 | Drain 模板提取、SimHash 聚类 |
| AI 工程化 | Prompt 设计、结构化输出、降级兜底 |
| K8s 部署 | 部署清单 + HPA |
| Jenkins | 自动构建部署 |
| 技术输出 | 3 篇系列文章 |

### 项目介绍模板

> **背景**：公司日志排查靠人肉 grep，一个故障要翻几台机器，定位慢。
> **任务**：做一个能自动归因的工具，且要能在内网私有化部署。
> **行动**：用 Drain 算法把日志聚成模板，把百万条压到几十个模板；再用规则引擎覆盖 80% 的已知故障模式，剩下 20% 交给 LLM；LLM 不可用时降级到规则结论。
> **结果**：故障定位从小时级降到分钟级；项目开源，X star。

---

## 7. 服务器方案（2C2G）

**不要在 ECS 上跑全栈。** 本地开发全量，服务器只跑演示。

| 环境 | 跑什么 |
|---|---|
| 本地（Docker Desktop） | collector + core + ai + MySQL + Redis + RocketMQ |
| ECS（2C2G） | 合并版单体的 demo + MySQL；Jenkins 另算 |

如果一定要在 ECS 全量跑，先升到 4G，否则 MQ 起不来。

---

## 8. 下一步

1. 确认方向（或换方向）
2. 我出项目骨架：目录结构 + pom + Docker Compose + 核心类
3. 你跑通第 1 周，我补第 2 周的 ai 模块
4. 跑通后写文章、开源
