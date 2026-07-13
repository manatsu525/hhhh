#!/usr/bin/env bash
# =============================================================================
# LibreChat 一体化管理脚本
#
# 合并自：
#   1) LibreChat + SearXNG 低配 VPS 交互式部署
#   2) 轻量本地网页抓取器安装/更新（可选 Firecrawl 云端回退）
#   3) LibreChat 分层卸载与清理
#
# 说明：
#   - 选择 1 完成基础部署后，可再次运行本脚本选择 2 加装本地抓取器。
#   - 选择 3 会逐项确认，不会未经询问直接删除 Docker 或 swap。
# =============================================================================
set -uo pipefail

R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; B='\033[0;36m'; N='\033[0m'
menu_die() { echo -e "${R}[x]${N} $*" >&2; exit 1; }

install_librechat() (
# =============================================================================
#  LibreChat + SearXNG 一键交互式部署脚本 (面向 1C1G 小鸡)
#  - 目标: 最小硬盘占用 + 最低 CPU/内存占用
#  - 关闭 Meilisearch / RAG API / Redis, MongoDB 限制缓存, SearXNG 单 worker
#  - 支持 Groq 作为模型后端
# =============================================================================
set -euo pipefail

R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; B='\033[0;36m'; N='\033[0m'
info() { echo -e "${B}[*]${N} $*"; }
ok()   { echo -e "${G}[✓]${N} $*"; }
warn() { echo -e "${Y}[!]${N} $*"; }
err()  { echo -e "${R}[x]${N} $*" >&2; }
die()  { err "$*"; exit 1; }

ask() { # ask VAR "提示" "默认值"
  local __v=$1 __p=$2 __d=${3:-} __in
  if [[ -n $__d ]]; then read -rp "$(echo -e "${B}?${N} $__p [${__d}]: ")" __in; __in=${__in:-$__d}
  else read -rp "$(echo -e "${B}?${N} $__p: ")" __in; fi
  printf -v "$__v" '%s' "$__in"
}
ask_secret() { local __v=$1 __p=$2 __in; read -rsp "$(echo -e "${B}?${N} $__p: ")" __in; echo; printf -v "$__v" '%s' "$__in"; }
yesno() { local __p=$1 __d=${2:-y} __in; read -rp "$(echo -e "${B}?${N} $__p (y/n) [$__d]: ")" __in; __in=${__in:-$__d}; [[ ${__in,,} == y* ]]; }

[[ $EUID -eq 0 ]] || die "请用 root 运行 (sudo bash $0)"
command -v apt-get >/dev/null || die "此脚本仅适配 Debian / Ubuntu"

echo -e "${G}=====  LibreChat + SearXNG 部署向导  =====${N}\n"

# --------------------------------------------------------------------------
# 0. 环境体检
# --------------------------------------------------------------------------
MEM_MB=$(free -m | awk '/^Mem:/{print $2}')
DISK_GB=$(df -BG --output=avail / | tail -1 | tr -dc '0-9')
info "内存: ${MEM_MB}MB   根分区可用: ${DISK_GB}GB"
(( DISK_GB >= 5 )) || warn "可用磁盘小于 5G，本地 MongoDB 方案可能装不下（建议选 Atlas 云数据库）"

# --------------------------------------------------------------------------
# 1. 交互收集配置
# --------------------------------------------------------------------------
ask INSTALL_DIR "安装目录" "/opt/librechat"

echo
ask_secret GROQ_API_KEY "Groq API Key (gsk_...)"
[[ $GROQ_API_KEY == gsk_* ]] || warn "看起来不像 Groq 的 key，但继续。"

echo
echo "数据库方案："
echo "  1) 本地 MongoDB 容器  (约 +700MB 硬盘 / +250MB 内存)"
echo "  2) MongoDB Atlas 免费云库 M0  (省硬盘省内存，1C1G 强烈推荐)"
ask DB_CHOICE "选择" "1"
MONGO_URI=""
if [[ $DB_CHOICE == 2 ]]; then
  echo "  去 https://cloud.mongodb.com 建一个免费 M0 集群，Network Access 里放行 0.0.0.0/0"
  ask MONGO_URI "粘贴连接串 (mongodb+srv://user:pass@xxx/LibreChat?retryWrites=true&w=majority)"
  [[ $MONGO_URI == mongodb* ]] || die "连接串不合法"
fi

echo
echo "网页抓取器 (Scraper)：搜索出链接后需要它把网页正文抓回来喂给模型。"
echo "  自建 Firecrawl 至少要 2G 内存，1C1G 跑不动，所以用官方免费额度的云 key。"
echo "  去 https://firecrawl.dev 注册可拿到免费额度；留空则依赖 LibreChat 内置的简易抓取（效果较差）。"
ask FIRECRAWL_API_KEY "Firecrawl API Key (可留空)" ""

echo
if yesno "是否有域名并配置 HTTPS？(选 n 则用 http://IP:端口 直接访问)" "n"; then
  USE_CADDY=1
  ask DOMAIN "域名 (需已解析到本机 IP)"
  ask ACME_EMAIL "证书通知邮箱" "admin@${DOMAIN}"
  APP_URL="https://${DOMAIN}"
else
  USE_CADDY=0
  ask HTTP_PORT "对外端口" "3080"
  PUB_IP=$(curl -fsS4 --max-time 5 ifconfig.me 2>/dev/null || echo "YOUR_IP")
  APP_URL="http://${PUB_IP}:${HTTP_PORT}"
fi

echo
SWAP_TOTAL=$(free -m | awk '/^Swap:/{print $2}')
ADD_SWAP=0
if (( SWAP_TOTAL < 1024 )); then
  warn "当前 swap 仅 ${SWAP_TOTAL}MB。1G 内存跑 LibreChat 没有 swap 极易被 OOM 杀死。"
  yesno "创建 2GB swap 文件？" "y" && ADD_SWAP=1
fi

echo
echo -e "${Y}--- 请确认 ---${N}"
echo "  目录:     $INSTALL_DIR"
echo "  数据库:   $([[ $DB_CHOICE == 2 ]] && echo 'MongoDB Atlas (云)' || echo '本地容器')"
echo "  访问地址: $APP_URL"
echo "  抓取器:   $([[ -n $FIRECRAWL_API_KEY ]] && echo 'Firecrawl' || echo '内置简易抓取')"
echo "  Swap:     $([[ $ADD_SWAP == 1 ]] && echo '新建 2GB' || echo '不改动')"
yesno "开始安装？" "y" || die "已取消"

# --------------------------------------------------------------------------
# 2. Swap
# --------------------------------------------------------------------------
if [[ $ADD_SWAP == 1 ]]; then
  info "创建 swap..."
  fallocate -l 2G /swapfile 2>/dev/null || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=none
  chmod 600 /swapfile && mkswap -q /swapfile && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl -qw vm.swappiness=20; grep -q '^vm.swappiness' /etc/sysctl.conf || echo 'vm.swappiness=20' >> /etc/sysctl.conf
  ok "swap 已启用"
fi

# --------------------------------------------------------------------------
# 3. Docker
# --------------------------------------------------------------------------
if ! command -v docker >/dev/null; then
  info "安装 Docker (国内机器若卡住可 Ctrl+C 后自行换源安装)..."
  export DEBIAN_FRONTEND=noninteractive
  apt-get -qq update && apt-get -qq install -y curl ca-certificates
  curl -fsSL https://get.docker.com | sh
  ok "Docker 安装完成"
else
  ok "Docker 已存在"
fi
docker compose version >/dev/null 2>&1 || die "缺少 docker compose 插件"

# 限制日志大小，防止小硬盘被日志撑爆
mkdir -p /etc/docker
if [[ ! -f /etc/docker/daemon.json ]]; then
  cat > /etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "2" }
}
EOF
  systemctl restart docker
  ok "已限制容器日志大小"
fi

# --------------------------------------------------------------------------
# 4. 生成配置文件
# --------------------------------------------------------------------------
mkdir -p "$INSTALL_DIR"/{searxng,data/images,data/uploads,data/logs}
cd "$INSTALL_DIR"

CREDS_KEY=$(openssl rand -hex 32)
CREDS_IV=$(openssl rand -hex 16)
JWT_SECRET=$(openssl rand -hex 32)
JWT_REFRESH_SECRET=$(openssl rand -hex 32)
SEARXNG_SECRET=$(openssl rand -hex 32)

[[ $DB_CHOICE == 1 ]] && MONGO_URI="mongodb://mongodb:27017/LibreChat"

info "写入 .env ..."
cat > .env <<EOF
HOST=0.0.0.0
PORT=3080
DOMAIN_CLIENT=${APP_URL}
DOMAIN_SERVER=${APP_URL}
NO_INDEX=true

MONGO_URI=${MONGO_URI}

# 省内存: 关闭 Meilisearch 全文检索(会话搜索功能不可用) 和 RAG 向量库
SEARCH=false
MEILI_NO_ANALYTICS=true

# 只启用 Groq(custom) 与 agents
ENDPOINTS=agents,custom
GROQ_API_KEY=${GROQ_API_KEY}

# 联网搜索
SEARXNG_INSTANCE_URL=http://searxng:8080
SEARXNG_API_KEY=
FIRECRAWL_API_KEY=${FIRECRAWL_API_KEY}

CREDS_KEY=${CREDS_KEY}
CREDS_IV=${CREDS_IV}
JWT_SECRET=${JWT_SECRET}
JWT_REFRESH_SECRET=${JWT_REFRESH_SECRET}

ALLOW_EMAIL_LOGIN=true
ALLOW_REGISTRATION=true
ALLOW_SOCIAL_LOGIN=false

# 限制 Node 堆内存，避免吃光 1G
NODE_OPTIONS=--max-old-space-size=460
EOF
chmod 600 .env

info "写入 librechat.yaml ..."
{
cat <<'EOF'
version: 1.2.8
cache: true

interface:
  webSearch: true
  privacyPolicy: {}
  termsOfService: {}

endpoints:
  agents:
    disableBuilder: false
    capabilities: ["web_search", "tools", "actions"]
  custom:
    - name: "Groq"
      apiKey: "${GROQ_API_KEY}"
      baseURL: "https://api.groq.com/openai/v1"
      models:
        default:
          - "llama-3.3-70b-versatile"
          - "llama-3.1-8b-instant"
          - "openai/gpt-oss-120b"
          - "openai/gpt-oss-20b"
          - "qwen/qwen3-32b"
          - "moonshotai/kimi-k2-instruct"
        fetch: true          # 自动从 Groq 拉取当前可用模型列表
      titleConvo: true
      titleModel: "llama-3.1-8b-instant"
      modelDisplayLabel: "Groq"

webSearch:
  searchProvider: "searxng"
  searxngInstanceUrl: "${SEARXNG_INSTANCE_URL}"
  searxngApiKey: "${SEARXNG_API_KEY}"
  rerankerType: "none"
  safeSearch: 0
  scraperTimeout: 10000
EOF
if [[ -n $FIRECRAWL_API_KEY ]]; then
cat <<'EOF'
  scraperProvider: "firecrawl"
  firecrawlApiKey: "${FIRECRAWL_API_KEY}"
  firecrawlOptions:
    formats: ["markdown"]
    onlyMainContent: true
    timeout: 10000
EOF
fi
} > librechat.yaml

info "写入 SearXNG 配置 ..."
cat > searxng/settings.yml <<EOF
# 精简版 SearXNG 配置：只作为 LibreChat 的后端 API，不对公网暴露
use_default_settings: true

general:
  instance_name: "search"
  debug: false
  donation_url: false
  contact_url: false

server:
  secret_key: "${SEARXNG_SECRET}"
  limiter: false          # 关键：开了会拦截 LibreChat 的请求
  public_instance: false
  image_proxy: false      # 省 CPU/带宽
  method: "GET"

search:
  safe_search: 0
  autocomplete: ""        # 关闭自动补全，省请求
  formats:
    - html
    - json                # 关键：不开 json，LibreChat 拿不到结果 (403)

ui:
  static_use_hash: true

outgoing:
  request_timeout: 6.0
  max_request_timeout: 10.0
  pool_connections: 20
  pool_maxsize: 10
EOF

info "写入 docker-compose.yml ..."
{
cat <<'EOF'
services:
EOF

if [[ $DB_CHOICE == 1 ]]; then
cat <<'EOF'
  mongodb:
    image: mongo:7
    container_name: lc-mongo
    restart: unless-stopped
    # 限制 WiredTiger 缓存，默认会吃掉一半内存
    command: mongod --wiredTigerCacheSizeGB 0.25 --quiet
    volumes:
      - ./data/mongo:/data/db
    mem_limit: 400m
    networks: [lcnet]

EOF
fi

cat <<'EOF'
  searxng:
    image: searxng/searxng:latest
    container_name: lc-searxng
    restart: unless-stopped
    # 不映射端口到宿主机：仅供内网容器调用，避免被爬/被滥用
    volumes:
      - ./searxng:/etc/searxng:rw
    environment:
      - SEARXNG_BASE_URL=http://searxng:8080/
      - UWSGI_WORKERS=1
      - UWSGI_THREADS=2
    cap_drop: [ALL]
    cap_add: [CHOWN, SETGID, SETUID]
    mem_limit: 192m
    networks: [lcnet]

  api:
    image: ghcr.io/danny-avila/librechat:latest
    container_name: lc-api
    restart: unless-stopped
    env_file: .env
    volumes:
      - ./librechat.yaml:/app/librechat.yaml:ro
      - ./data/images:/app/client/public/images
      - ./data/uploads:/app/uploads
      - ./data/logs:/app/api/logs
    mem_limit: 700m
    networks: [lcnet]
EOF

if [[ $DB_CHOICE == 1 ]]; then
cat <<'EOF'
    depends_on: [mongodb, searxng]
EOF
else
cat <<'EOF'
    depends_on: [searxng]
EOF
fi

if [[ $USE_CADDY == 1 ]]; then
cat <<EOF
    expose: ["3080"]

  caddy:
    image: caddy:2-alpine
    container_name: lc-caddy
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - ./data/caddy:/data
      - ./data/caddy-config:/config
    mem_limit: 64m
    depends_on: [api]
    networks: [lcnet]
EOF
else
cat <<EOF
    ports: ["${HTTP_PORT}:3080"]
EOF
fi

cat <<'EOF'

networks:
  lcnet:
    driver: bridge
EOF
} > docker-compose.yml

if [[ $USE_CADDY == 1 ]]; then
cat > Caddyfile <<EOF
{
  email ${ACME_EMAIL}
}
${DOMAIN} {
  encode gzip
  reverse_proxy api:3080
}
EOF
fi

# 管理小工具
cat > /usr/local/bin/lc <<EOF
#!/usr/bin/env bash
cd ${INSTALL_DIR} || exit 1
case "\${1:-}" in
  up|start)   docker compose up -d ;;
  down|stop)  docker compose down ;;
  restart)    docker compose restart ;;
  logs)       docker compose logs -f --tail=100 \${2:-} ;;
  ps|status)  docker compose ps; echo; docker stats --no-stream ;;
  update)     docker compose pull && docker compose up -d && docker image prune -f ;;
  lockdown)   sed -i 's/^ALLOW_REGISTRATION=.*/ALLOW_REGISTRATION=false/' .env && docker compose up -d api && echo "已关闭注册" ;;
  *) echo "用法: lc {up|down|restart|logs [服务]|status|update|lockdown}" ;;
esac
EOF
chmod +x /usr/local/bin/lc

ok "配置文件生成完毕"

# --------------------------------------------------------------------------
# 5. 启动
# --------------------------------------------------------------------------
info "拉取镜像（1C 小鸡较慢，请耐心等待，约 1.5~2.5GB）..."
docker compose pull
info "启动容器..."
docker compose up -d

info "等待 LibreChat 就绪..."
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null "http://127.0.0.1:$([[ $USE_CADDY == 1 ]] && echo 3080 || echo "$HTTP_PORT")/health" 2>/dev/null \
     || docker compose logs api 2>/dev/null | grep -q "Server listening"; then
    ok "服务已启动"; break
  fi
  sleep 5; printf '.'
done
echo

# 防火墙
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  if [[ $USE_CADDY == 1 ]]; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  else ufw allow "${HTTP_PORT}/tcp" >/dev/null; fi
  ok "已放行防火墙端口"
fi

cat <<EOF

$(echo -e "${G}=================  部署完成  =================${N}")

  访问地址 : ${APP_URL}
  第一步   : 打开页面点 Sign up 注册（第一个账号就是你的）
  第二步   : ${Y}注册完立刻执行 'lc lockdown' 关闭注册${N}，否则公网上谁都能注册

  用法     : 左上角选 Endpoint = Groq 或 Agents
             想联网时点输入框下方的 ${B}地球/Web Search${N} 图标

  常用命令 :
    lc status      查看容器与实时资源占用
    lc logs api    看 LibreChat 日志
    lc restart     重启
    lc update      更新镜像
    lc lockdown    关闭新用户注册

  目录     : ${INSTALL_DIR}   (改完 .env 或 librechat.yaml 后 lc restart)

EOF

)

install_scraper() (
# =============================================================================
#  给 LibreChat 加一个轻量自建抓取器 (伪装成 Firecrawl API)
#  - 直接 fetch + Readability 抽正文 + 转 Markdown，无需 Chromium
#  - 内存约 60~120MB，1C1G 可用
#  - 抓不到正文时可回退到 Firecrawl 云端(可选)，省着用免费额度
#  - 通过 docker-compose.override.yml 注入，不修改原 compose 文件
# =============================================================================
set -euo pipefail

R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; B='\033[0;36m'; N='\033[0m'
info(){ echo -e "${B}[*]${N} $*"; }
ok(){   echo -e "${G}[✓]${N} $*"; }
warn(){ echo -e "${Y}[!]${N} $*"; }
die(){  echo -e "${R}[x]${N} $*" >&2; exit 1; }
ask(){ local __v=$1 __p=$2 __d=${3:-} __in
  if [[ -n $__d ]]; then read -rp "$(echo -e "${B}?${N} $__p [${__d}]: ")" __in; __in=${__in:-$__d}
  else read -rp "$(echo -e "${B}?${N} $__p: ")" __in; fi
  printf -v "$__v" '%s' "$__in"; }
yesno(){ local __in; read -rp "$(echo -e "${B}?${N} $1 (y/n) [${2:-y}]: ")" __in; __in=${__in:-${2:-y}}; [[ ${__in,,} == y* ]]; }

[[ $EUID -eq 0 ]] || die "请用 root 运行"

echo -e "${G}=====  轻量自建抓取器 安装向导  =====${N}\n"

ask INSTALL_DIR "LibreChat 安装目录" "/opt/librechat"
cd "$INSTALL_DIR" 2>/dev/null || die "目录不存在: $INSTALL_DIR"
[[ -f docker-compose.yml && -f .env && -f librechat.yaml ]] || die "这里不像 LibreChat 安装目录（缺 docker-compose.yml / .env / librechat.yaml）"

echo
echo "云端回退：本地抓不到正文时（比如纯 JS 渲染的站点），可以自动转给 Firecrawl 云端。"
echo "只有回退时才消耗额度，正常页面全部本地免费抓。留空则不回退（抓不到就直接报错）。"
ask CLOUD_KEY "Firecrawl 云端 API Key (fc-... , 可留空)" ""

echo
ask CACHE_TTL "本地缓存时长（分钟，同一网页重复抓直接走缓存）" "60"

echo
yesno "确认安装？" "y" || die "已取消"

# 内部共享令牌：LibreChat 和本地抓取器之间用，随便什么值都行，但不能为空
LOCAL_TOKEN="local-$(openssl rand -hex 8)"

# --------------------------------------------------------------------------
# 1. 抓取器源码
# --------------------------------------------------------------------------
info "生成抓取器源码..."
mkdir -p scraper

cat > scraper/package.json <<'EOF'
{
  "name": "lite-scraper",
  "version": "1.0.0",
  "private": true,
  "type": "module",
  "main": "server.js",
  "dependencies": {
    "@mozilla/readability": "^0.5.0",
    "jsdom": "^24.1.0",
    "turndown": "^7.2.0"
  }
}
EOF

cat > scraper/server.js <<'EOF'
/**
 * 轻量抓取器：对外暴露 Firecrawl v1 兼容的 /v1/scrape 接口
 * 流程: fetch HTML -> Readability 抽正文 -> Turndown 转 Markdown
 * 抓不到正文且配置了云端 Key 时，回退到 Firecrawl 云端
 */
import http from 'node:http';
import { JSDOM } from 'jsdom';
import { Readability } from '@mozilla/readability';
import TurndownService from 'turndown';

const PORT          = parseInt(process.env.PORT || '3002', 10);
const FETCH_TIMEOUT = parseInt(process.env.FETCH_TIMEOUT_MS || '12000', 10);
const MAX_BYTES     = parseInt(process.env.MAX_BYTES || '2500000', 10);   // 2.5MB 上限，防 OOM
const MAX_CONC      = parseInt(process.env.MAX_CONCURRENCY || '2', 10);   // 1核机器别开太高
const CACHE_TTL_MS  = parseInt(process.env.CACHE_TTL_MS || '3600000', 10);
const CACHE_MAX     = parseInt(process.env.CACHE_MAX || '150', 10);
const MIN_CHARS     = parseInt(process.env.MIN_CHARS || '200', 10);       // 少于这个字数视为抓取失败
const CLOUD_KEY     = process.env.CLOUD_FIRECRAWL_KEY || '';
const CLOUD_URL     = 'https://api.firecrawl.dev/v1/scrape';

const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
           '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const turndown = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced' });
turndown.remove(['script', 'style', 'noscript', 'iframe', 'form', 'nav', 'footer']);

// ---------- 简易 LRU 缓存 ----------
const cache = new Map();
const cacheGet = (k) => {
  const hit = cache.get(k);
  if (!hit) return null;
  if (Date.now() - hit.t > CACHE_TTL_MS) { cache.delete(k); return null; }
  cache.delete(k); cache.set(k, hit);            // 刷新 LRU 位置
  return hit.v;
};
const cacheSet = (k, v) => {
  cache.set(k, { v, t: Date.now() });
  while (cache.size > CACHE_MAX) cache.delete(cache.keys().next().value);
};

// ---------- 并发闸门 ----------
let running = 0;
const waiters = [];
const acquire = () => running < MAX_CONC
  ? (running++, Promise.resolve())
  : new Promise((r) => waiters.push(r)).then(() => { running++; });
const release = () => { running--; const w = waiters.shift(); if (w) w(); };

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------- 本地抓取 ----------
async function localScrape(url) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT);
  let res;
  try {
    res = await fetch(url, {
      signal: ctrl.signal,
      redirect: 'follow',
      headers: {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      },
    });
  } finally { clearTimeout(timer); }

  if (!res.ok) throw new Error(`upstream HTTP ${res.status}`);

  const ctype = (res.headers.get('content-type') || '').toLowerCase();
  if (ctype.includes('application/pdf')) throw new Error('PDF 不支持（交给云端回退）');
  if (!ctype.includes('html') && !ctype.includes('text/plain') && !ctype.includes('xml')) {
    throw new Error(`不支持的类型: ${ctype}`);
  }

  // 流式读取并卡上限，防止超大页面吃爆内存
  const reader = res.body.getReader();
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > MAX_BYTES) { try { await reader.cancel(); } catch {} break; }
    chunks.push(value);
  }
  const html = Buffer.concat(chunks).toString('utf8');

  const dom = new JSDOM(html, { url });
  const doc = dom.window.document;
  const title = doc.title || '';
  const desc  = doc.querySelector('meta[name="description"]')?.content || '';

  const article = new Readability(doc.cloneNode(true)).parse();
  let markdown = '';
  if (article?.content) {
    markdown = turndown.turndown(article.content);
  } else {
    // Readability 失败，退而求其次：拿 body 硬转
    const body = doc.body?.innerHTML || '';
    markdown = turndown.turndown(body);
  }
  markdown = markdown.replace(/\n{3,}/g, '\n\n').trim();
  dom.window.close();

  if (markdown.length < MIN_CHARS) throw new Error(`正文过短(${markdown.length}字符)，可能是JS渲染页面`);

  return {
    markdown,
    metadata: {
      title: article?.title || title,
      description: article?.excerpt || desc,
      sourceURL: url,
      statusCode: res.status,
    },
  };
}

// ---------- 云端回退 ----------
async function cloudScrape(url) {
  if (!CLOUD_KEY) throw new Error('未配置云端回退 Key');
  const res = await fetch(CLOUD_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${CLOUD_KEY}` },
    body: JSON.stringify({ url, formats: ['markdown'], onlyMainContent: true, maxAge: 86400000 }),
  });
  const j = await res.json();
  if (!res.ok || !j?.data?.markdown) throw new Error(`云端失败: HTTP ${res.status}`);
  return { markdown: j.data.markdown, metadata: j.data.metadata || { sourceURL: url } };
}

// ---------- 主逻辑 ----------
async function scrape(url) {
  const cached = cacheGet(url);
  if (cached) { log('CACHE ', url); return cached; }

  await acquire();
  try {
    let data, via = 'LOCAL ';
    try {
      data = await localScrape(url);
    } catch (e) {
      log('LOCAL-FAIL', url, '->', e.message);
      if (!CLOUD_KEY) throw e;
      data = await cloudScrape(url);
      via = 'CLOUD ';
    }
    log(via, url, `${data.markdown.length} chars`);
    cacheSet(url, data);
    return data;
  } finally { release(); }
}

// ---------- HTTP 服务 ----------
const readBody = (req) => new Promise((resolve, reject) => {
  let d = '';
  req.on('data', (c) => { d += c; if (d.length > 1e6) req.destroy(); });
  req.on('end', () => { try { resolve(d ? JSON.parse(d) : {}); } catch (e) { reject(e); } });
  req.on('error', reject);
});

const send = (res, code, obj) => {
  const b = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) });
  res.end(b);
};

http.createServer(async (req, res) => {
  const path = (req.url || '').split('?')[0];

  if (path === '/health') return send(res, 200, { ok: true, cache: cache.size, running });

  // 兼容 Firecrawl 的几种路径写法
  const isScrape = req.method === 'POST' &&
    ['/v1/scrape', '/v0/scrape', '/scrape'].includes(path.replace(/\/$/, ''));

  if (!isScrape) {
    log('UNHANDLED', req.method, path);   // 路径对不上时看这行日志排查
    return send(res, 404, { success: false, error: `no route: ${req.method} ${path}` });
  }

  try {
    const body = await readBody(req);
    const url = body.url;
    if (!url || !/^https?:\/\//i.test(url)) {
      return send(res, 400, { success: false, error: 'invalid url' });
    }
    const data = await scrape(url);
    // Firecrawl v1 响应结构
    send(res, 200, { success: true, data: { markdown: data.markdown, metadata: data.metadata } });
  } catch (e) {
    log('ERROR ', e.message);
    send(res, 500, { success: false, error: e.message });
  }
}).listen(PORT, '0.0.0.0', () => log(`lite-scraper listening on :${PORT} (cloud fallback: ${CLOUD_KEY ? 'ON' : 'OFF'})`));
EOF

cat > scraper/Dockerfile <<'EOF'
FROM node:22-alpine
WORKDIR /app
COPY package.json ./
RUN npm install --omit=dev --no-audit --no-fund && npm cache clean --force
COPY server.js ./
ENV NODE_OPTIONS=--max-old-space-size=160
EXPOSE 3002
CMD ["node", "server.js"]
EOF

ok "源码已生成 -> $INSTALL_DIR/scraper/"

# --------------------------------------------------------------------------
# 2. compose override
# --------------------------------------------------------------------------
info "写入 docker-compose.override.yml ..."
[[ -f docker-compose.override.yml ]] && cp docker-compose.override.yml "docker-compose.override.yml.bak.$(date +%s)"

cat > docker-compose.override.yml <<EOF
# 由 add-scraper.sh 生成。删掉本文件并 'docker compose up -d' 即可完全回滚。
services:
  scraper:
    build: ./scraper
    container_name: lc-scraper
    restart: unless-stopped
    environment:
      - PORT=3002
      - CLOUD_FIRECRAWL_KEY=${CLOUD_KEY}
      - CACHE_TTL_MS=$(( CACHE_TTL * 60 * 1000 ))
      - MAX_CONCURRENCY=2
      - FETCH_TIMEOUT_MS=12000
    mem_limit: 256m
    networks: [lcnet]

  api:
    depends_on:
      - scraper
EOF
ok "override 已写入"

# --------------------------------------------------------------------------
# 3. 改 .env
# --------------------------------------------------------------------------
info "更新 .env ..."
cp .env ".env.bak.$(date +%s)"
set_env() {
  local k=$1 v=$2
  if grep -q "^${k}=" .env; then
    sed -i "s|^${k}=.*|${k}=${v}|" .env
  else
    echo "${k}=${v}" >> .env
  fi
}
# 指向本地抓取器；KEY 只是个占位符，但不能为空，否则 LibreChat 认为没配抓取器
set_env FIRECRAWL_API_URL "http://scraper:3002"
set_env FIRECRAWL_API_KEY "$LOCAL_TOKEN"
set_env FIRECRAWL_VERSION "v1"
ok ".env 已更新 (FIRECRAWL_API_URL=http://scraper:3002)"

# --------------------------------------------------------------------------
# 4. 改 librechat.yaml
# --------------------------------------------------------------------------
info "更新 librechat.yaml ..."
cp librechat.yaml "librechat.yaml.bak.$(date +%s)"

grep -q '^webSearch:' librechat.yaml || die "librechat.yaml 里找不到 webSearch: 段，请手动配置"

if grep -q 'scraperProvider' librechat.yaml; then
  sed -i 's|^\( *\)scraperProvider:.*|\1scraperProvider: "firecrawl"|' librechat.yaml
  grep -q 'firecrawlApiUrl' librechat.yaml || \
    sed -i '/scraperProvider:/a\  firecrawlApiUrl: "${FIRECRAWL_API_URL}"' librechat.yaml
  grep -q 'firecrawlApiKey' librechat.yaml || \
    sed -i '/scraperProvider:/a\  firecrawlApiKey: "${FIRECRAWL_API_KEY}"' librechat.yaml
else
  # webSearch 段在文件末尾（本脚本生成的布局），直接追加
  cat >> librechat.yaml <<'EOF'
  scraperProvider: "firecrawl"
  firecrawlApiKey: "${FIRECRAWL_API_KEY}"
  firecrawlApiUrl: "${FIRECRAWL_API_URL}"
  firecrawlOptions:
    formats: ["markdown"]
    onlyMainContent: true
    maxAge: 86400000
    timeout: 12000
EOF
fi
ok "librechat.yaml 已更新"
echo -e "${Y}--- 当前 webSearch 配置 ---${N}"
sed -n '/^webSearch:/,$p' librechat.yaml
echo -e "${Y}--------------------------${N}"

# --------------------------------------------------------------------------
# 5. 构建 & 启动
# --------------------------------------------------------------------------
info "构建抓取器镜像（1核机器约 2~4 分钟）..."
docker compose build scraper
info "启动..."
docker compose up -d

info "等待抓取器就绪..."
for i in $(seq 1 30); do
  if docker compose exec -T scraper wget -qO- http://127.0.0.1:3002/health >/dev/null 2>&1; then
    ok "抓取器已就绪"; break
  fi
  sleep 2; printf '.'
done
echo

# --------------------------------------------------------------------------
# 6. 自测
# --------------------------------------------------------------------------
info "抓一个真实页面测试..."
TEST=$(docker compose exec -T scraper node -e '
fetch("http://127.0.0.1:3002/v1/scrape",{method:"POST",headers:{"Content-Type":"application/json"},
 body:JSON.stringify({url:"https://en.wikipedia.org/wiki/Web_scraping"})})
 .then(r=>r.json()).then(j=>{
   if(j.success) console.log("OK|"+j.data.markdown.length+"|"+(j.data.metadata.title||""));
   else console.log("FAIL|"+j.error);
 }).catch(e=>console.log("FAIL|"+e.message));' 2>/dev/null || echo "FAIL|exec error")

if [[ $TEST == OK* ]]; then
  ok "自测通过：抓到 $(echo "$TEST" | cut -d'|' -f2) 字符 —— 《$(echo "$TEST" | cut -d'|' -f3)》"
else
  warn "自测未通过：$TEST"
  warn "看日志排查: docker compose logs scraper"
fi

cat <<EOF

$(echo -e "${G}=================  完成  =================${N}")

  现在 LibreChat 的抓取请求会打到本地 lc-scraper，${G}不再消耗 Firecrawl 额度${N}。
  $([[ -n $CLOUD_KEY ]] && echo "本地抓不到正文时（JS 渲染页面）会自动回退到云端。" || echo "未配置云端回退：JS 渲染的页面会抓取失败，这是预期行为。")

  ${Y}重要：如果你之前在网页弹窗里手填过 Firecrawl Key，那份用户级配置会覆盖服务端配置。${N}
  ${Y}请打开 LibreChat -> 点地球图标 -> 把 API URL 和 API 密钥两栏清空 -> 保存。${N}

  看抓取器实时日志（能看到每次抓取走的是 LOCAL / CLOUD / CACHE）：
    cd $INSTALL_DIR && docker compose logs -f scraper

  日志含义：
    LOCAL      本地抓取成功，免费
    CACHE      命中缓存，免费且瞬时
    CLOUD      本地失败，走了云端，${Y}消耗 1 credit${N}
    LOCAL-FAIL 本地失败原因（后面跟着 CLOUD 或 ERROR）

  完全回滚：
    cd $INSTALL_DIR && rm docker-compose.override.yml && docker compose up -d
    （再把 .env 里的 FIRECRAWL_API_URL 删掉、FIRECRAWL_API_KEY 换回云端 key）

EOF

)

uninstall_librechat() (
# =============================================================================
#  LibreChat + SearXNG 卸载脚本
#  分层清理：容器/镜像/数据 -> Docker 本体 -> swap -> 辅助命令
#  每一步单独确认，可以只删一部分
# =============================================================================
set -uo pipefail   # 注意：不用 -e，卸载过程中某步失败不应中断后续清理

R='\033[0;31m'; G='\033[0;32m'; Y='\033[1;33m'; B='\033[0;36m'; N='\033[0m'
info(){ echo -e "${B}[*]${N} $*"; }
ok(){   echo -e "${G}[✓]${N} $*"; }
warn(){ echo -e "${Y}[!]${N} $*"; }
skip(){ echo -e "    ${B}跳过${N} $*"; }
ask(){ local __v=$1 __p=$2 __d=${3:-} __in
  if [[ -n $__d ]]; then read -rp "$(echo -e "${B}?${N} $__p [${__d}]: ")" __in; __in=${__in:-$__d}
  else read -rp "$(echo -e "${B}?${N} $__p: ")" __in; fi
  printf -v "$__v" '%s' "$__in"; }
yesno(){ local __in; read -rp "$(echo -e "${B}?${N} $1 (y/n) [${2:-n}]: ")" __in; __in=${__in:-${2:-n}}; [[ ${__in,,} == y* ]]; }

[[ $EUID -eq 0 ]] || { echo "请用 root 运行"; exit 1; }

echo -e "${R}=====  LibreChat 卸载向导  =====${N}"
echo "每一步都会单独问你，可以只删一部分。\n"

ask INSTALL_DIR "安装目录" "/opt/librechat"

# 显示当前占用情况，方便决策
if command -v docker >/dev/null 2>&1; then
  echo -e "\n${Y}--- 当前 Docker 占用 ---${N}"
  docker system df 2>/dev/null || true
  echo -e "${Y}-----------------------${N}\n"
fi

# --------------------------------------------------------------------------
# 1. 停止并删除容器 / 网络 / 卷
# --------------------------------------------------------------------------
if [[ -d $INSTALL_DIR && -f $INSTALL_DIR/docker-compose.yml ]]; then
  if yesno "停止并删除所有相关容器？" "y"; then
    info "停止容器..."
    ( cd "$INSTALL_DIR" && docker compose down -v --remove-orphans ) 2>/dev/null \
      && ok "容器已删除" || warn "compose down 失败，稍后用兜底方式清理"
  else
    skip "容器保留"
  fi
else
  warn "$INSTALL_DIR 下没找到 docker-compose.yml，改用容器名兜底清理"
fi

# 兜底：按容器名清理（compose 文件丢了/损坏时）
if command -v docker >/dev/null 2>&1; then
  LEFT=$(docker ps -aq --filter "name=^lc-" 2>/dev/null)
  if [[ -n $LEFT ]]; then
    if yesno "还有残留的 lc-* 容器，强制删除？" "y"; then
      docker rm -f $LEFT >/dev/null 2>&1 && ok "残留容器已清除"
    fi
  fi
fi

# --------------------------------------------------------------------------
# 2. 删除镜像（这是占硬盘的大头）
# --------------------------------------------------------------------------
if command -v docker >/dev/null 2>&1; then
  echo
  if yesno "删除相关 Docker 镜像？(librechat / mongo / searxng / caddy / scraper，约 2GB)" "y"; then
    info "删除镜像..."
    for img in \
      "ghcr.io/danny-avila/librechat" \
      "mongo" \
      "searxng/searxng" \
      "caddy" \
      "librechat-scraper" \
      "librechat_scraper" ; do
      IDS=$(docker images -q "$img" 2>/dev/null | sort -u)
      [[ -n $IDS ]] && docker rmi -f $IDS >/dev/null 2>&1 && echo "    removed: $img"
    done
    # 抓取器是本地 build 的，镜像名可能带项目名前缀，兜底扫一遍
    EXTRA=$(docker images --format '{{.Repository}}:{{.ID}}' 2>/dev/null | grep -i 'scraper' | cut -d: -f2 | sort -u)
    [[ -n $EXTRA ]] && docker rmi -f $EXTRA >/dev/null 2>&1
    ok "镜像已删除"

    info "清理构建缓存和悬空层..."
    docker builder prune -af >/dev/null 2>&1
    docker image prune -af >/dev/null 2>&1
    ok "缓存已清理"
  else
    skip "镜像保留"
  fi
fi

# --------------------------------------------------------------------------
# 3. 删除数据目录
# --------------------------------------------------------------------------
echo
if [[ -d $INSTALL_DIR ]]; then
  SIZE=$(du -sh "$INSTALL_DIR" 2>/dev/null | cut -f1)
  # 判断是否用了 Atlas 云数据库
  USED_ATLAS=0
  grep -q '^MONGO_URI=mongodb+srv' "$INSTALL_DIR/.env" 2>/dev/null && USED_ATLAS=1

  warn "即将删除 $INSTALL_DIR (${SIZE:-未知})，${R}包含全部聊天记录、账号、API Key，不可恢复${N}"
  if yesno "确认删除？" "y"; then
    rm -rf "$INSTALL_DIR"
    ok "$INSTALL_DIR 已删除"
    if (( USED_ATLAS )); then
      echo
      warn "检测到你用的是 MongoDB Atlas 云数据库。"
      warn "本地已删干净，但${R}聊天记录还在云上${N}——记得去 https://cloud.mongodb.com 手动删掉那个集群。"
    fi
  else
    skip "数据目录保留"
  fi
else
  skip "$INSTALL_DIR 不存在"
fi

# --------------------------------------------------------------------------
# 4. 卸载 Docker 本体
# --------------------------------------------------------------------------
echo
if command -v docker >/dev/null 2>&1; then
  OTHER=$(docker ps -aq 2>/dev/null | wc -l)
  if (( OTHER > 0 )); then
    warn "系统里还有 ${OTHER} 个其他容器，卸载 Docker 会一并干掉它们。"
  fi
  if yesno "彻底卸载 Docker？(如果这台机器还跑别的容器就选 n)" "n"; then
    info "卸载 Docker..."
    systemctl stop docker docker.socket containerd 2>/dev/null
    systemctl disable docker docker.socket containerd 2>/dev/null
    export DEBIAN_FRONTEND=noninteractive
    apt-get -qq purge -y docker-ce docker-ce-cli containerd.io \
      docker-buildx-plugin docker-compose-plugin docker-ce-rootless-extras >/dev/null 2>&1
    apt-get -qq autoremove -y >/dev/null 2>&1
    rm -rf /var/lib/docker /var/lib/containerd /etc/docker
    rm -f /etc/apt/sources.list.d/docker.list /etc/apt/keyrings/docker.asc /etc/apt/keyrings/docker.gpg
    groupdel docker 2>/dev/null
    ok "Docker 已卸载"
  else
    skip "Docker 保留"
  fi
fi

# --------------------------------------------------------------------------
# 5. 移除 swap
# --------------------------------------------------------------------------
echo
if [[ -f /swapfile ]]; then
  warn "移除 swap 后可用内存会更紧张。如果这台机器还要跑别的东西，建议留着。"
  if yesno "移除安装时创建的 /swapfile (2GB)？" "n"; then
    swapoff /swapfile 2>/dev/null
    rm -f /swapfile
    sed -i '\|^/swapfile|d' /etc/fstab
    sed -i '/^vm.swappiness=20$/d' /etc/sysctl.conf
    ok "swap 已移除（腾出 2GB 硬盘）"
  else
    skip "swap 保留"
  fi
fi

# --------------------------------------------------------------------------
# 6. 辅助命令 & 防火墙
# --------------------------------------------------------------------------
if [[ -f /usr/local/bin/lc ]]; then
  rm -f /usr/local/bin/lc && ok "已移除 lc 命令"
fi

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  echo
  if yesno "撤销安装时放行的防火墙端口 (80/443/3080)？" "y"; then
    for p in 80 443 3080; do ufw delete allow ${p}/tcp >/dev/null 2>&1; done
    ok "防火墙规则已撤销"
  fi
fi

# --------------------------------------------------------------------------
# 收尾
# --------------------------------------------------------------------------
echo
echo -e "${G}=================  清理完成  =================${N}"
echo
echo "当前磁盘："
df -h / | tail -1 | awk '{print "    根分区: 已用 "$3" / 共 "$2"，可用 "$4}'
echo "当前内存："
free -m | awk '/^Mem:/{print "    内存: 已用 "$3"MB / 共 "$2"MB"}'
free -m | awk '/^Swap:/{print "    Swap: "$2"MB"}'
echo
echo -e "${Y}别忘了：${N}"
echo "  - 去 Groq 后台吊销那个 API Key（既然不用了，防止泄露被盗刷）"
echo "  - 如果注册过 Firecrawl，免费额度不用管，放着就行"
echo "  - 如果用的是 MongoDB Atlas，记得去云上删集群"
echo

)

show_summary() {
  cat <<'EOF'

功能说明
  1. 基础部署
     为 Debian/Ubuntu 的低配 VPS 部署 LibreChat、SearXNG，并可选本地
     MongoDB 或 MongoDB Atlas、Caddy HTTPS、Firecrawl 云端抓取和 2GB swap。
     同时生成 lc 管理命令。

  2. 轻量抓取器
     给已经存在的 LibreChat 安装一个 Firecrawl v1 兼容的本地抓取服务。
     使用 fetch + Readability + Turndown 抽取正文，带并发限制、大小限制、
     LRU 缓存，并可在本地抓取失败时回退到 Firecrawl 云端。

  3. 分层卸载
     可分别删除相关容器/卷、镜像和构建缓存、安装目录、Docker、swap、
     lc 命令及相关防火墙规则。危险操作会逐项确认。
EOF
}

[[ ${EUID:-$(id -u)} -eq 0 ]] || menu_die "请用 root 运行：sudo bash $0"

while true; do
  echo -e "${G}===== LibreChat 一体化管理 =====${N}"
  echo "  1) 安装 LibreChat + SearXNG"
  echo "  2) 安装 / 更新轻量本地抓取器"
  echo "  3) 卸载 / 清理 LibreChat"
  echo "  4) 查看功能说明"
  echo "  0) 退出"
  read -rp "$(echo -e "${B}?${N} 请选择 [1]: ")" choice
  choice=${choice:-1}
  echo

  case "$choice" in
    1) install_librechat; break ;;
    2) install_scraper; break ;;
    3) uninstall_librechat; break ;;
    4) show_summary; echo ;;
    0|q|Q) exit 0 ;;
    *) echo -e "${Y}[!]${N} 无效选择：$choice"; echo ;;
  esac
done


