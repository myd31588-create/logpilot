# 在阿里云 ECS 上部署（2C2G / Ubuntu 18）

目标：让 LogPilot 在一台 2 核 2G、已经装了宝塔的 Ubuntu 上跑起来。

## 先选方案

| | 方案 A：原生部署（推荐） | 方案 B：Docker |
|---|---|---|
| 适合 | 2G 小机器、系统已经跑着别的东西 | 4G 以上、环境干净 |
| 内存占用 | 低（只多一个 JVM） | 高（Docker 守护进程 + 镜像层） |
| 服务器要不要装 Maven | 不要（本地打好 jar 传上去） | 不要 |
| 排障难度 | 低，systemd 日志直接看 | 中 |

**2C2G 且已装宝塔的机器，选 A。** 宝塔本身已经占掉 800M 左右，再叠 Docker 很容易 OOM。

---

# 方案 A：原生部署

## 0. 先体检

在服务器上跑这几条，确认现状：

```bash
free -h                 # 内存
df -h /                 # 磁盘
lsb_release -a          # 系统版本
systemctl status mysql redis-server nginx --no-pager 2>/dev/null | grep -E "●|Active"
```

## 1. 加 swap（2G 机器必做）

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
free -h
```

## 2. 装 JRE 17

Ubuntu 18.04 的官方源里没有 JDK 17，用清华镜像下载 Temurin：

```bash
cd /tmp
FILE=$(curl -sSL https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/linux/ \
       | grep -o 'OpenJDK17U-jre_x64_linux_hotspot_[0-9._]*\.tar\.gz' | head -1)
echo "下载 $FILE"
curl -sSL -o jre17.tar.gz "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jre/x64/linux/$FILE"
sudo mkdir -p /opt/logpilot
sudo tar -xzf jre17.tar.gz -C /opt/logpilot
sudo mv /opt/logpilot/jdk-17* /opt/logpilot/jre
/opt/logpilot/jre/bin/java -version
```

看到 `openjdk version "17.x"` 就对了。

## 3. 装 MySQL 和 Redis

```bash
sudo apt update
sudo apt install -y mysql-server redis-server
sudo systemctl enable --now mysql redis-server
```

> **如果 `apt update` 报 404**：Ubuntu 18.04 已 EOL，官方源被移走了。要么换成阿里云镜像源，
> 要么把 `/etc/apt/sources.list` 里的 `archive.ubuntu.com` 换成 `old-releases.ubuntu.com`。

**压住 Redis 内存**（2G 机器不设上限会被写满）：

```bash
sudo sed -i 's/^# maxmemory .*/maxmemory 64mb/' /etc/redis/redis.conf
sudo sed -i 's/^# maxmemory-policy .*/maxmemory-policy allkeys-lru/' /etc/redis/redis.conf
sudo systemctl restart redis-server
```

## 4. 建库建用户

```bash
sudo mysql -e "CREATE DATABASE IF NOT EXISTS logpilot DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql -e "CREATE USER IF NOT EXISTS 'logpilot'@'localhost' IDENTIFIED BY '换成你的强密码';"
sudo mysql -e "GRANT ALL PRIVILEGES ON logpilot.* TO 'logpilot'@'localhost'; FLUSH PRIVILEGES;"
```

## 5. 传 jar 上去

**在你本地**（Windows PowerShell）执行：

```powershell
cd D:\work_space\deepseek_work\logpilot
build.cmd -DskipTests          # 打出 target\logpilot.jar
scp target\logpilot.jar root@<服务器IP>:/opt/logpilot/
scp deploy\systemd\logpilot.service root@<服务器IP>:/tmp/
scp deploy\logpilot.env.example root@<服务器IP>:/opt/logpilot/logpilot.env
```

> 服务器没装 ssh 客户端不影响，scp 是本机发起的。

## 6. 配 systemd

服务器上：

```bash
# 建运行用户
sudo useradd -r -s /sbin/nologin logpilot 2>/dev/null || true
sudo chown -R logpilot:logpilot /opt/logpilot

# 改密码（一定要改）
sudo nano /opt/logpilot/logpilot.env
sudo chmod 600 /opt/logpilot/logpilot.env

# 装服务
sudo cp /tmp/logpilot.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now logpilot
```

## 7. 验证

```bash
sudo systemctl status logpilot --no-pager
sudo journalctl -u logpilot -n 50 --no-pager
curl -s localhost:8080/actuator/health
```

`{"status":"UP"}` 就成功了。

## 8. 放行端口

阿里云控制台 → 安全组 → 入方向 → 添加 `8080/8080`。

**授权对象填你自己的公网 IP，不要填 0.0.0.0/0。** 这个应用没有登录鉴权。

---

# 方案 B：Docker

前置：服务器已装 Docker，且**不要在服务器上 build**（2G 必 OOM）。

```bash
# 本地构建（需要本机 Docker 可用）
docker build -t logpilot:0.2 .
docker save logpilot:0.2 -o logpilot-0.2.tar
scp logpilot-0.2.tar root@<服务器IP>:/opt/logpilot/

# 服务器
docker load -i logpilot-0.2.tar
scp deploy/docker-compose.ecs.yml root@<服务器IP>:/opt/logpilot/docker-compose.yml
docker compose up -d
```

---

## 附：资源账（2C2G）

| 组件 | 常驻内存 |
|---|---|
| 宝塔 + Nginx | ~800 MB（已经在跑） |
| MySQL 5.7（buffer pool 128M） | ~250 MB |
| Redis（maxmemory 64M） | ~15 MB |
| App（-Xmx320m） | ~400 MB |
| 系统 | ~200 MB |

合计约 1.6–1.7 GB，**必须开 swap**，并且不要再在这台机器上跑 Jenkins。

## 常见问题

**启动就退出，日志里是 `Communications link failure`**
MySQL 没起来或密码不对。`sudo systemctl status mysql`，再核对 `logpilot.env`。

**`Address already in use: 8080`**
宝塔或其他服务占了端口。改 `logpilot.env` 加 `SERVER_PORT=8081`，
或在 systemd 里加 `Environment=SERVER_PORT=8081`。

**页面能开但接口 500**
看 `journalctl -u logpilot`，多半是数据库连不上或表还没建（首次启动会自动建表）。
