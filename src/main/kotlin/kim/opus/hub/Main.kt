package kim.opus.hub

import io.javalin.Javalin
import io.javalin.http.staticfiles.Location
import kim.opus.hub.data.*
import kim.opus.hub.model.*
import kim.opus.hub.view.*
import kim.opus.hub.web.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

fun main() {
    val config = Config.load()

    Db.init(config.dbPath)
    Schema.migrate()
    Uploads.configure(config.uploadDir, config.maxUploadBytes)
    Sessions.configure(config.sessionMinutes, config.secureCookie)
    Bootstrap.ensureAdmin(config)

    val app = Javalin.create { cfg ->
        cfg.jetty.host = config.host
        cfg.jetty.port = config.port
        cfg.staticFiles.add { files ->
            files.hostedPath = "/public"
            files.directory = "/public"
            files.location = Location.CLASSPATH
            files.headers = mapOf("Cache-Control" to Assets.CACHE_CONTROL)
        }
        Routes.register(cfg)
    }.start()

    val sweeper = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "session-sweeper").apply { isDaemon = true }
    }
    sweeper.scheduleAtFixedRate({ Sessions.sweep() }, 10, 10, TimeUnit.MINUTES)

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { app.stop() }
        runCatching { Db.close() }
    })

    Bootstrap.printBanner(config)
}

object Bootstrap {

    private const val ADMIN_USERNAME = "admin"

    fun ensureAdmin(config: Config) {
        if (UserRepo.count() > 0L) return
        val password = Tokens.generate(12)
        UserRepo.createAdmin(ADMIN_USERNAME, config.adminDisplayName, password)
        println("=".repeat(64))
        println("已创建初始管理员账号")
        println("  账号: " + ADMIN_USERNAME)
        println("  显示名: " + config.adminDisplayName)
        println("  密码: " + password)
        println("  这个密码是随机生成的，只显示这一次，请保存好")
        println("=".repeat(64))
    }

    fun printBanner(config: Config) {
        if (config.host == "0.0.0.0" || config.host == "::") {
            println("已启动: 监听 " + config.host + ":" + config.port + "，用 http://服务器IP:" + config.port + " 访问")
        } else {
            println("已启动: http://" + config.host + ":" + config.port)
        }
        println("  数据目录: " + config.dataDir)
        println("  附件上限: " + formatSize(config.maxUploadBytes))
        if (config.host != "127.0.0.1" && config.host != "localhost" && !config.secureCookie) {
            println()
            println("!! 注意: 当前监听 " + config.host + " 且未启用 HTTPS 专用 Cookie")
            println("!! 明文 HTTP 会把密码暴露在网络上，请在前面挂一层 HTTPS 反向代理，")
            println("!! 并把 cookie.secure 设为 true")
            println()
        }
    }
}
