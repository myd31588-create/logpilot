# 在阿里云 ECS 上部署（2C2G / Ubuntu）

目标：让 LogPilot 在一台 2 核 2G 的 Ubuntu 上跑起来。

## 为什么不能直接在服务器上 build

`docker build` 要跑一遍 Maven 全量编译，2G 内存基本必 OOM。所以推荐：

- **本地构建镜像** → `docker save` → 传到服务器 → `docker load` → 服务器只负责跑
- 确需在服务器构建，先加 2G swap，并做好等 15 分钟的准备

## 1. 装 Docker

```bash
curl -fsSL https://get.docker.com | sh -s -- --mirror Aliyun
sudo systemctl enable --now docker
```

Ubuntu 18.04 已 EOL，如果脚本提示不支持的发行版：

```bash
curl -fsSL https://get.docker.com | sh -s -- --version 24.0.9
```

还不行就升级到 Ubuntu 22.04，别在 18.04 上耗。

确认 compose v2 可用：

```bash
docker compose version
```

## 2. 加 swap（2G 机器必做）

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

## 3. 本地构建并传输镜像

本地（有 Docker 的机器）：

```bash
docker build -t logpilot:0.2 .
docker save logpilot:0.2 -o logpilot-0.2.tar
scp logpilot-0.2.tar root@<ECS_IP>:/opt/logpilot/
```

服务器：

```bash
cd /opt/logpilot
docker load -i logpilot-0.2.tar
docker images | grep logpilot
```

## 4. 启动

把 `deploy/docker-compose.ecs.yml` 传到服务器同一个目录：

```bash
scp deploy/docker-compose.ecs.yml root@<ECS_IP>:/opt/logpilot/docker-compose.yml
```

然后：

```bash
cd /opt/logpilot
export LLM_API_KEY=sk-xxx     # 不设就走规则模式
docker compose up -d
docker compose ps
docker compose logs -f app
```

## 5. 放行端口

阿里云控制台 → 安全组 → 入方向 → 添加 `8080/8080`。

**授权对象填你自己的公网 IP，不要填 0.0.0.0/0。** 这个应用没有登录鉴权，暴露到公网等于把日志和 LLM 额度送人。

## 6. 验证

```bash
curl -s localhost:8080/actuator/health
# {"status":"UP"}

curl -s -X POST localhost:8080/api/v1/logs \
  -H 'Content-Type: application/json' \
  -d '{"lines":["2026-10-26 10:00:00 ERROR [order-service] Connection refused to redis 10.0.0.21:6379"]}'
# {"saved":1,"skipped":0,"duplicates":0,"templateCount":1}

curl -s -X POST localhost:8080/api/v1/analysis \
  -H 'Content-Type: application/json' -d '{}'
```

浏览器打开 `http://<ECS_IP>:8080` 即可。

## 2C2G 的资源账

| 组件 | 常驻内存 |
|---|---|
| MySQL 8（buffer pool 调到 128M） | ~400 MB |
| Redis | ~10 MB |
| App（-Xmx512m） | ~400 MB |
| 系统 + Docker | ~300 MB |

合计约 1.1–1.3 GB，2G 的机器能跑，但**不要在同一台机器上再跑 Jenkins**。
