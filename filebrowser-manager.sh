#!/usr/bin/env bash
# File Browser 交互管理脚本 v2.0
# 功能：一键安装/更新；一键彻底移除 File Browser，但永久保留共享目录 /home/share

set -Eeuo pipefail
trap 'echo "[错误] 第 ${LINENO} 行执行失败：${BASH_COMMAND}" >&2' ERR

SCRIPT_VERSION="2.0"

# ======================== 默认设置 ========================
SHARE_DIR="${FILEBROWSER_SHARE_DIR:-/home/share}"
DATA_DIR="${FILEBROWSER_DATA_DIR:-/var/lib/filebrowser}"
DB_PATH="${DATA_DIR}/filebrowser.db"
SERVICE_USER="filebrowser"
SERVICE_NAME="filebrowser"
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
PORT="${FILEBROWSER_PORT:-8080}"
ADDRESS="${FILEBROWSER_ADDRESS:-0.0.0.0}"
TOKEN_EXPIRE="${FILEBROWSER_TOKEN_EXPIRE:-8760h}"
ADMIN_USER="${FILEBROWSER_USERNAME:-admin}"
DEFAULT_PASSWORD="sumire"
MIN_PASSWORD_LENGTH=6
CREDENTIAL_FILE="/root/filebrowser-credentials.txt"
INSTALLER_URL="https://raw.githubusercontent.com/filebrowser/get/master/get.sh"
# =========================================================

require_root() {
  if [[ ${EUID} -ne 0 ]]; then
    echo "请使用 root 用户运行此脚本。" >&2
    exit 1
  fi
}

check_system() {
  if ! command -v apt-get >/dev/null 2>&1; then
    echo "此脚本仅适用于 Debian/Ubuntu 系统。" >&2
    exit 1
  fi

  if ! command -v systemctl >/dev/null 2>&1; then
    echo "当前系统没有 systemd，无法管理 File Browser 服务。" >&2
    exit 1
  fi

  if ! [[ "${PORT}" =~ ^[0-9]+$ ]] || (( PORT < 1 || PORT > 65535 )); then
    echo "端口无效：${PORT}" >&2
    exit 1
  fi
}

read_admin_password() {
  local first=""
  local second=""

  while true; do
    read -r -s -p "设置管理员密码（直接回车默认：${DEFAULT_PASSWORD}）：" first
    echo

    if [[ -z "${first}" ]]; then
      ADMIN_PASSWORD="${DEFAULT_PASSWORD}"
      echo "已使用默认密码：${DEFAULT_PASSWORD}"
      return 0
    fi

    if (( ${#first} < MIN_PASSWORD_LENGTH )); then
      echo "密码至少需要 ${MIN_PASSWORD_LENGTH} 位，请重新输入。"
      continue
    fi

    read -r -s -p "请再次输入密码确认：" second
    echo

    if [[ "${first}" != "${second}" ]]; then
      echo "两次输入不一致，请重新输入。"
      continue
    fi

    ADMIN_PASSWORD="${first}"
    return 0
  done
}

prepare_share_directory() {
  if [[ -e "${SHARE_DIR}" && ! -d "${SHARE_DIR}" ]]; then
    echo "发现 ${SHARE_DIR} 已存在，但它不是目录。" >&2
    echo "请先把这个同名文件移走，再重新安装。" >&2
    exit 1
  fi

  if [[ -d "${SHARE_DIR}" ]]; then
    echo "检测到共享目录已存在：${SHARE_DIR}"
    echo "不会重复创建，也不会清空其中的任何文件。"
  else
    echo "创建共享目录：${SHARE_DIR}"
    mkdir -p "${SHARE_DIR}"
  fi

  # File Browser 服务使用独立低权限账号运行，因此需要取得共享目录读写权。
  chown -R "${SERVICE_USER}:${SERVICE_USER}" "${SHARE_DIR}"
  chmod 0750 "${SHARE_DIR}"
}

install_filebrowser() {
  check_system
  read_admin_password

  echo
  echo "[1/7] 安装基础依赖……"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -y
  apt-get install -y --no-install-recommends ca-certificates curl tar

  echo "[2/7] 安装或更新 File Browser……"
  local installer_file
  installer_file="$(mktemp)"
  curl -fsSL "${INSTALLER_URL}" -o "${installer_file}"
  bash "${installer_file}"
  rm -f "${installer_file}"

  local fb_bin
  fb_bin="$(command -v filebrowser || true)"
  if [[ -z "${fb_bin}" || ! -x "${fb_bin}" ]]; then
    echo "安装失败：没有找到 filebrowser 可执行文件。" >&2
    exit 1
  fi

  echo "[3/7] 准备服务账号与目录……"
  if id "${SERVICE_USER}" >/dev/null 2>&1; then
    echo "系统用户 ${SERVICE_USER} 已存在，直接复用。"
  else
    useradd \
      --system \
      --home-dir "${DATA_DIR}" \
      --shell /usr/sbin/nologin \
      "${SERVICE_USER}"
  fi

  install -d -m 0750 -o "${SERVICE_USER}" -g "${SERVICE_USER}" "${DATA_DIR}"
  prepare_share_directory

  # 修改数据库前先停服务，避免数据库占用。
  systemctl stop "${SERVICE_NAME}" >/dev/null 2>&1 || true

  echo "[4/7] 初始化或更新配置……"
  local install_mode="新安装"

  if [[ ! -s "${DB_PATH}" ]]; then
    "${fb_bin}" config init \
      --database "${DB_PATH}" \
      --address "${ADDRESS}" \
      --port "${PORT}" \
      --root "${SHARE_DIR}" \
      --auth.method json \
      --minimumPasswordLength "${MIN_PASSWORD_LENGTH}" \
      --tokenExpirationTime "${TOKEN_EXPIRE}" \
      --disableExec

    "${fb_bin}" users add "${ADMIN_USER}" "${ADMIN_PASSWORD}" \
      --database "${DB_PATH}" \
      --perm.admin
  else
    install_mode="更新/修复"
    echo "检测到已有数据库：${DB_PATH}"
    echo "不会重复初始化数据库；将更新配置和管理员密码。"

    "${fb_bin}" config set \
      --database "${DB_PATH}" \
      --address "${ADDRESS}" \
      --port "${PORT}" \
      --root "${SHARE_DIR}" \
      --auth.method json \
      --minimumPasswordLength "${MIN_PASSWORD_LENGTH}" \
      --tokenExpirationTime "${TOKEN_EXPIRE}" \
      --disableExec

    if "${fb_bin}" users find "${ADMIN_USER}" --database "${DB_PATH}" >/dev/null 2>&1; then
      "${fb_bin}" users update "${ADMIN_USER}" \
        --database "${DB_PATH}" \
        --password "${ADMIN_PASSWORD}" \
        --perm.admin
    else
      "${fb_bin}" users add "${ADMIN_USER}" "${ADMIN_PASSWORD}" \
        --database "${DB_PATH}" \
        --perm.admin
    fi
  fi

  chown -R "${SERVICE_USER}:${SERVICE_USER}" "${DATA_DIR}"

  echo "[5/7] 写入 systemd 服务……"
  cat > "${SERVICE_FILE}" <<SERVICE
[Unit]
Description=File Browser
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_USER}
WorkingDirectory=${DATA_DIR}
Environment=HOME=${DATA_DIR}
ExecStart=${fb_bin} --database=${DB_PATH}
Restart=on-failure
RestartSec=3
UMask=0027
NoNewPrivileges=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
SERVICE

  systemctl daemon-reload
  systemctl enable --now "${SERVICE_NAME}"

  echo "[6/7] 检查防火墙……"
  if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q '^Status: active'; then
    ufw allow "${PORT}/tcp" >/dev/null
    echo "已在 UFW 放行 TCP ${PORT}。"
  else
    echo "UFW 未启用，跳过自动放行。"
  fi

  echo "[7/7] 检查运行状态……"
  if ! systemctl is-active --quiet "${SERVICE_NAME}"; then
    echo "File Browser 启动失败，最近日志如下：" >&2
    journalctl -u "${SERVICE_NAME}" -n 60 --no-pager >&2
    exit 1
  fi

  umask 077
  cat > "${CREDENTIAL_FILE}" <<CREDS
File Browser 登录信息

访问地址：http://你的VPS公网IP:${PORT}
用户名：${ADMIN_USER}
密码：${ADMIN_PASSWORD}
共享目录：${SHARE_DIR}
登录有效期：${TOKEN_EXPIRE}
CREDS
  chmod 600 "${CREDENTIAL_FILE}"

  echo
  echo "============================================================"
  echo "File Browser ${install_mode}完成"
  echo "访问地址：http://你的VPS公网IP:${PORT}"
  echo "用户名：${ADMIN_USER}"
  echo "密码：${ADMIN_PASSWORD}"
  echo "共享目录：${SHARE_DIR}"
  echo "登录有效期：${TOKEN_EXPIRE}（约一年）"
  echo "登录信息：${CREDENTIAL_FILE}"
  echo "============================================================"
}

uninstall_filebrowser() {
  check_system

  echo
  echo "即将删除以下内容："
  echo "  - File Browser systemd 服务"
  echo "  - File Browser 可执行程序"
  echo "  - File Browser 数据库和配置"
  echo "  - filebrowser 专用系统用户"
  echo
  echo "不会删除共享目录：${SHARE_DIR}"
  echo "不会删除共享目录中的任何文件。"
  echo

  local confirm=""
  read -r -p "确认删除？输入 y 继续 [y/N]：" confirm
  if [[ ! "${confirm}" =~ ^[Yy]$ ]]; then
    echo "已取消。"
    return 0
  fi

  echo "[1/5] 停止并删除 systemd 服务……"
  systemctl disable --now "${SERVICE_NAME}" >/dev/null 2>&1 || true
  rm -f "${SERVICE_FILE}"
  systemctl daemon-reload
  systemctl reset-failed "${SERVICE_NAME}" >/dev/null 2>&1 || true

  echo "[2/5] 删除 File Browser 可执行程序……"
  local current_bin
  current_bin="$(command -v filebrowser 2>/dev/null || true)"

  local candidate
  declare -A seen=()
  for candidate in "${current_bin}" /usr/local/bin/filebrowser /usr/bin/filebrowser; do
    [[ -z "${candidate}" ]] && continue
    [[ -n "${seen[${candidate}]:-}" ]] && continue
    seen["${candidate}"]=1

    if [[ -f "${candidate}" ]]; then
      rm -f "${candidate}"
      echo "已删除：${candidate}"
    fi
  done

  echo "[3/5] 删除数据库、配置和登录信息……"
  rm -rf "${DATA_DIR}"
  rm -f "${CREDENTIAL_FILE}"

  echo "[4/5] 处理共享目录并删除专用账号……"
  if [[ -d "${SHARE_DIR}" ]]; then
    # 专用账号删除后，将保留文件交还给 root，避免出现无人所属的 UID。
    chown -R root:root "${SHARE_DIR}"
    echo "共享目录已保留：${SHARE_DIR}"
  fi

  if id "${SERVICE_USER}" >/dev/null 2>&1; then
    userdel "${SERVICE_USER}" >/dev/null 2>&1 || true
  fi

  echo "[5/5] 完成清理……"
  echo
  echo "============================================================"
  echo "File Browser 已删除。"
  echo "共享目录及其全部文件仍然保留在：${SHARE_DIR}"
  echo "============================================================"
}

show_menu() {
  clear 2>/dev/null || true
  echo "============================================================"
  echo "       File Browser 交互管理脚本 v${SCRIPT_VERSION}"
  echo "============================================================"
  echo "1. 一键安装 / 更新 File Browser"
  echo "2. 一键删除 File Browser（保留 /home/share）"
  echo "0. 退出"
  echo "============================================================"

  local choice=""
  read -r -p "请选择 [0-2]：" choice

  case "${choice}" in
    1) install_filebrowser ;;
    2) uninstall_filebrowser ;;
    0) echo "已退出。" ;;
    *) echo "无效选项：${choice}" >&2; exit 1 ;;
  esac
}

require_root
show_menu
