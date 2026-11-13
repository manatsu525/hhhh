package com.pocketshell.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

/**
 * 冒烟测试。界面部分随时可跑；SSH 部分需要一台测试用 sshd，通过环境变量提供：
 *   PSHELL_SSH_PORT / PSHELL_SSH_USER / PSHELL_SSH_PASS  本机 127.0.0.1 上的账号
 *   PSHELL_SSH_KEY_ED / PSHELL_SSH_KEY_ED_PASS            带口令的 ed25519 私钥文件
 *   PSHELL_SSH_KEY_RSA                                     不带口令的 RSA 私钥文件
 * 没有这些变量时 SSH 测试自动跳过。PSHELL_SHOT_DIR 指定截图输出目录。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmokeTest {
    private val shots = File(System.getenv("PSHELL_SHOT_DIR") ?: "build/shots").apply { mkdirs() }
    private val port = System.getenv("PSHELL_SSH_PORT")?.toIntOrNull()
    private val user = System.getenv("PSHELL_SSH_USER") ?: ""
    private val pass = System.getenv("PSHELL_SSH_PASS") ?: ""

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun waitFor(what: String, timeoutMs: Long = 30000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            idle()
            if (cond()) return
            if (System.currentTimeMillis() > end) {
                SessionManager.sessions.values.forEach { println("TRANSCRIPT AT TIMEOUT:\n" + text(it)) }
                fail("等待超时：$what")
            }
            Thread.sleep(40)
        }
    }

    private fun shot(a: Activity, name: String) {
        idle()
        val v = a.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(shots, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : View> find(root: View, cls: Class<T>): T? {
        if (cls.isInstance(root)) return cls.cast(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) find(root.getChildAt(i), cls)?.let { return it }
        return null
    }

    private fun text(s: TermSession) = s.emulator.screen.transcriptText

    @Before
    fun reset() {
        SessionManager.closeAll()
        Store.servers.clear()
        Store.snippets.clear()
        Store.favorites.clear()
        Store.githubToken = ""
        File(Store.appContext.filesDir, "known_hosts").delete()
    }

    @Test
    fun screensOpen() {
        Store.servers.add(Server(name = "东京 VPS", host = "203.0.113.10", user = "root", password = "x"))
        Store.servers.add(Server(name = "家里 NAS", host = "nas.example.com", port = 2200, user = "admin", useKey = true))
        Store.snippets.add(Snippet(name = "查看磁盘", command = "df -h"))
        Store.snippets.add(Snippet(name = "Docker 状态", command = "docker ps -a", autoEnter = false))

        shot(Robolectric.buildActivity(MainActivity::class.java).setup().get(), "main")
        shot(Robolectric.buildActivity(ServerEditActivity::class.java).setup().get(), "server_new")
        shot(
            Robolectric.buildActivity(
                ServerEditActivity::class.java,
                Intent().putExtra("id", Store.servers[1].id)
            ).setup().get(), "server_edit_key"
        )
        shot(Robolectric.buildActivity(SnippetsActivity::class.java).setup().get(), "snippets")
        shot(Robolectric.buildActivity(GitHubActivity::class.java).setup().get(), "github_setup")
    }

    private fun openTerminal(server: Server): Pair<TerminalActivity, TermSession> {
        if (Store.servers.none { it.id == server.id }) Store.servers.add(server)
        val a = Robolectric.buildActivity(
            TerminalActivity::class.java, Intent().putExtra("server", server.id)
        ).setup().get()
        return a to SessionManager.get(server.id)!!
    }

    private fun acceptHostKey(s: TermSession) {
        waitFor("主机密钥确认框") { s.pendingPrompt != null }
        assertEquals(Prompt.YES_NO, s.pendingPrompt!!.kind)
        println("HOSTKEY PROMPT: " + s.pendingPrompt!!.message)
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
    }

    @Test
    fun passwordLoginAndTerminal() {
        assumeTrue(port != null)
        val server = Server(name = "local", host = "127.0.0.1", port = port!!, user = user, password = pass)
        val (a, s) = openTerminal(server)
        val term = find(a.window.decorView, TerminalView::class.java)!!
        acceptHostKey(s)
        waitFor("连接") { s.state == TermSession.State.CONNECTED }
        waitFor("提示符") { text(s).contains("$") }

        // 普通输入 + 回车
        term.sendText("echo hello-\$((6*7))\n")
        waitFor("echo 输出") { text(s).contains("hello-42") }

        // 终端尺寸同步到了服务器
        term.sendText("stty size\n")
        val size = "${s.emulator.mRows} ${s.emulator.mColumns}"
        waitFor("stty size = $size") { text(s).lines().any { it.trim() == size } }
        assertTrue(s.emulator.mColumns > 20 && s.emulator.mRows > 10)

        // 粘滞 CTRL：cat 挂住后 Ctrl+C 打断
        term.sendText("cat\n")
        Thread.sleep(300)
        term.mods.ctrl = 1
        term.sendText("c")
        assertEquals(0, term.mods.ctrl)
        // 等 ^C 之后的新提示符出来（中断时 tty 会丢弃紧跟着的输入）
        waitFor("^C 回显") { text(s).contains("^C") }
        Thread.sleep(300)
        term.sendText("echo after-\$((1+1))\n")
        waitFor("Ctrl+C 之后的命令") { text(s).contains("after-2") }

        // 上方向键调出上一条命令再执行
        term.sendKeyCode(KeyEvent.KEYCODE_DPAD_UP)
        term.sendKeyCode(KeyEvent.KEYCODE_ENTER)
        waitFor("历史命令重放") { Regex("after-2").findAll(text(s)).count() >= 2 }

        // 全屏程序：进入备用屏幕，F2 / PgUp / Shift+Tab 的转义序列能正确送达
        term.sendText("clear; stty -echo; printf '\\033[?1049h'; read -rsn5 k; printf '\\033[?1049l'; stty echo; printf '%s' \"\$k\" | od -c | head -1\n")
        waitFor("备用屏幕") { s.emulator.isAlternateBufferActive }
        term.sendKeyCode(KeyEvent.KEYCODE_F5)
        waitFor("F5 序列") { text(s).contains("033   [   1   5   ~") }

        // 运行 GitHub 脚本的通道：上传、带参数执行、删除临时文件
        s.runScript("hello.sh", "#!/bin/bash\r\necho script-ok \"\$1\" \$((40+2))\r\n", "arg1", true)
        waitFor("脚本输出") { text(s).contains("script-ok arg1 42") }
        waitFor("临时文件已删除") { File("/tmp").list { _, n -> n.startsWith("pshell.") }!!.isEmpty() }

        // 快捷指令
        s.write("printf 'snip-%s\\n' done\r")
        waitFor("快捷指令输出") { text(s).contains("snip-done") }

        term.sendText("ls --color=always /etc | head -30; uname -a\n")
        waitFor("ls") { text(s).contains("Linux") }
        shot(a, "terminal")
        println("TRANSCRIPT:\n" + text(s))

        // 服务器端退出 → 会话变为已关闭，回车可重连且不再询问主机密钥
        term.sendText("exit\n")
        waitFor("关闭") { s.state == TermSession.State.CLOSED }
        assertTrue(text(s).contains("按回车键重新连接"))
        term.sendKeyCode(KeyEvent.KEYCODE_ENTER)
        waitFor("重连") { s.state == TermSession.State.CONNECTED }
        assertNull(s.pendingPrompt)
        SessionManager.close(server.id)
        assertEquals(TermSession.State.CLOSED, s.state)
    }

    @Test
    fun keyLogin() {
        assumeTrue(port != null && System.getenv("PSHELL_SSH_KEY_ED") != null)
        val ed = File(System.getenv("PSHELL_SSH_KEY_ED")!!).readText()
        val edPass = System.getenv("PSHELL_SSH_KEY_ED_PASS") ?: ""

        // 带口令的 ed25519，口令已保存
        var server = Server(name = "ed", host = "127.0.0.1", port = port!!, user = user, useKey = true, privateKey = ed, passphrase = edPass)
        var (_, s) = openTerminal(server)
        acceptHostKey(s)
        waitFor("ed25519 登录") { s.state == TermSession.State.CONNECTED }
        SessionManager.close(server.id)

        // 口令没保存：连接时询问
        server = server.copy(id = "ed2", passphrase = "")
        s = openTerminal(server).second
        waitFor("口令询问") { s.pendingPrompt != null }
        assertEquals(Prompt.SECRET, s.pendingPrompt!!.kind)
        s.pendingPrompt!!.result.complete(edPass)
        waitFor("ed25519 登录（询问口令）") { s.state == TermSession.State.CONNECTED }
        SessionManager.close(server.id)

        // 不带口令的 RSA
        val rsa = File(System.getenv("PSHELL_SSH_KEY_RSA")!!).readText()
        server = Server(name = "rsa", host = "127.0.0.1", port = port, user = user, useKey = true, privateKey = rsa)
        s = openTerminal(server).second
        waitFor("RSA 登录") { s.state == TermSession.State.CONNECTED }
        SessionManager.close(server.id)
    }

    @Test
    fun wrongPasswordAndUnreachable() {
        assumeTrue(port != null)
        var server = Server(name = "bad", host = "127.0.0.1", port = port!!, user = user, password = "wrong-password")
        var (_, s) = openTerminal(server)
        acceptHostKey(s)
        // 保存的密码不对 → 询问密码；取消 → 关闭
        waitFor("密码询问") { s.pendingPrompt?.kind == Prompt.SECRET }
        s.pendingPrompt!!.result.complete(null)
        waitFor("关闭") { s.state == TermSession.State.CLOSED }
        println("BAD PASSWORD: " + text(s))
        SessionManager.close(server.id)

        server = Server(name = "nope", host = "127.0.0.1", port = 1, user = user, password = "x")
        s = openTerminal(server).second
        waitFor("连接失败") { s.state == TermSession.State.CLOSED && text(s).contains("重新连接") }
        println("UNREACHABLE: " + text(s))
        assertTrue(text(s).contains("无法连接"))
    }
}
