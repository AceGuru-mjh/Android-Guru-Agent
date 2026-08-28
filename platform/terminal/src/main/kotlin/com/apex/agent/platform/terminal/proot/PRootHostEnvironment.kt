package com.apex.agent.platform.terminal.proot

import java.io.File

/**
 * P71: PRoot 运行时宿主环境 —— 目录准备 + host env 构建。
 *
 * 纯 JVM（java.io.File），无 Android Context 依赖：app DI 注入
 * `context.applicationInfo.nativeLibraryDir` 与 `filesDir`/`cacheDir`，
 * JVM 测试注入临时目录。
 *
 * 职责（一次性 [prepare]，幂等）：
 *  1. staging 目录 `<baseDir>/linux/bin/`：
 *     - `libtalloc.so.2 -> <nativeLibraryDir>/libtalloc.so`
 *       （Android 打包要求 jniLibs 命名 `lib*.so`，但 proot 的 DT_NEEDED 是
 *        `libtalloc.so.2` —— SONAME 查找按文件名进行，需同名字的入口；
 *        symlink 指向 nativeLibraryDir 内真实文件，遵循 Termux/UserLAnd 先例）
 *  2. proot 临时目录 `<cacheDir>/proot-tmp/`（Android 的 /tmp 不可写）
 *  3. [hostEnv] —— forkpty child / ProcessBuilder 的 env（G4：与 guest env 严格分离，
 *     guest env 只经 proot 的 -E 传入）：
 *       PROOT_TMP_DIR   proot 自身临时目录
 *       PROOT_LOADER    guest loader（宿主侧 ptrace 注入用）
 *       PROOT_LOADER_32 32 位 guest loader（存在时）
 *       LD_LIBRARY_PATH staging 目录 + nativeLibraryDir（libtalloc.so.2 解析）
 *       PATH            Android 系统路径（proot 自身 exec /bin/sh 探针用）
 */
class PRootHostEnvironment(
    /** App 的 nativeLibraryDir（APK 解压后的 .so 所在目录，唯一保证可执行）。 */
    val nativeLibraryDir: String,
    /** App filesDir（staging 落点：<baseDir>/linux/bin）。 */
    private val baseDir: File,
    /** App cacheDir（proot tmp 落点：<cacheDir>/proot-tmp）。 */
    private val cacheDir: File
) {

    val prootBinary: File get() = File(nativeLibraryDir, "libproot.so")
    val loaderBinary: File get() = File(nativeLibraryDir, "libproot-loader.so")
    val loader32Binary: File get() = File(nativeLibraryDir, "libproot-loader32.so")
    val tallocLibrary: File get() = File(nativeLibraryDir, "libtalloc.so")

    /** staging 目录（含 libtalloc.so.2 symlink）。 */
    val stagingDir: File get() = File(baseDir, "linux/bin")

    /** proot 自身的可写临时目录。 */
    val prootTmpDir: File get() = File(cacheDir, "proot-tmp")

    private var prepared = false

    /** 幂等准备。失败（IO 错误）返回 Result.failure。 */
    fun prepare(): Result<Unit> = runCatching {
        if (prepared) return@runCatching
        if (!prootBinary.canExecute()) {
            error("PRootHostEnvironment: ${prootBinary.absolutePath} 不存在或不可执行")
        }
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            error("PRootHostEnvironment: 无法创建 staging 目录 ${stagingDir.absolutePath}")
        }
        // libtalloc.so.2 SONAME 入口 —— 每次重建（覆盖旧链接/旧文件，容忍升级）。
        val sonameLink = File(stagingDir, "libtalloc.so.2")
        if (sonameLink.exists()) sonameLink.delete()
        java.nio.file.Files.createSymbolicLink(
            sonameLink.toPath(),
            tallocLibrary.toPath()
        )
        if (!prootTmpDir.exists() && !prootTmpDir.mkdirs()) {
            error("PRootHostEnvironment: 无法创建 proot tmp 目录 ${prootTmpDir.absolutePath}")
        }
        prepared = true
    }

    /**
     * proot 进程的 host env（G4：不含任何 guest 变量；guest env 由 argv 的 -E 携带）。
     * 调用前必须先 [prepare] 成功。
     */
    fun hostEnv(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val env = mutableMapOf<String, String>(
            "PROOT_TMP_DIR" to prootTmpDir.absolutePath,
            "PROOT_LOADER" to loaderBinary.absolutePath,
            "LD_LIBRARY_PATH" to "${stagingDir.absolutePath}:$nativeLibraryDir",
            // proot 自身（宿主侧）需要能 exec /bin/sh 做 rootfs 探针 —— Android PATH。
            "PATH" to "/system/bin:/system/xbin:/vendor/bin:/product/bin"
        )
        if (loader32Binary.exists()) {
            env["PROOT_LOADER_32"] = loader32Binary.absolutePath
        }
        env.putAll(extra)
        return env
    }
}
