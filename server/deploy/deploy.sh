#!/usr/bin/env bash
# JetDo 中继一键部署（Debian/Ubuntu + nginx）
#
#   用法： sudo bash deploy.sh <域名> [relay 端口]
#   示例： sudo bash deploy.sh jetdo.example.com
#
# 前置条件（脚本会检查，不满足就停下来告诉你缺什么）：
#   1. 该域名的 A 记录已指向本机公网 IP；安全组放行 80/443
#   2. 本机能拿到该域名的证书（五种来源见 ssl_setup）
#
# 本脚本只做加法：新增 /opt/jetdo-relay、一个 systemd unit、一个 nginx conf.d 文件，
# 不修改任何已有站点配置，重复执行安全。

set -euo pipefail

DOMAIN="${1:-}"
RELAY_PORT="${2:-${RELAY_PORT:-18080}}"
APP_DIR=/opt/jetdo-relay
APP_USER=jetdo
SVC_NAME=jetdo-relay
WEBROOT=/var/www/html
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NGINX_CONF_D="/etc/nginx/conf.d"
SSL_DIR="/etc/nginx/ssl"

log()  { printf '\033[36m[jetdo]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[jetdo]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[jetdo]\033[0m %s\n' "$*" >&2; exit 1; }

[[ -n "$DOMAIN" ]] || die "缺少域名。用法：sudo bash deploy.sh <域名> [端口]"
[[ $EUID -eq 0 ]] || die "需要 root：sudo bash deploy.sh $DOMAIN"
[[ -f "$SRC_DIR/relay.py" ]] || die "找不到 $SRC_DIR/relay.py，请在仓库的 server/ 目录下执行"
command -v nginx >/dev/null || die "nginx 未安装：apt-get install -y nginx"

# 端口不能撞已有服务：relay 绑 127.0.0.1:$RELAY_PORT，撞了会启动失败
# （这台机器上 8080 被 api 站点的死上游占着语义，4000 是 docker-proxy）。
if ss -lntH "sport = :$RELAY_PORT" 2>/dev/null | grep -q .; then
    die "端口 $RELAY_PORT 已被占用，换一个：sudo bash deploy.sh $DOMAIN 18081"
fi

log "域名：$DOMAIN    端口：$RELAY_PORT    源码：$SRC_DIR"

# ---------------------------------------------------------------- 1. 依赖
# 判定标准是 ensurepip 而不是 venv：Ubuntu 上 `import venv` 能过，但缺 python3.12-venv 时
# `python3 -m venv` 会在 ensurepip 处失败（报 "ensurepip is not available"）。
python3 -c 'import ensurepip' 2>/dev/null || die "缺 python3-venv：
  apt-get install -y python3-venv    # 或按发行版写 python3.12-venv"

# ---------------------------------------------------------------- 2. 目录与代码
id -u "$APP_USER" >/dev/null 2>&1 || { log "创建系统用户 $APP_USER"; useradd -r -s /usr/sbin/nologin "$APP_USER"; }
install -m 644 "$SRC_DIR/relay.py" "$SRC_DIR/requirements.txt" "$APP_DIR/"

if [[ ! -d "$APP_DIR/.venv" ]]; then
    log "创建 venv 并安装依赖（首次约 1-2 分钟）"
    python3 -m venv "$APP_DIR/.venv"
fi
"$APP_DIR/.venv/bin/pip" install -q --upgrade pip
"$APP_DIR/.venv/bin/pip" install -q -r "$APP_DIR/requirements.txt"

# ---------------------------------------------------------------- 3. .env
if [[ -f "$APP_DIR/.env" ]]; then
    warn ".env 已存在，保持不动（改配额请手工编辑 $APP_DIR/.env）"
    sed -i "s/^PORT=.*/PORT=$RELAY_PORT/" "$APP_DIR/.env"
else
    log "写入 $APP_DIR/.env"
    cat > "$APP_DIR/.env" <<ENV
PORT=$RELAY_PORT
# 日志目录。/opt 在 ProtectSystem=strict 下只读，别把 relay.log 落到 CWD。
LOG_DIR=/var/log/jetdo-relay

# ---- 推送中继（不配则服务仍可启动，只是推送不可用）----
# FIREBASE_CREDENTIALS_PATH=$APP_DIR/service-account.json
# VAPID_PRIVATE_KEY_PATH=$APP_DIR/vapid_private.pem
# VAPID_PUBLIC_KEY_PATH=$APP_DIR/vapid_public.pem
RELAY_BASE_URL=https://$DOMAIN
ENV
fi

# 已存在的 .env 可能缺 LOG_DIR（升级场景），补一行，不动其它内容。
grep -q '^LOG_DIR=' "$APP_DIR/.env" || echo "LOG_DIR=/var/log/jetdo-relay" >> "$APP_DIR/.env"

chown -R "$APP_USER:$APP_USER" "$APP_DIR"
chmod 750 "$APP_DIR"
chmod 600 "$APP_DIR/.env"

# ---------------------------------------------------------------- 4. systemd
log "安装 systemd 服务"
sed "s/--port 18080/--port $RELAY_PORT/" "$SRC_DIR/deploy/$SVC_NAME.service" \
    > "/etc/systemd/system/$SVC_NAME.service"
systemctl daemon-reload
systemctl enable "$SVC_NAME" >/dev/null
systemctl restart "$SVC_NAME"

for _ in $(seq 1 20); do
    curl -fsS --max-time 2 "http://127.0.0.1:$RELAY_PORT/health" >/dev/null 2>&1 && break
    sleep 1
done
curl -fsS --max-time 3 "http://127.0.0.1:$RELAY_PORT/health" >/dev/null 2>&1 \
    || die "服务没起来：journalctl -u $SVC_NAME -n 50 --no-pager"
log "服务已在 127.0.0.1:$RELAY_PORT 监听"

# ---------------------------------------------------------------- 5. 先只上 80，为 ACME 让路
# 443 的 server 块引用了证书文件，证书不存在时 nginx -t 直接失败，
# 所以必须先有一个只监听 80 的引导配置，把 /.well-known 放出去，再签证书。
NGINX_TARGET="$NGINX_CONF_D/jetdo-relay.conf"
cat > "$NGINX_TARGET" <<CONF
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    location /.well-known/acme-challenge/ { root $WEBROOT; }
    location / { return 404; }
}
CONF
nginx -t >/dev/null 2>&1 || { rm -f "$NGINX_TARGET"; die "引导配置未通过 nginx -t，已回滚"; }
systemctl reload nginx
log "引导配置已上线（80 端口，仅放行 ACME）"

# ---------------------------------------------------------------- 6. 证书
ssl_setup() {
    mkdir -p "$SSL_DIR"
    if [[ -s "$SSL_DIR/$DOMAIN.pem" && -s "$SSL_DIR/$DOMAIN.key" ]]; then
        log "已有证书 $SSL_DIR/$DOMAIN.pem，跳过签发"
        openssl x509 -in "$SSL_DIR/$DOMAIN.pem" -noout -issuer -enddate 2>/dev/null | sed 's/^/    /'
        return
    fi
    if [[ -s "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
        log "复用已有 Let's Encrypt 证书"
        ln -sf "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$SSL_DIR/$DOMAIN.pem"
        ln -sf "/etc/letsencrypt/live/$DOMAIN/privkey.pem"   "$SSL_DIR/$DOMAIN.key"
        return
    fi
    if command -v certbot >/dev/null; then
        log "certbot webroot 签发（$WEBROOT，走 80 端口 http-01）"
        if ! certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" \
                --non-interactive --agree-tos --keep-until-expiring 2>&1 | tail -5; then
            warn "首次尝试失败（可能缺少注册邮箱），重试一次"
            certbot certonly --webroot -w "$WEBROOT" -d "$DOMAIN" \
                --non-interactive --agree-tos --register-unsafely-without-email \
                --keep-until-expiring 2>&1 | tail -5 || true
        fi
        if [[ -s "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
            ln -sf "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" "$SSL_DIR/$DOMAIN.pem"
            ln -sf "/etc/letsencrypt/live/$DOMAIN/privkey.pem"   "$SSL_DIR/$DOMAIN.key"
            return
        fi
    fi
    if [[ -x "$HOME/.acme.sh/acme.sh" ]]; then
        log "acme.sh + dns_cf 签发（需要 CF_Token 环境变量）"
        "$HOME/.acme.sh/acme.sh" --issue -d "$DOMAIN" --dns dns_cf --keylength ec-256 || true
        "$HOME/.acme.sh/acme.sh" --install-cert -d "$DOMAIN" --ecc \
            --key-file       "$SSL_DIR/$DOMAIN.key" \
            --fullchain-file "$SSL_DIR/$DOMAIN.pem" \
            --reloadcmd      "systemctl reload nginx"
        return
    fi
    die "拿不到证书。三选一后重跑：
  a) apt-get install -y certbot        （本机已有则直接用）
  b) 装 acme.sh 并 export CF_Token=...
  c) 手工放证书到 $SSL_DIR/$DOMAIN.pem 和 .key"
}
ssl_setup
[[ -s "$SSL_DIR/$DOMAIN.pem" ]] || die "证书缺失：$SSL_DIR/$DOMAIN.pem"

# ---------------------------------------------------------------- 7. 正式配置
log "安装正式 nginx 站点配置"
sed -e "s/__DOMAIN__/$DOMAIN/g" -e "s/__PORT__/$RELAY_PORT/g" \
    "$SRC_DIR/deploy/nginx-jetdo-relay.conf" > "$NGINX_TARGET"
nginx -t || die "nginx -t 失败，配置已写入 $NGINX_TARGET 但未 reload"
systemctl reload nginx
log "nginx 已 reload"

# ---------------------------------------------------------------- 8. 验证
sleep 1
if curl -fsS --max-time 8 "https://$DOMAIN/health" >/dev/null 2>&1; then
    log "公网自检通过：https://$DOMAIN/health"
else
    warn "本机访问 https://$DOMAIN/health 失败：检查安全组 443、DNS 生效、证书域名"
fi
echo
echo "内部自检（绕过 nginx）："
curl -s --max-time 3 "http://127.0.0.1:$RELAY_PORT/health" || true
echo

cat <<EOF

下一步：
  1) 客户端若要接入推送，把 relay-config.json 里的 relay_endpoint 指到
       https://$DOMAIN/subscribe
  2) 日志：journalctl -u $SVC_NAME -f
EOF
