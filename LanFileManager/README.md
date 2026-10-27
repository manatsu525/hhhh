# 文件互传管家（LanFileManager）

一个 Android 文件管理器，内置局域网互传：电脑**不用安装任何软件**，用浏览器打开手机上显示的地址，就能和手机互发消息、互传文件，也能浏览手机文件。

## 功能

**手机端文件管理**
- 浏览、搜索（筛选当前目录）、排序、显示/隐藏隐藏文件
- 多选（长按进入），可以复制、剪切、粘贴、重命名、删除、分享、选择打开方式、查看属性
- 图片、视频、APK 显示缩略图（只缓存在内存里）
- 可以访问各 app 的私有目录：`Android/data` 和 `Android/obb`，做法参照 ZArchiver
  - Android 10 及以下：直接访问
  - Android 11 / 12：通过系统文件选择器授权一次整个目录
  - Android 13 及以上：系统不允许整体授权，所以按每个 app 的目录单独授权，列表里显示 app 名称
  - **Shizuku（v1.1 起支持，Android 14 及以上推荐）**：授权一次，就能以 shell 身份访问所有 app 的 data/obb。读写、移动、删除、电脑端浏览都走它
  - 部分 Android 14 及以上的设备，系统已封掉 SAF 授权方式，这时请使用 Shizuku

**内置文本编辑器（v1.2 起）**
- 点开 txt、log、md、json、xml、代码、字幕等文本文件，直接用内置编辑器打开；其他文件可以「打开方式 → 内置文本编辑器」
- 菜单 →「新建文本文件」
- 大文件不卡：自研编辑视图，只排版、只绘制屏幕上可见的部分；文本用 gap buffer 存储，行索引增量维护。100 MB 文本建立行索引约几十毫秒，输入延迟与文件大小无关
- 一行几十 MB 的压缩 JSON 也能打开（不换行模式下按 4096 字符分段显示）
- 查找：区分大小写、全字匹配、正则；实时高亮、显示匹配数量；上一个 / 下一个（到头自动从另一端继续）
- 替换：逐个替换、全部替换（可撤销）；正则模式下替换内容支持 `$1`、`${name}`、`\n`、`\t`
- 撤销 / 重做、跳转到行、自动换行、行号、只读模式、双指缩放字体、自动缩进
- 编码：自动识别 UTF-8 / UTF-8 BOM / UTF-16 / GB18030；可以换编码重新打开，也可以换编码保存（GBK、Big5、Shift_JIS 等）。遇到目标编码无法表示的字符会提示，不会悄悄变成问号
- 换行符：自动识别 LF / CRLF / CR，保存时保持原样，也可以手动转换
- 另存为（系统文件选择器）；也能被其他 app 用「打开方式」调用，能写回时直接保存
- 接物理键盘：Ctrl+S / F / H / G / Z / Y / A / C / X / V，F3 查找下一个
- 可打开的大小取决于手机给 app 的内存（已开启 largeHeap），一般 8 GB 内存的手机可以打开 100 MB 以上的纯文本

**压缩包（v1.4 起）**
- 点开压缩包直接进入内置查看器，**不用解压**就能像文件夹一样浏览里面的目录
  - 点开里面的文件直接查看（图片、视频、文本、APK…），只临时解压这一个文件到应用缓存，关闭压缩包后自动删除
  - 压缩包里的压缩包可以继续点开
  - 长按多选，可以只解压选中的文件 / 文件夹，也可以分享、选择打开方式
  - 当前目录内搜索、压缩包信息（格式、文件数、解压后大小、压缩率）
  - zip 文件名乱码时可以手动切换编码（自动识别 UTF-8 / GBK，另有 Big5、Shift_JIS、EUC-KR 等）
- 解压：在压缩包查看器里点「全部解压」，或在文件管理器里选中压缩包 →「解压到…」
  - 可以解压到同名新文件夹或当前文件夹；遇到同名文件可选保留两者 / 覆盖 / 跳过
  - 也能解压到 `Android/data` 等私有目录（通过 Shizuku / 系统授权）
- 创建压缩文件：在文件管理器里多选文件或文件夹 →「压缩…」
  - 格式：ZIP、7Z、TAR.GZ、TAR.XZ、TAR.BZ2、TAR
  - 压缩级别：仅存储 / 快速 / 标准 / 最高
  - 密码：ZIP 用 AES-256 加密；7Z 用 AES-256 加密，还可以同时加密文件名
- 支持读取的格式：

  | 格式 | 浏览 / 解压 | 创建 | 说明 |
  |---|---|---|---|
  | ZIP（含 jar、apk、docx、epub 等） | ✅ | ✅ | 支持 AES / ZipCrypto 密码、GBK 文件名、zip64、Deflate64、zipx |
  | 7Z | ✅ | ✅ | 支持密码和加密文件名，支持分卷 `.7z.001` |
  | RAR（RAR4 / RAR5） | ✅ | ❌ | 支持密码、加密文件名、分卷 `.part1.rar` |
  | TAR、TAR.GZ / TGZ、TAR.BZ2、TAR.XZ | ✅ | ✅ | |
  | GZ、BZ2、XZ、LZMA、Z（单个文件） | ✅ | — | |
  | ISO、CAB、ARJ、LZH、CPIO、RPM、DEB、WIM、DMG、XAR、CHM、NSIS 等 | ✅ | ❌ | 由 7-Zip 引擎读取 |
  | ZST（Zstandard） | ❌ | ❌ | 暂不支持 |

  RAR 是商业格式，没有公开的创建方式，所以只能解压不能创建（和 ZArchiver 等应用一样）。
- 其他应用也可以用「打开方式」把压缩包交给本应用查看

**电脑互传（浏览器）**
- 手机打开「电脑互传」，电脑浏览器访问显示的地址（如 `http://192.168.1.5:8899`），输入 4 位访问码
- 双向发文字消息，网址可以点击，一键复制
- 电脑发给手机：
  - 选文件
  - 选文件夹
  - 拖放文件或文件夹
  - 直接粘贴截图
  - 上传时显示进度和速度，可以取消
- 手机发给电脑：
  - 在文件管理器里多选，点「发送到电脑」
  - 在聊天页点 📎
  - 从其他 app「分享」到本 app
- 文件夹发送时会实时打包成 zip 下载，不在手机上生成临时文件
- 支持断点续传（HTTP Range），大文件和视频可以边下边看
- 「手机文件」页可以在电脑上浏览手机存储（包括已授权的私有目录），支持下载、上传到指定目录、新建文件夹
- 安全措施：
  - 访问码每次启动服务时随机生成
  - 访问码连续输错 5 次锁定 60 秒
  - 可以关闭「允许电脑浏览手机文件」

## 零残留设计

- 设置保存在 `/data/data/com.lanfile.manager`，卸载时系统自动删除
- 聊天记录只放在内存里；缩略图不写入磁盘
- 不在公共存储里创建任何自己的文件夹
- 禁用系统备份（`allowBackup=false`、`dataExtractionRules`），重装后旧数据不会恢复
- 其他 app 分享过来的文件，暂存在应用缓存（卸载即删），清空聊天记录时一并清理
- 压缩包查看器：点开查看的文件只临时解压到应用缓存，关闭压缩包时删除；异常退出的话下次启动时清理。创建压缩文件时先写同目录下的隐藏临时文件 `.文件名.lanpart`，完成后再改名，失败或取消会删除
- 文本编辑器：不写缓存、不自动保存副本，未保存的内容只在内存里。保存时先写同目录下的隐藏临时文件 `.文件名.lanpart`，写完再替换原文件（中途失败原文件不受影响），临时文件同样登记在案，异常退出后下次启动自动清理
- 接收中的文件用 `.lanpart` 临时名写入。传输失败会立即删除；如果 app 意外退出，下次启动时自动清理
- 删除、移动文件后会通知媒体库同步，相册里不会留下失效的记录
- `Android/data` 的 SAF 授权由系统保存，卸载时系统自动收回
- Shizuku 文件服务不是常驻进程，随本应用退出；它只是读取本应用的 APK 运行，不复制任何文件。授权记录只保存在 Shizuku 自己的应用数据里，可以在 Shizuku 的「已授权应用」中撤销

> 电脑发来的文件默认存到系统「下载」目录。这些是你自己的文件，卸载后会保留。
> 如果希望卸载时一并删除，在「设置 → 电脑发来的文件保存到」里选「应用专属目录」。

## 构建

需要 JDK 17 及以上和 Android SDK（platform 34）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

签名用的是项目里的 `app/release.keystore`，密码 `lanfile123`。如果要长期发布，请换成你自己的签名。

## 项目结构

```
app/src/main/
├── assets/web/index.html          浏览器端页面（单文件，无外部依赖）
└── java/com/lanfile/manager/
    ├── core/                      纯 Java，可脱离 Android 测试
    │   ├── WebServer.java         零依赖 HTTP 服务器（SSE 推送、上传、Range 下载、zip 打包）
    │   ├── Hub.java               消息、登录会话、推送中心（仅内存）
    │   ├── Vfs.java / LocalVfs    路径式文件系统抽象
    │   └── Util.java
    ├── AndroidVfs.java            File / Shizuku / SAF 透明切换，用来访问 Android/data、obb
    ├── ShizukuHelper.java         Shizuku 状态、授权、绑定
    ├── ShizukuFileService.java    运行在 shell 进程中的文件服务（AIDL: IFileService）
    ├── MainActivity.java          文件管理器
    ├── EditorActivity.java        文本编辑器界面（查找替换栏、保存、编码）
    ├── editor/                    编辑器核心（纯 Java + 一个自定义 View）
    │   ├── TextBuffer.java        gap buffer + 增量行索引
    │   ├── EditorView.java        只绘制可见行的编辑视图，含输入法、选择、滚动
    │   ├── TextIO.java            流式读写、编码与换行符识别
    │   ├── Searcher.java          查找 / 替换
    │   └── UndoManager.java       撤销重做
    ├── archive/                   压缩引擎（纯 Java，可脱离 Android 测试）
    │   ├── ArchiveReader.java     识别格式、列目录、解压（zip4j / commons-compress / 7-Zip 三种实现）
    │   ├── ZipReader.java         zip：密码、文件名编码识别
    │   ├── StreamReader.java      tar 及 gz / bz2 / xz / lzma / Z
    │   ├── NativeReader.java      7-Zip 引擎：7z、rar、iso、cab 等，含分卷
    │   ├── ArchiveWriter.java     创建 zip / 7z / tar.*
    │   └── Extractor.java         解压到 Vfs（同名处理、防目录穿越）
    ├── ArchiveActivity.java       压缩包查看器
    ├── ArchiveUi.java             压缩 / 解压对话框和后台任务
    ├── OpRunner.java              带进度、可取消的后台操作
    ├── TransferActivity.java      互传聊天界面
    ├── TransferService.java       前台服务，运行 HTTP 服务器
    ├── ShareProvider.java         只读文件 Provider，用于「打开方式」和「分享」
    ├── Thumbs.java                内存缩略图
    ├── SettingsDialog.java / Prefs.java / App.java
```

第三方依赖：
- Shizuku API
- [zip4j](https://github.com/srikanth-lingala/zip4j)（Apache-2.0）：zip 读写和加密
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) + [XZ for Java](https://tukaani.org/xz/java.html)：tar、gz、bz2、xz
- [7-Zip-JBinding-4Android](https://github.com/omicronapps/7-Zip-JBinding-4Android)（LGPL-2.1，基于 7-Zip 16.02）：7z、rar 等

release APK 约 3.3 MB（大部分是 7-Zip 原生库，包含 arm64-v8a、armeabi-v7a、x86_64 三种架构）。

## Shizuku 使用步骤（Android 14 及以上）

1. 安装 Shizuku：https://shizuku.rikka.app/zh-hans/download/
2. 打开 Shizuku，按提示用「无线调试」配对并启动（已 root 的手机可以直接用 root 启动）
3. 回到本应用：菜单 →「Shizuku（访问应用私有目录）」→「授权」，或者直接点进 `Android/data`，选「用 Shizuku 授权」
4. 未 root 的手机重启后，需要在 Shizuku 里重新启动一次服务
