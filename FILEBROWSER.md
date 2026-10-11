# File Browser 管理脚本

`filebrowser-manager.sh` 是一个交互式脚本，用于在 Debian / Ubuntu VPS 上一键安装、更新或卸载 [File Browser](https://github.com/filebrowser/filebrowser)（网页文件管理器）。

- 以独立的低权限系统用户 `filebrowser` 运行，并由 systemd 托管、开机自启
- 共享目录默认为 `/home/share`，**卸载时会永久保留该目录及其中所有文件**
- 重复运行安装即为更新 / 修复：不会清空已有数据库，只更新配置和管理员密码

## 系统要求

- Debian / Ubuntu（需要 `apt-get`）
- systemd
- root 权限
- 能访问 GitHub（下载官方安装脚本 `filebrowser/get`）

## 快速使用

```bash
wget -O filebrowser-manager.sh https://raw.githubusercontent.com/manatsu525/hhhh/main/filebrowser-manager.sh
chmod +x filebrowser-manager.sh
sudo ./filebrowser-manager.sh
```

或者一行命令：

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/manatsu525/hhhh/main/filebrowser-manager.sh)
```

运行后会显示菜单：

```
1. 一键安装 / 更新 File Browser
2. 一键删除 File Browser（保留 /home/share）
0. 退出
```

### 1. 安装 / 更新

1. 提示设置管理员密码（至少 6 位，需输入两次确认）；直接回车则使用默认密码 `sumire`
2. 安装依赖（`ca-certificates`、`curl`、`tar`），并通过官方脚本安装 / 更新 `filebrowser`
3. 创建系统用户 `filebrowser`、数据目录和共享目录
4. 初始化数据库（已存在则只更新配置与管理员账号）
5. 写入 systemd 服务 `/etc/systemd/system/filebrowser.service` 并启动
6. 若 UFW 已启用，自动放行对应 TCP 端口
7. 检查服务状态，启动失败时输出最近日志

完成后访问 `http://你的VPS公网IP:8080`，用户名 `admin`。登录信息同时保存在 `/root/filebrowser-credentials.txt`（权限 600）。

> 使用默认密码时，建议登录后立即修改，或重新运行脚本设置新密码。
> 如果云服务商有安全组 / 防火墙，需要自行放行端口。

### 2. 卸载

输入 `y` 确认后会删除：

- systemd 服务
- `filebrowser` 可执行文件
- 数据库与配置（`/var/lib/filebrowser`）及 `/root/filebrowser-credentials.txt`
- `filebrowser` 系统用户

共享目录**不会被删除**，其所有权会交还给 `root:root`。

## 自定义配置（环境变量）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `FILEBROWSER_SHARE_DIR` | `/home/share` | 共享（根）目录 |
| `FILEBROWSER_DATA_DIR` | `/var/lib/filebrowser` | 数据库与数据目录 |
| `FILEBROWSER_PORT` | `8080` | 监听端口 |
| `FILEBROWSER_ADDRESS` | `0.0.0.0` | 监听地址 |
| `FILEBROWSER_TOKEN_EXPIRE` | `8760h` | 登录有效期（约一年） |
| `FILEBROWSER_USERNAME` | `admin` | 管理员用户名 |

示例：使用 9000 端口，共享 `/data/share`：

```bash
sudo FILEBROWSER_PORT=9000 FILEBROWSER_SHARE_DIR=/data/share ./filebrowser-manager.sh
```

> 卸载时请使用与安装时相同的环境变量，否则脚本找不到自定义的数据目录。

## 常用命令

```bash
systemctl status filebrowser      # 查看状态
systemctl restart filebrowser     # 重启
journalctl -u filebrowser -f      # 查看实时日志
```
