# LogPilot · 日志根因分析

[![CI](https://github.com/myd31588-create/logpilot/actions/workflows/ci.yml/badge.svg)](https://github.com/myd31588-create/logpilot/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](pom.xml)

> 把百万条日志压成几十个模板，再用规则 + LLM 找出根因。
> 面向中小团队，单机可跑，私有化部署。

**v0.2**：采集 → 指纹去重 → Drain 聚类 → 规则归因 → LLM 增强 → 页面展示。
Redis 承担去重 / 任务锁 / 结果缓存，三者均可降级；支持 Docker 与 K8s 部署。

---

## 为什么做这个

中小团队通常有三种状态：

- 有日志，但没有 ELK / 可观测平台（成本高、运维重）
- 有 ELK，但只用来搜关键字，没人做归因
- 出事靠人肉 `grep` + `tail -f`，定位以小时计

LogPilot 只做一件事：**把"看日志"变成"看结论"**。

---

## 快速开始

### 方式一：Docker（推荐，不用装 JDK）

前置条件：本机 Docker 已运行。Windows 上需要 Docker Desktop + WSL2（没有 WSL 发行版时
Docker Desktop 起不来，会报 `failed to connect to the docker API at npipe:...`）。

```bash
docker compose up -d --build
```

一条命令起 App + MySQL + Redis，等 30 秒左右访问 <http://localhost:8080>。

```bash
docker compose logs -f app     # 看日志
docker compose down            # 停止
docker compose down -v         # 停止并清空数据
```

### 方式二：本地跑

要求：JDK 17+、Maven 3.8+

```bash
mvn spring-boot:run
```

**Windows 注意**：如果终端默认的 `JAVA_HOME` 指向 JDK 8，`mvn` 会报
`Fatal error compiling: 无效的标记: --release`。直接用仓库里的脚本，已经固定好 JDK 17：

| 脚本 | 作用 |
|---|---|
| `run.cmd` | 启动应用（8080） |
| `debug.cmd` | 启动并开放调试端口 5005 |
| `build.cmd` | 打包；`build.cmd test` 跑测试 |

打开 <http://localhost:8080>，把 `samples/demo.log` 的内容粘进去，点「入库并聚类」。

默认用 H2 内存库，不需要装 MySQL；重启即清空，要持久化见下面的「切 MySQL」。

### 用接口灌日志

```bash
curl -X POST http://localhost:8080/api/v1/logs \
  -H 'Content-Type: application/json' \
  -d '{"lines":["2026-10-24 20:00:01 ERROR [order-service] Connection to 10.0.0.11:8080 failed after 3000ms"]}'
```

### 触发分析

```bash
curl -X POST http://localhost:8080/api/v1/analysis \
  -H 'Content-Type: application/json' -d '{}'
```

输出：

```json
{
  "rootCause": "疑似「下游服务不可用」，优先排查该方向",
  "confidence": 0.75,
  "degraded": true,
  "templateCount": 17,
  "evidence": ["4 次  Connection to <*> failed after <*>ms"],
  "suggestions": ["下游服务不可用：确认目标服务是否存活、端口是否监听、网络策略/安全组是否放行"]
}
```

---

## 它是怎么工作的

```
原始日志
  → 解析（时间 / 级别 / 服务 / 内容）
  → 归一化（IP、手机号、UUID、时间戳、数字 → <*>）
  → Drain 模板提取（同形状的日志合并成模板）
  → 落库（脱敏后的内容 + 模板）
  → 规则引擎（OOM / 下游不可用 / 慢 SQL / 连接池 / 磁盘 / 超时 / 鉴权）
  → 根因报告（根因 + 证据 + 置信度 + 建议）
```

### 模板提取（Drain 简化实现）

```
原日志：Connection to 10.0.0.11:8080 failed after 3000ms
        Connection to 10.0.0.12:8080 failed after 5120ms
模板  ：Connection to <*> failed after <*>ms
```

30 条样例日志 → 17 个模板。真正的日志量下，压缩比通常在 100:1 以上。

### 为什么是「规则 + LLM」而不是纯 LLM

| | 规则 | LLM |
|---|---|---|
| 已知故障 | 快、准、零成本 | 没必要 |
| 未知故障 | 覆盖不到 | 能推理 |
| 可用性 | 不依赖外部服务 | 会超时、会限流 |

所以 v0.2 的设计是：**规则先跑，覆盖不到的交 LLM；LLM 挂了，规则结论照样能交付。**
报告里的 `degraded: true` 就是这个意思。

---

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/logs` | 批量上报（≤500 条） |
| GET | `/api/v1/templates` | 模板列表（按次数倒序） |
| POST | `/api/v1/analysis` | 根因分析 |

---

## 接入 LLM（可选）

不配也能跑——没 key 时自动跳过，报告标记 `degraded: true`，只出规则结论。

```powershell
$env:LLM_API_KEY = "sk-xxx"
run.cmd
```

配置项（`application.yml` 里的 `logpilot.llm.*`）：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enabled` | true | 总开关 |
| `api-key` | 空 | 建议用环境变量 `LLM_API_KEY` 注入 |
| `base-url` | `https://api.deepseek.com/v1` | 任何 OpenAI 兼容服务 |
| `model` | `deepseek-chat` | |
| `timeout-seconds` | 8 | 超时即降级 |
| `max-templates` | 12 | 控制 token 成本 |

报告里三个字段说明它走了哪条路：

```json
{ "degraded": false, "llmModel": "deepseek-chat", "degradedReason": null }
{ "degraded": true,  "degradedReason": "LLM 调用失败：Connection refused" }
```

### 没有 API Key 怎么演示

仓库带了一个假的 OpenAI 兼容服务，返回固定结论，专门用来演示/联调：

```bash
python tools/fake-llm.py
java -jar target/logpilot.jar --logpilot.llm.base-url=http://127.0.0.1:9099/v1 --logpilot.llm.api-key=test
```

这样面试时即使没有 key、没有网，也能演示完整的 LLM 归因链路。

---

## Redis（去重 / 锁 / 缓存）

Redis 是**增强项，不是依赖**。没装 Redis 时项目照常启动，三个能力自动降级：

| 用途 | 有 Redis | 没 Redis |
|---|---|---|
| 日志指纹去重 | 同一指纹 60s 内只入库一次 | 全量入库 |
| 分析任务锁 | 同服务分析串行，避免并发烧 token | 不做互斥 |
| LLM 结论缓存 | 模板快照没变时复用上次结论 | 每次都调用 |

验证过的效果：

```
第一次灌 30 条  -> {"saved":29,"duplicates":1}    # 样例里有两行完全相同
第二次灌同样 30 条 -> {"saved":0,"duplicates":30}
第一次分析 146ms / 第二次分析 11ms（命中缓存）
```

启动 Redis：

```bash
docker compose up -d redis
```

### 没有 Redis 怎么演示

```bash
python tools/fake-redis.py     # 监听 6380
run.cmd --spring.data.redis.port=6380
```

一个最小 RESP 实现，支持 SET NX PX / GET / DEL / PING，够跑通去重和缓存。

---

## Docker / K8s

### 镜像

多阶段构建：Maven 编译 → JRE 运行，最终镜像不含源码和构建工具。

几个刻意的选择：

| 做法 | 原因 |
|---|---|
| 先 `COPY pom.xml` 再 `COPY src` | 依赖层可缓存，改代码不用重拉依赖 |
| `useradd appuser` + `USER` | 不用 root 跑应用 |
| `JAVA_TOOL_OPTIONS=-Xmx512m` | 容器里 JVM 会按宿主机内存算堆，必须显式限制，否则容易被 OOM Kill |
| `HEALTHCHECK` 用 `/dev/tcp` | 镜像里没有 curl，用 bash 内置的 TCP 探测 |

### K8s

```bash
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml          # 从 secret.example.yaml 复制
kubectl apply -f k8s/deployment.yaml
kubectl port-forward svc/logpilot 8080:80
```

细节见 [k8s/README.md](k8s/README.md)。其中一条值得单独说：

**Deployment 固定 1 副本**，因为 Drain 的模板状态在 JVM 内存里，多副本会各算各的。
要水平扩展，得先把状态外置到 Redis（v0.3）。**宁可部署得保守，也不要一个看起来能扩展、结果却不对的系统。**

部署到真实 Linux 服务器（2C2G 也能跑）见 [docs/deploy-ecs.md](docs/deploy-ecs.md)。

---

## 切 MySQL

```bash
docker compose up -d mysql
mvn spring-boot:run -Dspring-boot.run.profiles=mysql
```

---

## 路线图

- [x] v0.1 采集 + 聚类 + 规则归因
- [x] v0.2 LLM 归因（可插拔、可降级）
- [x] v0.2 Redis 去重 / 任务锁 / 结果缓存
- [x] v0.2 Docker 多阶段构建 + K8s 清单
- [ ] v0.3 RocketMQ 异步管道、模板状态外置（支持水平扩展）
- [ ] v0.4 告警通道（钉钉 / 企微）
- [ ] v0.5 前端重写（Vue3）、多租户

---

## 目录

```
├── src/main/java/com/logpilot/
│   ├── engine/   Drain 模板提取、脱敏、规则引擎（纯 Java，无框架依赖）
│   ├── llm/      LLM 调用、Prompt 构造、降级模型
│   ├── cache/    Redis 封装（熔断降级）、日志指纹
│   ├── service/  入库、分析
│   ├── domain/   JPA 实体
│   ├── repo/     仓储
│   ├── web/      REST + 页面
│   └── config/   Bean 装配
├── k8s/          部署清单
├── deploy/       服务器用 compose（只跑镜像，不构建）
├── docs/         部署文档
├── tools/        本地联调用的假 LLM / 假 Redis
└── samples/      样例日志
```

`engine` 包刻意不依赖 Spring —— 便于单测，也便于以后抽成独立库。

---

## License

Apache-2.0
