package com.easytier.jni

/**
 * EasyTier JNI 绑定（vendored from EasyTier easytier-contrib/easytier-android-jni，main 分支）。
 *
 * 包名/类名必须与原生库导出符号保持一致（Java_com_easytier_jni_EasyTierJNI_*），不可重命名；
 * R8 规则见 proguard-rules.pro。
 *
 * 与上游差异：库加载改为懒加载并捕获 UnsatisfiedLinkError——本地开发机若无 CI 产出的
 * libeasytier_android_jni.so，应用整体仍可运行，EasyTier 功能自动降级为不可用。
 */
object EasyTierJNI {

    private data class LoadResult(val success: Boolean, val error: String?)

    private val loadResult: LoadResult by lazy {
        try {
            System.loadLibrary("easytier_android_jni")
            LoadResult(true, null)
        } catch (e: Throwable) {
            LoadResult(false, "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 原生库是否成功加载（CI 产物缺失时为 false，调用方需先检查）。 */
    val isAvailable: Boolean
        get() = loadResult.success

    /** 加载失败原因（库可用时为 null）。 */
    val loadError: String?
        get() = loadResult.error

    /**
     * 解析配置字符串
     * @param config TOML 格式的配置字符串
     * @return 0 表示成功，-1 表示失败
     * @throws RuntimeException 当配置解析失败时抛出异常
     */
    @JvmStatic external fun parseConfig(config: String): Int

    /**
     * 运行网络实例
     * @param config TOML 格式的配置字符串
     * @return 0 表示成功，-1 表示失败
     * @throws RuntimeException 当实例启动失败时抛出异常
     */
    @JvmStatic external fun runNetworkInstance(config: String): Int

    /**
     * 保留指定的网络实例，停止其他实例
     * @param instanceNames 要保留的实例名称数组，传入 null 或空数组将停止所有实例
     * @return 0 表示成功，-1 表示失败
     * @throws RuntimeException 当操作失败时抛出异常
     */
    @JvmStatic external fun retainNetworkInstance(instanceNames: Array<String>?): Int

    /**
     * 收集网络信息
     * @param maxLength 最大返回条目数
     * @return NetworkInstanceRunningInfoMap 的 JSON 字符串（proto3 JSON，snake_case 字段名）
     * @throws RuntimeException 当操作失败时抛出异常
     */
    @JvmStatic external fun collectNetworkInfos(maxLength: Int): String?

    /**
     * 列出当前运行的实例名称和实例 ID。
     * @param maxLength 最大返回条目数
     * @return JSON 对象，key 为 instance name，value 为 instance id
     */
    @JvmStatic external fun listInstances(maxLength: Int): String?

    /**
     * 获取最后的错误消息
     * @return 错误消息字符串，如果没有错误则返回 null
     */
    @JvmStatic external fun getLastError(): String?

    /**
     * 便利方法：停止所有网络实例
     * @return 0 表示成功，-1 表示失败
     */
    @JvmStatic
    fun stopAllInstances(): Int {
        return retainNetworkInstance(null)
    }

    /**
     * 便利方法：停止指定实例外的所有实例
     * @param instanceName 要保留的实例名称
     * @return 0 表示成功，-1 表示失败
     */
    @JvmStatic
    fun retainSingleInstance(instanceName: String): Int {
        return retainNetworkInstance(arrayOf(instanceName))
    }
}
