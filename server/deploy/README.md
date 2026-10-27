# 部署中继（server/relay.py）

> 本文中的 `<server-ip>` / `<relay-host>` / `<existing-host>` / `<nginx-container>` / `<project>`
> 都是占位符 —— 换成你自己的服务器与域名。真实值只留在本地，不进仓库。
>
> 推荐：**中继域名单独用一个 zone**，不要和你有管理面板的域名同域。
> 客户端一旦启用推送，`relay-config.json` 里的中继地址会编进 APK（`strings` 可提取），
> 同域兄弟子域会被顺带枚举到；分开之后，泄漏一个域不会牵出后台。

先判一件事：**这台机器的 80/443 是谁在听。**

```bash
sudo ss -lntp | grep -E ':(80|443)\b'
```

| 输出 | 走哪条路 |
|---|---|
| `nginx: master process /usr/sbin/nginx` | **A**：`deploy.sh` 一把梭 |
| `docker-proxy` / 容器里的 nginx | **B**：配置必须进容器，见下 |

`<existing-host>` 那台腾讯云（`<server-ip>`）属于 **B**：443 由 `<nginx-container>`
容器占着，宿主机的 `nginx.service` 是 inactive。

---

## A. 宿主机 nginx（deploy.sh）

```bash
sudo bash deploy/deploy.sh <域名> [端口]
```

脚本依次做：建 jetdo 用户 → venv → `.env` → systemd → 本机自检 → 只上 80 的引导配置 →
签证书（已有证书 / certbot webroot / acme.sh dns_cf 三选一）→ 正式 443 配置 → 公网自检。
只往 `conf.d` 加一个文件，不动任何已有站点。

不用推送功能可以完全不管 VAPID / Firebase：缺配置时 `relay.py` 只告警不退出。

## B. 80/443 在容器里

以本机为例：容器 `<nginx-container>`（nginx:1.27-alpine），网络 `<project>`
（`172.19.0.0/16`，网关 `172.19.0.1`），配置来源是挂载的 templates 目录
`/opt/<project>/deploy/nginx/templates`（entrypoint 用 envsubst 渲染成 conf.d）。

### B1. 起服务时先改监听地址

`deploy.sh` 那套 unit 假定 nginx 在宿主机、能连 127.0.0.1。容器要访问宿主机必须走网关 IP，
所以 unit 里是 `--host 0.0.0.0 --port 18080`（对外由 ufw 兜住）。可以直接复用 deploy.sh
的前半段（建目录/venv/systemd），只把 nginx 部分跳过：

```bash
sudo bash deploy/deploy.sh <域名>   # 跑到 nginx 那步会失败，无妨；服务已经起来了
sudo curl -s http://127.0.0.1:18080/health
```

### B2. 放行容器 → 宿主机

Docker 桥接到宿主端口属于 INPUT，被 ufw 默认 DROP。不放开就是 **504**：

```bash
NET_ID=$(sudo docker network inspect <project> -f '{{.Id}}' | cut -c1-12)
sudo ufw allow in on "br-$NET_ID" to any port 18080 proto tcp comment 'JetDo relay from docker'
```

### B3. 先放 ACME，再签证书

证书签给容器里的 nginx 用，走它挂载的 certbot 目录：

```bash
# 临时只上 80 的配置，把 /.well-known 放出去
sudo tee /tmp/jetdo-acme.conf >/dev/null <<'CONF'
server {
    listen 80;
    server_name <relay-host>;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
CONF
sudo docker cp /tmp/jetdo-acme.conf <nginx-container>:/etc/nginx/conf.d/jetdo-relay.conf
sudo docker exec <nginx-container> nginx -t && sudo docker exec <nginx-container> nginx -s reload

sudo certbot certonly --webroot -w /opt/<project>/deploy/certbot/www \
  -d <relay-host> \
  --config-dir /opt/<project>/deploy/certbot/conf \
  --work-dir /var/lib/letsencrypt --logs-dir /var/log/letsencrypt \
  --non-interactive --agree-tos --keep-until-expiring
```

证书落在 `certbot/conf/live/<域名>/`，该目录以 ro 挂到容器的 `/etc/letsencrypt`，
所以容器里能直接引用。

### B4. 上正式配置

```bash
sudo docker cp docker-nginx/jetdo-relay.conf.template /tmp/jetdo-relay.conf
# 把 ${DOLLAR} 还原成 $（容器 env 里 DOLLAR=$）
sudo sed -i 's/\${DOLLAR}/$/g' /tmp/jetdo-relay.conf
# 填自己的域名
sudo sed -i 's/__RELAY_HOST__/<relay-host>/g' /tmp/jetdo-relay.conf
sudo docker cp /tmp/jetdo-relay.conf <nginx-container>:/etc/nginx/conf.d/jetdo-relay.conf
sudo docker exec <nginx-container> nginx -t
sudo docker exec <nginx-container> nginx -s reload
```

**注意**：`docker cp` 进去的配置只在容器生命周期内有效。要让它扛住容器重建，
同时把 `docker-nginx/jetdo-relay.conf.template`（保留 `${DOLLAR}` 原样）放进
`/opt/<project>/deploy/nginx/templates/`，entrypoint 会在下次启动时渲染回来。

### B5. 续期

这台机器已有的 cron 会跑 `docker compose ... certbot renew` 然后 `nginx -s reload`，
新证书在同一个 certbot 目录里，**自动跟着续**，不用额外配置。验证：

```bash
sudo certbot certificates --config-dir /opt/<project>/deploy/certbot/conf | grep -A2 jetdo
```

---

## 五个踩过的坑（都已在代码里修掉）

| 现象 | 原因 | 处理 |
|---|---|---|
| `OSError: Read-only file system: relay.log` | unit 里 `ProtectSystem=strict`，`/opt` 只读 | 加 `LogsDirectory=jetdo-relay`，`relay.py` 改读 `LOG_DIR`，写不进去就只打 stdout |
| `ensurepip is not available` | Ubuntu 有 `venv` 模块但没有 `python3.12-venv` | 判定改成 `import ensurepip`，装包后重跑 |
| 限流变成全局桶 | uvicorn 0.27 的 `--forwarded-allow-ips` **不支持 CIDR**，精确匹配才生效 | 用 `*`，配合 nginx 侧 `X-Forwarded-For $remote_addr` 覆盖 |
| 容器访问宿主 504 | ufw 默认 DROP 桥接进 INPUT 的新连接 | `ufw allow in on br-<netid> to any port 18080` |
| `unknown directive "http2"` | 该指令要 nginx ≥ 1.25.1，Ubuntu 24.04 自带的是 1.24 | 写 `listen 443 ssl http2;`（容器是 1.27，两种都行） |

另外两个环境结论：**别用 8080**（本机 `<existing-host>` 的反代指向 `127.0.0.1:8080`，
中继占了会顺带接管那个站点）。

## 验证清单

```bash
curl -s https://<域名>/health            # 应为 {"status":"ok",...}，不是别的服务的 JSON
curl -s -o /dev/null -w '%{http_code}\n' https://<域名>/crash   # 404（该端点已不存在）
sudo journalctl -u jetdo-relay -n 5 --no-pager                  # 无 error/panic
```
