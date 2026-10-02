# 部署指南

> ⚠️ **先读这一节再决定要不要部署。**
>
> 部署到公网**不是必须的**。项目本身（代码、README、评测报告、压测报告）已经能完整
> 证明你的能力，面试官看 GitHub 就够了。"有线上地址"是加分项，主要解决
> "面试官想亲手点一下"的场景。
>
> **代价**：一台学生云服务器约 10 元/月，加上配置和安全加固的时间（约 2–3 小时）。
> 而且**你需要持续维护它**——忘记续费、被扫描器打、证书过期都会变成麻烦。
>
> **建议**：如果预算紧张或时间不够，先跳过。本文档已经写好，等真要用的时候照着做。

---

## 一、方案对比

| 方案 | 成本 | 难度 | 适合场景 |
|---|---|---|---|
| **裸机 + systemd**（推荐） | ~10 元/月 | 中 | 学生机、长期运行、想学运维 |
| Docker Compose | ~10 元/月 | 中 | 换机方便、想顺便学容器 |
| 平台托管（Railway/Fly） | 免费额度有限 | 低 | 短期演示 |

裸机方案作为主推，因为**没有 Docker 这一层，出问题时你能直接看到进程和日志**。
初学阶段少一层抽象，排查成本低很多。

---

## 二、裸机部署（推荐）

### 0. 前置条件

- 一台 Linux 服务器（Ubuntu 22.04 / Debian 12 均可），1 核 2G 足够
- 一个域名（可选，没有就先用 IP 访问）

### 1. 装运行环境

```bash
# JRE 21（只需要运行时，不需要完整 JDK）
sudo apt update
sudo apt install -y openjdk-21-jre-headless

# MySQL 8
sudo apt install -y mysql-server
sudo mysql_secure_installation

# Redis
sudo apt install -y redis-server
```

### 2. 建库建账号

```bash
sudo mysql -u root -p
```

```sql
CREATE DATABASE model_gate
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ⚠️ 用强密码，不要用开发机的密码
CREATE USER 'modelgate_app'@'localhost' IDENTIFIED BY '<强密码>';
GRANT ALL PRIVILEGES ON model_gate.* TO 'modelgate_app'@'localhost';
FLUSH PRIVILEGES;
```

> **Flyway 会自动建表**，你不需要手工执行任何 DDL。启动时它会依次应用
> `V1__init.sql`、`V2__add_cache_and_cost.sql`、`V3__add_eval.sql`。

### 3. 上传应用

```bash
sudo mkdir -p /opt/modelgate
sudo useradd -r -s /usr/sbin/nologin modelgate
sudo chown modelgate:modelgate /opt/modelgate

# 在本地打包后上传（不要把 target/ 提交到 Git）
scp target/model-gate-0.1.0-SNAPSHOT.jar user@server:/tmp/
sudo mv /tmp/model-gate-0.1.0-SNAPSHOT.jar /opt/modelgate/app.jar
sudo chown modelgate:modelgate /opt/modelgate/app.jar
```

### 4. 配置凭据

**⚠️ 不要把 `.env` 从开发机直接传上去。** 用独立的生产凭据：

```bash
sudo tee /opt/modelgate/modelgate.env > /dev/null <<'EOF'
DB_URL=jdbc:mysql://127.0.0.1:3306/model_gate?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
DB_USER=modelgate_app
DB_PASSWORD=<强密码>
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
SERVER_PORT=8081
DEEPSEEK_API_KEY=<你的密钥>
EOF

# 权限收紧到只有服务账号能读
sudo chown modelgate:modelgate /opt/modelgate/modelgate.env
sudo chmod 600 /opt/modelgate/modelgate.env
```

### 5. 注册 systemd 服务

```bash
sudo tee /etc/systemd/system/modelgate.service > /dev/null <<'EOF'
[Unit]
Description=ModelGate LLM Gateway
After=network.target mysql.service redis-server.service
Wants=mysql.service redis-server.service

[Service]
Type=simple
User=modelgate
Group=modelgate
WorkingDirectory=/opt/modelgate
EnvironmentFile=/opt/modelgate/modelgate.env

# 堆内存按机器调整：2G 内存的机器给 512M 足够
ExecStart=/usr/bin/java -Xms256m -Xmx512m -XX:+UseG1GC -jar /opt/modelgate/app.jar

Restart=on-failure
RestartSec=10

# ---- 安全加固：即使应用被攻破，攻击面也尽量小 ----
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/modelgate

StandardOutput=journal
StandardError=journal
SyslogIdentifier=modelgate

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now modelgate
sudo systemctl status modelgate
```

### 6. 验证

```bash
curl http://127.0.0.1:8081/actuator/health
# 期望：{"status":"UP",...}

curl -X POST http://127.0.0.1:8081/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <你的key>" \
  -d '{"model":"auto","messages":[{"role":"user","content":"你好"}]}'
```

---

## 三、安全清单（**这一节最重要**）

把服务暴露到公网前，**逐条确认**。跳过任何一条都可能变成真实事故。

| # | 检查项 | 为什么 |
|---|---|---|
| 1 | **打开 `modelgate.gateway.require-api-key=true`** | 否则任何人扫到你的端口就能免费用你的 API 额度。**这是最容易出的事故。** |
| 2 | MySQL **只监听 127.0.0.1** | 数据库绝不暴露公网。云厂商的默认安全组通常已经拦了，但别依赖它 |
| 3 | Redis **只监听 127.0.0.1**，或设置密码 | **未设密码的 Redis 暴露公网是最经典的入侵入口**，可以直接写 SSH 公钥 |
| 4 | 防火墙只开必要端口 | `ufw allow 22,80,443`，不要 `ufw disable` |
| 5 | `.env` 权限 600 且属主是服务账号 | 同机器上其他用户不该能读到密钥 |
| 6 | **不用 root 运行应用** | systemd 里已经配了 `User=modelgate` |
| 7 | 关闭 SSH 密码登录，改用密钥 | `PasswordAuthentication no` |
| 8 | 日志里**不打印密钥** | 本项目已做脱敏（`ApiKeyPool.mask`），但别自己加日志时漏了 |
| 9 | 加 HTTPS（Nginx + Let's Encrypt） | 否则 API key 在网络上明文传输 |
| 10 | 限额配好（`ratelimit` 的配额） | 万一 key 泄露，配额是最后一道防线 |

### Nginx 反向代理 + HTTPS（可选但推荐）

```nginx
server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # LLM 调用可能很慢，超时要放宽（默认 60s 会截断长回复）
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }
}
```

```bash
sudo certbot --nginx -d your-domain.com
```

---

## 四、Docker 部署（备选）

仓库里有 `Dockerfile` 和 `docker-compose.yml`：

```bash
# 只起依赖，应用在宿主机跑（开发调试方便）
docker compose up -d mysql redis

# 全套容器化
export DEEPSEEK_API_KEY=<你的密钥>
docker compose up -d --build
```

> ⚠️ **这两个文件在本机未经实际构建验证**（开发机没装 Docker）。
> 第一次使用请先本地 `docker build` 确认，不要直接上生产。
>
> 特意标注出来，是因为**"看起来能用但实际跑不起来"的部署配置比没有更危险** ——
> 你会在最需要它的时候才发现问题。

---

## 五、常见故障排查

| 现象 | 原因 | 排查 |
|---|---|---|
| 启动失败，报 `FlywayValidateException` | 有人手工改过数据库表结构 | **不要手工改库**。Flyway 校验和对不上会拒绝启动 |
| 启动失败，报 `SchemaManagementException` | 实体和表结构不一致 | `ddl-auto: validate` 生效了。检查迁移脚本 |
| 接口 429 且带 `Retry-After` | 触发限流或配额 | 正常行为。调 `modelgate.ratelimit.*` 配置 |
| 所有请求都返回降级内容 | 上游全部不可用，或熔断已打开 | 看 `/api/lab/stats` 的熔断器状态；看日志里的 `retryable` |
| 接口没响应但进程还活着 | 可能是线程池被占满（静默死亡） | 看 `/api/lab/stats` 的 `activeThreads` 是否长期等于 `poolSize` |
| 时间显示差 8 小时 | 数据库里存的是 UTC | **预期行为**，见 `CallLog` 注释。原生 SQL 用 `UTC_TIMESTAMP()` |

---

## 六、部署后的验收清单

- [ ] `curl /actuator/health` 返回 `{"status":"UP"}`
- [ ] 能成功调用 `/v1/chat/completions` 拿到回复
- [ ] **不带 API key 调用被拒绝**（验证鉴权真的打开了）
- [ ] `/actuator/prometheus` 能看到 `modelgate_` 指标
- [ ] Dashboard 页面能打开且显示数据
- [ ] 重启后服务自动恢复（`systemctl restart modelgate`）
- [ ] 数据库里能看到调用记录
- [ ] 跑一次评测：`POST /api/eval/run?models=...`
