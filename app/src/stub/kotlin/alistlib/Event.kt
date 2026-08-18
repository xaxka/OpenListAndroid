// 仅编译桩，真实实现来自 CI 产出的 gomobile AAR（app/libs/*.aar 存在时本目录不参与编译）。
// 签名与 gomobile bind 生成的 Java 接口一致（源：tmp/alist-lib/alistlib/server.go）。
package alistlib

/** 引擎生命周期事件回调，由 Go 内核在服务启停/异常时触发。 */
interface Event {
    fun onStartError(t: String, err: String)

    fun onShutdown(t: String)

    fun onProcessExit(code: Long)
}
