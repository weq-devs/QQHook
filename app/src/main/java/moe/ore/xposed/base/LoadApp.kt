package moe.ore.xposed.base

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kyuubiran.ezxhelper.android.logging.Logger
import dev.weq.qqhook.BuildConfig
import moe.ore.xposed.common.ModeleStatus
import moe.ore.xposed.hook.AntiDetection
import moe.ore.xposed.hook.MainHook
import moe.ore.xposed.hook.base.ProcUtil
import moe.ore.xposed.hook.base.hostAndroidId
import moe.ore.xposed.hook.base.hostApp
import moe.ore.xposed.hook.base.hostAppName
import moe.ore.xposed.hook.base.hostClassLoader
import moe.ore.xposed.hook.base.hostInit
import moe.ore.xposed.hook.base.hostPackageName
import moe.ore.xposed.hook.base.hostProcessName
import moe.ore.xposed.hook.base.hostVersionCode
import moe.ore.xposed.hook.base.hostVersionName
import moe.ore.xposed.hook.base.moduleClassLoader
import moe.ore.xposed.hook.enums.QQTypeEnum
import moe.ore.xposed.hooks.ListenTXHookUpdate
import moe.ore.xposed.utils.FileBridge
import moe.ore.xposed.utils.FuzzySearchClass
import moe.ore.xposed.utils.XPClassloader
import moe.ore.xposed.utils.afterHook
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object LoadApp {

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (QQTypeEnum.valueOfPackage(lpparam.packageName)) {
            QQTypeEnum.TXHook -> hookActivation(lpparam)
            else -> hookEntry(lpparam)
        }
    }

    private fun hookActivation(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            ModeleStatus::class.java.name,
            lpparam.classLoader,
            "isModuleActivated",
            XC_MethodReplacement.returnConstant(true)
        )
    }

    @Volatile
    private var startupDone = false

    private fun hookEntry(lpparam: XC_LoadPackage.LoadPackageParam) {
        hostPackageName = lpparam.packageName
        hostProcessName = lpparam.processName
        hostClassLoader = lpparam.classLoader

        val startup = afterHook(50) { param ->
            if (startupDone) return@afterHook
            startupDone = true
            XposedBridge.log("[TXHook-DIAG] startup 回调触发 pid=${android.os.Process.myPid()} proc=$hostProcessName")
            try {
                val loader = param.thisObject.javaClass.classLoader!!
                XPClassloader.ctxClassLoader = loader

                XposedBridge.log("[TXHook-DIAG] startup: 准备加载 Application 类, loader=$loader")
                val clz = try {
                    loader.loadClass("com.tencent.common.app.BaseApplicationImpl").also {
                        XposedBridge.log("[TXHook-DIAG] startup: BaseApplicationImpl OK")
                    }
                } catch (e: ClassNotFoundException) {
                    XposedBridge.log("[TXHook-DIAG] startup: BaseApplicationImpl 缺失 -> 试 WatchApplicationDelegate")
                    Logger.e("loadClass BaseApplicationImpl failed", e)
                    loader.loadClass("com.tencent.qqnt.watch.app.WatchApplicationDelegate")
                }
                // 优先直接用触发回调的 Application 实例：
                // Application.onCreate 阶段静态 INSTANCE 字段可能还没赋值。
                val app: Context = (param.thisObject as? Context) ?: run {
                    val field = clz.declaredFields.firstOrNull { it.type == clz }
                        ?: throw IllegalStateException("找不到 Application 实例字段")
                    field.isAccessible = true
                    (field.get(null) as? Context)
                        ?: throw IllegalStateException("Application 静态实例为 null")
                }
                XposedBridge.log("[TXHook-DIAG] startup: 拿到 Context=${app.javaClass.name}")
                if (!hostInit) {
                    hostApp = app as Application
                }
                hostClassLoader = hostApp.classLoader

                execStartupInit(app)

                if (ProcUtil.isMain) {
                    Logger.i(
                        """
                        module version: ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})
                        hook version: ${hostAppName}-$hostVersionName($hostVersionCode)
                        androidId: $hostAndroidId
                    """.trimIndent()
                    )
                }
            } catch (e: Exception) {
                Logger.e("HookEntry startup failed", e)
                XposedBridge.log("[TXHook-DIAG] startup 异常 pid=${android.os.Process.myPid()}: ${e}")
            }
        }

        entry(hostClassLoader, startup)
    }

    private fun classExists(loader: ClassLoader, className: String): Boolean =
        try {
            loader.loadClass(className)
            true
        } catch (_: ClassNotFoundException) {
            false
        }

    private fun hookBooleanNoParamMethods(
        loader: ClassLoader,
        className: String,
        hook: XC_MethodHook
    ) {
        val clazz = loader.loadClass(className)
        clazz.declaredMethods
            .filter { it.returnType == Boolean::class.java && it.parameterTypes.isEmpty() }
            .forEach {
                XposedBridge.hookMethod(it, hook)
            }
    }

    private fun handleNtQqHook(loader: ClassLoader, hook: XC_MethodHook) {
        val fieldList = arrayListOf<Field>()
        FuzzySearchClass.findAllClassByField(
            loader,
            "com.tencent.mobileqq.startup.task.config"
        ) { _, field ->
            (field.type == HashMap::class.java || field.type == Map::class.java) && Modifier.isStatic(
                field.modifiers
            )
        }.forEach {
            it.declaredFields.forEach { field ->
                if ((field.type == HashMap::class.java || field.type == Map::class.java) && Modifier.isStatic(
                        field.modifiers
                    )
                ) {
                    fieldList.add(field)
                }
            }
        }

        XposedBridge.log("[TXHook-DIAG] handleNtQqHook 找到 ${fieldList.size} 个静态 Map 字段")
        var hooked = 0
        fieldList.forEach { field ->
            if (!field.isAccessible) field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as? Map<String, Class<*>>)?.forEach { (key, clazz) ->
                if (key.contains("LoadDex", ignoreCase = true)) {
                    XposedBridge.log("[TXHook-DIAG] 命中 LoadDex key=$key class=${clazz.name}")
                    clazz.declaredMethods.forEach { method ->
                        if (method.parameterTypes.size == 1 && method.parameterTypes[0] == Context::class.java) {
                            XposedBridge.hookMethod(method, hook)
                            hooked++
                            XposedBridge.log("[TXHook-DIAG] hook 方法 ${method.name}")
                        }
                    }
                }
            }
        }
        XposedBridge.log("[TXHook-DIAG] handleNtQqHook 共 hook $hooked 个方法")
    }

    private fun entry(loader: ClassLoader, startup: XC_MethodHook) {
        val diagProc = runCatching { hostProcessName }.getOrElse { "?" }
        val diagPid = android.os.Process.myPid()
        XposedBridge.log("[TXHook-DIAG] entry() pid=$diagPid proc=$diagProc")
        val type = when {
            classExists(loader, "com.tencent.mobileqq.startup.step.LoadDex") -> 1
            classExists(
                loader,
                "com.tencent.qqnt.watch.startup.task.ApplicationCreateStageTask"
            ) -> 2

            else -> 0
        }

        XposedBridge.log("[TXHook-DIAG] entry() type=$type pid=${android.os.Process.myPid()}")

        // 兜底：Application.onCreate 是进程启动必经入口，比 startup task 可靠。
        // 主进程走 LoadDexTask 也能到，但 MSF 等子进程可能不执行 startup task 链。
        // 直接把同一个 startup hook 再挂一份到 Application.onCreate。
        runCatching {
            val appCls = loader.loadClass("android.app.Application")
            XposedBridge.hookAllMethods(appCls, "onCreate", startup)
            XposedBridge.log("[TXHook-DIAG] 已挂 Application.onCreate 兜底 hook")
        }.onFailure {
            XposedBridge.log("[TXHook-DIAG] hook Application.onCreate 失败: $it")
        }

        when (type) {
            0 -> handleNtQqHook(loader, startup)
            1 -> hookBooleanNoParamMethods(
                loader,
                "com.tencent.mobileqq.startup.step.LoadDex",
                startup
            )

            2 -> hookBooleanNoParamMethods(
                loader,
                "com.tencent.qqnt.watch.startup.task.ApplicationCreateStageTask",
                startup
            )
        }
    }

    private fun execStartupInit(ctx: Context) {
        val classLoader = ctx.classLoader.also { requireNotNull(it) }
        XPClassloader.hostClassLoader = classLoader

        // 文件投递通道：写宿主自己的 files 目录，App 端用 root 读取
        runCatching { FileBridge.init(ctx.filesDir) }
            .onFailure { XposedBridge.log("[TXHook] FileBridge 初始化失败: $it") }

        initHooks(
            // list
            ListenTXHookUpdate,
        )

        if (injectClassloader(moduleClassLoader)) {
            // 诊断：进程判断在 Android 10+ 可能失效（runningAppProcesses 只返回自身）
            val procNameDiag = runCatching { ProcUtil.procName }.getOrElse { "ERR:${it.message}" }
            val isMsfDiag = runCatching { ProcUtil.isMsf }.getOrElse { false }
            android.util.Log.i(
                "TXHook-DIAG",
                "[INIT] pid=${android.os.Process.myPid()} procName=$procNameDiag " +
                    "isMsf=$isMsfDiag hostProcess=$hostProcessName",
            )
            XposedBridge.log(
                "[TXHook-DIAG] pid=${android.os.Process.myPid()} procName=$procNameDiag " +
                    "isMsf=$isMsfDiag hostProcess=$hostProcessName",
            )

            AntiDetection() // 由于进程作用域不同，现在它不能放进isMsf的判断中

            if (ProcUtil.isMsf) {
                MainHook(0, ctx)
            }
        }
    }

    private fun injectClassloader(moduleLoader: ClassLoader): Boolean {
        if (runCatching { moduleLoader.loadClass("mqq.app.MobileQQ") }.isSuccess) {
            return true
        }

        val parent = moduleLoader.parent
        val field = ClassLoader::class.java.declaredFields
            .first { it.name == "parent" }

        field.isAccessible = true
        field.set(XPClassloader, parent)

        if (XPClassloader.load("mqq.app.MobileQQ") == null) return false
        field.set(moduleLoader, XPClassloader)

        return runCatching { Class.forName("mqq.app.MobileQQ") }.isSuccess
    }

    private fun initHooks(vararg hooks: BaseHook) {
        for (hook in hooks) {
            kotlin.runCatching {
                hook.init()
            }.onFailure {
                Logger.e("Hook init failed", it)
            }
        }
        Logger.i("Hook init success")
    }
}
