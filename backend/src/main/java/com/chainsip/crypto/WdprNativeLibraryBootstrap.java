package com.chainsip.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * FISCO BCOS 2.x 加密原生库（WeDPR libffi）加载引导。
 *
 * <h2>为什么需要这个类</h2>
 * SDK 2.x 的 ECDSA/SM 运算全部走 JNI 原生库 {@code com.webank.wedpr.crypto.NativeInterface}，
 * 而这个类在静态初始化时会：
 * <ol>
 *   <li>按 OS 拼出资源路径 {@code WeDPR_dynamic_lib/libffi_java_sdk{tail}}（从 jar 内读）；</li>
 *   <li>把 .dll/.so/.dylib <b>解压到 {@code <user.home>/.fisco/nativeutils/}</b>；
 *       解压流程会无条件创建并写入 {@code native.lock}。</li>
 * </ol>
 * 这意味着两件事：
 * <ul>
 *   <li>只要 {@code ~/.fisco/nativeutils} <b>不可写</b>，就会抛
 *       {@code FileNotFoundException: ...native.lock (拒绝访问。)}，进而
 *       {@code ExceptionInInitializerError: Failed to load library}，
 *       最终整个 {@code CryptoSuite} 构造失败 —— 钱包验签、机构私钥地址推导全废，
 *       而且因为 {@code WalletSignatureVerifier} 是单例 Bean，应用会在启动时就崩。
 *       典型触发场景：容器/沙箱只挂载了工作区可写、CI、或该目录被安全软件锁住。</li>
 *   <li>解压出来的库缓存在用户目录，多版本 SDK 之间还会互相覆盖。</li>
 * </ul>
 *
 * <h2>怎么绕开</h2>
 * 反编译 SDK 依赖 {@code webank-blockchain-java-crypto} 的 {@code NativeUtils.loadLibrary(String)}
 * 可以看到它的第一段逻辑（javap -c 结论）：
 * <pre>
 *   String ffiPath = System.getProperty("java.library.ffipath");
 *   if (ffiPath != null &amp;&amp; !ffiPath.isEmpty()
 *           &amp;&amp; loadLibraryFromLibraryPath(ffiPath, libName)) {
 *       return;   // &lt;-- 直接 System.load(ffiPath/libName)，完全不走解压与 native.lock
 *   }
 *   ... 否则才回落到 user.home 解压
 * </pre>
 * 所以只要在 <b>任何 CryptoSuite 被构造之前</b>把 {@code java.library.ffipath} 指向一个
 * 可写目录、并把 jar 内的原生库解压到那里，就能完全避开 {@code ~/.fisco}。
 *
 * <p>本类只做"尽力而为"的引导：若系统已显式设置 {@code java.library.ffipath}（例如运维
 * 用 {@code -Djava.library.ffipath=...} 指定），则完全不干预；若解压失败，也不抛异常，
 * 而是让 SDK 走它自己的默认流程（能成功就成功，不能成功则由上层报出真实原因）。
 */
public final class WdprNativeLibraryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WdprNativeLibraryBootstrap.class);

    /** SDK 的 NativeUtils 优先读取的系统属性名。 */
    public static final String FFI_PATH_PROPERTY = "java.library.ffipath";

    /** jar 内原生库的资源前缀；真实文件名要再拼上 OS 后缀。 */
    private static final String RESOURCE_PREFIX = "WeDPR_dynamic_lib/libffi_java_sdk";

    /** 解压目标目录（相对工作目录，避免写入用户目录）。 */
    private static final String TARGET_DIR_PROPERTY = "chainsip.native.lib.dir";
    private static final String DEFAULT_TARGET_DIR = "sdk/native";

    /** 只引导一次；失败也不再重试（重试不会变好，只会刷日志）。 */
    private static volatile boolean bootstrapDone;

    private WdprNativeLibraryBootstrap() {
    }

    /**
     * 尽力把 {@code java.library.ffipath} 指向工作区内的原生库副本。
     *
     * <p>可以在任何地方重复调用，幂等且绝不抛异常。
     */
    public static void provision() {
        if (bootstrapDone) {
            return;
        }
        synchronized (WdprNativeLibraryBootstrap.class) {
            if (bootstrapDone) {
                return;
            }
            bootstrapDone = true;
            try {
                String existing = System.getProperty(FFI_PATH_PROPERTY);
                if (existing != null && !existing.trim().isEmpty()) {
                    log.debug("已由外部设置 {}={}，不再干预", FFI_PATH_PROPERTY, existing);
                    return;
                }
                Path targetDir = resolveTargetDir();
                String fileName = libraryFileName();
                Path target = targetDir.resolve(fileName);

                if (!Files.exists(target)) {
                    if (!extract(fileName, target)) {
                        log.debug("jar 内未找到原生库资源 {}，交给 SDK 自行处理", fileName);
                        return;
                    }
                }
                // 只有确认文件确实存在才设置属性：NativeUtils 在路径不存在时会回落，
                // 但明确设置好能让日志更干净、也避免它去碰 ~/.fisco。
                System.setProperty(FFI_PATH_PROPERTY, targetDir.toAbsolutePath().toString());
                log.info("FISCO 2.x 原生加密库就绪：{}（{}={}）",
                        target.toAbsolutePath(), FFI_PATH_PROPERTY, targetDir.toAbsolutePath());
            } catch (Throwable t) {
                // 引导失败不能影响应用启动：SDK 自己还有 user.home 这条回落路径。
                log.warn("原生加密库引导未完成（将回落到 SDK 默认解压路径 ~/.fisco/nativeutils）：{}", t.toString());
            }
        }
    }

    /** 原生库文件名，按 SDK 的 EnvironmentUtils.getResourceTailByOs 规则推导。 */
    static String libraryFileName() {
        String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase(Locale.ROOT);
        String arch = String.valueOf(System.getProperty("os.arch", "")).toLowerCase(Locale.ROOT);
        boolean aarch64 = "aarch64".equals(arch) || "arm64".equals(arch);
        if (os.contains("windows")) {
            // Windows 只有 x86 版，没有 _arm 变体
            return "libffi_java_sdk.dll";
        }
        if (os.contains("linux")) {
            return aarch64 ? "libffi_java_sdk_arm.so" : "libffi_java_sdk.so";
        }
        if (os.contains("mac")) {
            return aarch64 ? "libffi_java_sdk_arm.dylib" : "libffi_java_sdk.dylib";
        }
        throw new IllegalStateException("不支持的操作系统：" + os);
    }

    private static Path resolveTargetDir() throws IOException {
        String configured = System.getProperty(TARGET_DIR_PROPERTY);
        Path dir = Paths.get(configured == null || configured.trim().isEmpty()
                ? DEFAULT_TARGET_DIR : configured.trim());
        Files.createDirectories(dir);
        return dir;
    }

    private static boolean extract(String fileName, Path target) throws IOException {
        String resource = "/" + RESOURCE_PREFIX + fileName.substring("libffi_java_sdk".length());
        try (InputStream in = WdprNativeLibraryBootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            Files.createDirectories(target.getParent());
            // 先写临时文件再原子移动：避免多个实例/线程同时解压时读到半个文件
            Path tmp = Files.createTempFile(target.getParent(), "libffi", ".tmp");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        }
    }
}
