// 仅编译桩，真实实现来自 CI 产出的 gomobile AAR（app/libs/*.aar 存在时本目录不参与编译）。
// 签名与 gomobile bind 生成物一致（源：tmp/alist-lib/alistlib/{server,common,settings}.go）：
// Go 首字母大写导出 → Java 小写开头静态方法；error 返回值 → checked Exception。
package alistlib

object Alistlib {
    @JvmStatic
    fun setConfigData(path: String) {
    }

    @JvmStatic
    fun setConfigLogStd(b: Boolean) {
    }

    @JvmStatic
    fun setConfigDebug(b: Boolean) {
    }

    @JvmStatic
    fun setConfigNoPrefix(b: Boolean) {
    }

    @JvmStatic
    fun init(e: Event, cb: LogCallback) {
    }

    @JvmStatic
    fun start() {
    }

    @JvmStatic
    fun shutdown(timeout: Long) {
    }

    @JvmStatic
    fun isRunning(t: String): Boolean = false

    @JvmStatic
    fun setAdminPassword(pwd: String) {
    }

    @JvmStatic
    fun getOutboundIPString(): String = "localhost"

    @JvmStatic
    fun getOutboundIP(): ByteArray = ByteArray(0)
}
