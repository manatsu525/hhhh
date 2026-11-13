# PocketShell — 安卓 SSH 终端

手机上的 SSH 终端（类似 Windows 上的 Xshell），外加从 GitHub 仓库一键运行脚本。

## 功能

- **服务器配置**：保存名称 / 主机 / 端口 / 用户名，支持**密码**和**密钥**（RSA、ECDSA、Ed25519，可带口令）登录。首页点一下直接登录；长按可编辑、复制、删除、断开。
- **终端**：xterm-256color，vim / htop / tmux 等全屏程序可用；手指上下滑动翻历史，双指缩放字号。
- **特殊按键**（终端下方，常驻）：
  - `ESC` `TAB` `HOME` `END` `PGUP` `PGDN` `↑ ↓ ← →`，按住连发
  - `CTRL` `SHIFT` `META`（即 Alt）：点一下只对下一个键生效，再点一下锁定，再点关闭
  - 顶上一排可左右滑动：`F1`–`F12`、`INS`、`DEL` 和常用符号
- **快捷指令**：保存常用命令，终端里点"指令"一点即发（可选是否自动回车）。
- **GitHub 脚本**：填一次个人访问令牌（PAT，只需仓库内容的只读权限）并保存，之后可浏览自己的仓库，点脚本文件 → 预览 → 运行。可以收藏常用脚本，下次直接点。
  - 脚本通过当前 SSH 连接传到服务器的临时文件（权限 700）再执行，默认运行完自动删除；令牌不会发到服务器上。
- 粘贴、复制终端文本、中文输入模式、断线后回车重连、后台保活。

## 卸载不留痕

- 不申请任何存储权限，不向公共目录（下载、SD 卡等）写任何文件。
- 所有数据（服务器配置、密码、私钥、快捷指令、GitHub 令牌、known_hosts）都在应用私有目录，并用 Android Keystore 密钥加密；卸载时系统会把私有目录和密钥一并删除。
- 关闭了云备份和换机迁移，重装后不会从云端恢复旧数据。
- 导入私钥文件用的是系统文件选择器，只读你选中的那个文件，应用里保存的是一份加密副本。

## 构建

```bash
./gradlew assembleRelease
```

产物在 `app/build/outputs/apk/release/`。需要 JDK 17+ 和 Android SDK（platform 35）。

默认用 debug 密钥签名。要用固定密钥签名（这样新版本可以直接覆盖安装），设置环境变量
`KEYSTORE_FILE`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
仓库里的 GitHub Actions（`PocketShell APK`）在该目录有改动时自动构建，APK 在运行记录的 Artifacts 里下载；
配置仓库 Secrets `POCKETSHELL_KEYSTORE_BASE64`（密钥库的 base64）和 `POCKETSHELL_KEYSTORE_PASSWORD` 后会用固定密钥签名。

## 测试

```bash
./gradlew testDebugUnitTest
```

界面冒烟测试随时可跑；SSH 端到端测试需要一台测试 sshd，环境变量见 `app/src/test/.../SmokeTest.kt` 顶部说明。

## 用到的开源库

- [Termux terminal-emulator / terminal-view](https://github.com/termux/termux-app)（终端模拟与绘制）
- [mwiede/jsch](https://github.com/mwiede/jsch)（SSH）
- [Bouncy Castle](https://www.bouncycastle.org/)（Ed25519 / Curve25519）
