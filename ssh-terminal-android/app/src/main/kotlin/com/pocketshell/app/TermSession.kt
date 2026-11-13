package com.pocketshell.app

import android.content.Intent
import android.os.Build
import android.os.Looper
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** 连接线程向界面提出的一次询问（主机指纹确认 / 输入密码等），线程阻塞等待 result */
class Prompt(val kind: Int, val message: String) {
    val result = CompletableFuture<String?>()

    companion object {
        const val YES_NO = 0
        const val SECRET = 1
        const val TEXT = 2
    }
}

/**
 * 一个 SSH 终端会话：JSch 的 shell 通道 + 终端模拟器。
 * 模拟器只在主线程上读写；网络读在独立线程，网络写在单线程执行器上。
 */
class TermSession(val server: Server) : TerminalOutput() {

    enum class State { CONNECTING, CONNECTED, CLOSED }

    interface Listener {
        fun onScreenUpdated()
        fun onStateChanged()
        fun onPrompt(p: Prompt)
    }

    val emulator = TerminalEmulator(this, 80, 24, 3000, NoopClient)

    @Volatile
    var state = State.CLOSED
        private set
    var listener: Listener? = null

    @Volatile
    var pendingPrompt: Prompt? = null
        private set

    /** 连接成功后在主线程执行一次（用于"连上后自动跑脚本"） */
    var onConnected: (() -> Unit)? = null

    @Volatile
    private var ssh: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    private var generation = 0
    private val writer = Executors.newSingleThreadExecutor()

    private val lock = Object()
    private val pending = ByteArrayOutputStream()
    private var scheduled = false

    // ---------- 连接 ----------

    /** 主线程调用 */
    fun connect() {
        if (state != State.CLOSED) return
        state = State.CONNECTING
        val gen = ++generation
        emulator.reset()
        local("正在连接 ${server.user}@${server.host}:${server.port} ...\r\n")
        listener?.onStateChanged()
        thread(name = "ssh-${server.host}") { run(gen) }
    }

    private fun run(gen: Int) {
        var s: Session? = null
        try {
            val jsch = JSch()
            val known = File(Store.appContext.filesDir, "known_hosts")
            if (!known.exists()) known.createNewFile()
            jsch.setKnownHosts(known.absolutePath)
            if (server.useKey) {
                if (server.privateKey.isBlank()) throw IOException("该服务器选择了密钥登录，但没有填写私钥")
                jsch.addIdentity(
                    "key-${server.id}",
                    server.privateKey.trim().replace("\r\n", "\n").plus("\n").toByteArray(),
                    null,
                    server.passphrase.takeIf { it.isNotEmpty() }?.toByteArray()
                )
            }
            s = jsch.getSession(server.user, server.host, server.port)
            if (!server.useKey && server.password.isNotEmpty()) s.setPassword(server.password)
            s.setConfig("StrictHostKeyChecking", "ask")
            s.setConfig(
                "PreferredAuthentications",
                if (server.useKey) "publickey,keyboard-interactive,password"
                else "password,keyboard-interactive,publickey"
            )
            s.userInfo = Ui()
            s.serverAliveInterval = 20000
            s.serverAliveCountMax = 3
            ssh = s
            s.connect(20000)
            if (gen != generation) throw IOException("已取消")

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", emulator.mColumns, emulator.mRows, 0, 0)
            val input = ch.inputStream
            val o = ch.outputStream
            ch.connect(15000)
            channel = ch
            out = o
            state = State.CONNECTED
            ui {
                if (gen == generation) {
                    // 连接期间屏幕尺寸可能变过，再同步一次
                    val c = emulator.mColumns
                    val r = emulator.mRows
                    writer.execute { try { ch.setPtySize(c, r, 0, 0) } catch (_: Exception) {} }
                    listener?.onStateChanged()
                    onConnected?.let { onConnected = null; it() }
                }
            }
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) feed(buf, n)
            }
            if (gen == generation) local("\r\n连接已关闭。")
        } catch (e: Throwable) {
            if (gen == generation) local("\r\n\u001b[31m${describe(e)}\u001b[0m")
        } finally {
            try { s?.disconnect() } catch (_: Exception) {}
            if (gen == generation) {
                ssh = null
                channel = null
                out = null
                state = State.CLOSED
                pendingPrompt?.result?.complete(null)
                local("\r\n\u001b[33m[按回车键重新连接]\u001b[0m\r\n")
                ui { onConnected = null; listener?.onStateChanged() }
            }
        }
    }

    private fun describe(e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.last()
        val msg = e.message ?: ""
        return when {
            cause is UnknownHostException -> "无法解析主机名：${server.host}"
            cause is SocketTimeoutException || msg.contains("timeout", true) -> "连接超时"
            cause is ConnectException -> "无法连接到 ${server.host}:${server.port}（${cause.message}）"
            msg.contains("Auth fail") -> "认证失败：用户名、密码或密钥不正确"
            msg.contains("Auth cancel") -> "已取消认证"
            msg.contains("reject HostKey") -> "已拒绝该主机密钥，连接中止"
            e is JSchException -> "SSH 错误：$msg"
            else -> "连接失败：${msg.ifEmpty { e.javaClass.simpleName }}"
        }
    }

    /** 主动断开（主线程调用） */
    fun disconnect() {
        generation++
        val s = ssh
        ssh = null
        channel = null
        out = null
        onConnected = null
        state = State.CLOSED
        pendingPrompt?.result?.complete(null)
        if (s != null) bg { try { s.disconnect() } catch (_: Exception) {} }
        listener?.onStateChanged()
    }

    // ---------- 终端数据 ----------

    /** 把本地提示信息显示到终端里 */
    private fun local(text: String) {
        val b = text.toByteArray()
        feed(b, b.size)
    }

    private fun feed(b: ByteArray, n: Int) {
        synchronized(lock) {
            // 输出洪水（比如 cat 大文件）时让读线程等一等主线程
            if (Looper.myLooper() != Looper.getMainLooper()) {
                while (pending.size() > 262144) {
                    try { lock.wait(200) } catch (_: InterruptedException) { break }
                }
            }
            pending.write(b, 0, n)
            if (!scheduled) {
                scheduled = true
                mainHandler.post(drain)
            }
        }
    }

    private val drain = Runnable {
        val data: ByteArray
        synchronized(lock) {
            data = pending.toByteArray()
            pending.reset()
            scheduled = false
            lock.notifyAll()
        }
        emulator.append(data, data.size)
        listener?.onScreenUpdated()
    }

    /** 模拟器和按键输入都从这里把字节发往服务器（主线程调用） */
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (state == State.CLOSED) {
            for (i in offset until offset + count) {
                if (data[i] == 13.toByte()) {
                    connect()
                    break
                }
            }
            return
        }
        val o = out ?: return
        val copy = data.copyOfRange(offset, offset + count)
        writer.execute {
            try {
                o.write(copy)
                o.flush()
            } catch (_: Exception) {
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols == emulator.mColumns && rows == emulator.mRows) return
        emulator.resize(cols, rows)
        val ch = channel ?: return
        writer.execute { try { ch.setPtySize(cols, rows, 0, 0) } catch (_: Exception) {} }
    }

    // ---------- 运行脚本 ----------

    /**
     * 通过同一条 SSH 连接的 exec 通道把脚本内容传到服务器的临时文件（权限 700），
     * 然后在当前终端里敲入运行命令，这样脚本的交互和输出都在终端里。
     * GitHub 令牌不会出现在服务器上。
     */
    fun runScript(name: String, content: String, args: String, deleteAfter: Boolean) {
        val s = ssh
        if (s == null || state != State.CONNECTED) {
            local("\r\n\u001b[31m未连接，无法运行脚本\u001b[0m\r\n")
            return
        }
        thread {
            try {
                val ch = s.openChannel("exec") as ChannelExec
                ch.setCommand(
                    "umask 077; f=\$(mktemp \"\${TMPDIR:-/tmp}/pshell.XXXXXX\") && cat > \"\$f\" && chmod 700 \"\$f\" && printf %s \"\$f\""
                )
                val o = ch.outputStream
                val i = ch.inputStream
                ch.connect(15000)
                o.write(content.replace("\r\n", "\n").toByteArray())
                o.flush()
                o.close()
                val path = i.readBytes().toString(Charsets.UTF_8).trim()
                ch.disconnect()
                if (!path.startsWith("/") || path.any { it == '\'' || it == '\n' || it == '\r' }) {
                    throw IOException("服务器上创建临时文件失败")
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                val interp = when {
                    ext == "sh" || ext == "bash" -> "bash "
                    ext == "py" -> "python3 "
                    ext == "js" -> "node "
                    ext == "rb" -> "ruby "
                    ext == "pl" -> "perl "
                    ext == "php" -> "php "
                    content.startsWith("#!") -> ""
                    else -> "bash "
                }
                var cmd = " $interp'$path'"
                if (args.isNotBlank()) cmd += " " + args.trim()
                if (deleteAfter) cmd += "; rm -f '$path'"
                ui { write("$cmd\r") }
            } catch (e: Exception) {
                local("\r\n\u001b[31m运行脚本失败：${e.message}\u001b[0m\r\n")
            }
        }
    }

    // ---------- JSch 交互回调（在连接线程上被调用） ----------

    private fun ask(kind: Int, msg: String): String? {
        val p = Prompt(kind, msg)
        pendingPrompt = p
        ui { listener?.onPrompt(p) }
        return try {
            p.result.get()
        } catch (e: Exception) {
            null
        } finally {
            if (pendingPrompt === p) pendingPrompt = null
        }
    }

    private inner class Ui : UserInfo, UIKeyboardInteractive {
        private var pass: String? = null
        private var phrase: String? = null
        private var savedPasswordTried = false

        override fun getPassphrase() = phrase
        override fun getPassword() = pass

        override fun promptPassword(message: String?): Boolean {
            pass = ask(Prompt.SECRET, "请输入 ${server.user}@${server.host} 的密码")
            return pass != null
        }

        override fun promptPassphrase(message: String?): Boolean {
            phrase = ask(Prompt.SECRET, "请输入私钥的口令")
            return phrase != null
        }

        override fun promptYesNo(message: String?): Boolean =
            ask(Prompt.YES_NO, message ?: "") == "yes"

        override fun showMessage(message: String?) {
            if (!message.isNullOrBlank()) local(message.replace("\n", "\r\n") + "\r\n")
        }

        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?
        ): Array<String>? {
            if (prompt == null || prompt.isEmpty()) return arrayOf()
            // 很多服务器把"密码登录"实现成 keyboard-interactive：有保存的密码就先自动试一次
            if (prompt.size == 1 && echo?.get(0) == false && !savedPasswordTried && server.password.isNotEmpty()) {
                savedPasswordTried = true
                return arrayOf(server.password)
            }
            val answers = ArrayList<String>()
            for (i in prompt.indices) {
                val head = listOfNotNull(name, instruction).filter { it.isNotBlank() }.joinToString("\n")
                val a = ask(
                    if (echo?.get(i) == true) Prompt.TEXT else Prompt.SECRET,
                    (if (head.isEmpty()) "" else head + "\n") + prompt[i]
                ) ?: return null
                answers.add(a)
            }
            return answers.toTypedArray()
        }
    }

    // ---------- TerminalOutput 的其余回调：用不到 ----------
    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() {}

    private object NoopClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}

/** 进程内的活动会话表：以服务器 id 为键，切到别的界面或别的应用再回来时会话还在 */
object SessionManager {
    val sessions = LinkedHashMap<String, TermSession>()

    fun get(id: String?): TermSession? = sessions[id]

    fun isActive(id: String) = sessions[id]?.let { it.state != TermSession.State.CLOSED } ?: false

    /** 已有未关闭的会话就复用，否则新建并开始连接 */
    fun open(server: Server): TermSession {
        sessions[server.id]?.let { if (it.state != TermSession.State.CLOSED) return it }
        val s = TermSession(server.copy())
        sessions[server.id] = s
        s.connect()
        updateService()
        return s
    }

    fun close(id: String) {
        sessions.remove(id)?.disconnect()
        updateService()
    }

    fun closeAll() {
        sessions.values.forEach { it.disconnect() }
        sessions.clear()
        updateService()
    }

    private fun updateService() {
        val ctx = Store.appContext
        val i = Intent(ctx, KeepAliveService::class.java)
        try {
            if (sessions.isEmpty()) ctx.stopService(i)
            else if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        } catch (_: Exception) {
            // 后台启动受限等情况：没有保活服务也能用，只是切到后台久了可能断线
        }
    }
}
