# Anime Stack (hhhh)

一键部署 **File Browser** + **Anime Hub**（动漫花园 / ACG.RIP / Nyaa 聚合搜索 + aria2 BT 下载）。

## 快速安装

```bash
git clone https://github.com/<你的用户名>/hhhh.git
cd hhhh
chmod +x install.sh
sudo ./install.sh install
```

## 卸载

```bash
sudo ./install.sh uninstall           # 保留下载数据
sudo ./install.sh uninstall --purge   # 连数据一起删
```

## 说明

详见脚本输出与 `install.sh` 头部注释。

| 服务 | 默认端口 |
|------|----------|
| File Browser | 8080 |
| Anime Hub | 8765 |
| aria2 RPC | 6800（本机） |

下载目录默认：`/home/share`
