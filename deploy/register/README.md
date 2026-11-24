# 注册服务（可选）

让用户自己在 App 里注册账号，不需要管理员手动建号。

## 原理

```
App 点「注册」→ POST /api/register
        ↓
本服务 → 用管理员身份登录信令 → 调 /api/admin/users/create 建号
        ↓
        → 再调 /api/admin/leases/create 授权全部设备（否则新账号看不到任何手机）
```

## 部署

```bash
mkdir -p /opt/handlink-register
cp server.js /opt/handlink-register/

cat > /etc/systemd/system/handlink-register.service <<'EOF'
[Unit]
Description=HandLink Register Service
After=network.target docker.service

[Service]
Type=simple
WorkingDirectory=/opt/handlink-register
Environment=PORT=443
Environment=UPSTREAM_PORT=8443
Environment=ADMIN_USER=admin
Environment=ADMIN_PASS=改成你的管理员密码
Environment=LIMIT=10
Environment=NODE_TLS_REJECT_UNAUTHORIZED=0
ExecStart=/usr/bin/node /opt/handlink-register/server.js
Restart=always

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload && systemctl enable --now handlink-register
```

端口 443 复用信令服务的证书（`/opt/handlink-tls`），App 端不需要额外信任。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | 9444 | 监听端口（建议 443，防火墙已开） |
| `UPSTREAM_PORT` | 8443 | 信令服务端口 |
| `ADMIN_USER` / `ADMIN_PASS` | admin / admin123 | 用于建号的管理员账号 |
| `LIMIT` | 10 | 账号总数上限 |
| `CERT` / `KEY` | `/opt/handlink-tls/server.crt` / `.key` | TLS 证书 |

## 接口

```
POST /api/register          {"username":"xxx","password":"xxxxxx"}
GET  /api/register/status   → {"used":3,"limit":10,"open":true}
GET  /healthz
```

## 注意

- 同一 IP 每小时最多 10 次注册尝试
- 账号名 3-20 位，字母/数字/下划线；密码至少 6 位
- 名额满了会返回「注册名额已满」
