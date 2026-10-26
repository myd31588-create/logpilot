# K8s 部署说明

## 文件

| 文件 | 用途 |
|---|---|
| `configmap.yaml` | 非敏感配置（环境变量） |
| `secret.example.yaml` | 敏感配置模板，复制成 `secret.yaml` 后填值 |
| `deployment.yaml` | 应用 Deployment + Service |
| `dev-deps.yaml` | 本地演示用的 MySQL / Redis（生产别用） |

## 部署

```bash
# 1. 构建镜像（本地 docker 环境）
docker compose build app

# 2. kind 加载镜像（用 kind 时必需，否则 Pod 拉不到本地镜像）
kind load docker-image logpilot:0.2

# 3. 准备 secret
cp k8s/secret.example.yaml k8s/secret.yaml
# 编辑 secret.yaml 填 MYSQL_PASSWORD 和 LLM_API_KEY

# 4. 应用
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/dev-deps.yaml      # 仅本地演示
kubectl apply -f k8s/deployment.yaml

# 5. 验证
kubectl rollout status deploy/logpilot
kubectl port-forward svc/logpilot 8080:80
```

## 为什么只有 1 个副本

日志模板（Drain 提取器的状态）目前存在 JVM 内存里。多副本时每个 Pod 各算各的模板，同一个服务在不同 Pod 上会得到不同模板，统计就不可比了。

要水平扩展，得先把模板状态外置到 Redis（v0.3 计划），在此之前**不要调大 `replicas`**。

这个取舍是刻意的：**宁可部署得保守，也不要一个看起来能扩展、实际结果不对的系统。**
