// 仅编译桩，真实实现来自 CI 产出的 gomobile AAR（app/libs/*.aar 存在时本目录不参与编译）。
// 签名与 gomobile bind 生成的 Java 接口一致（源：tmp/alist-lib/alistlib/server.go）。
package alistlib

/** Go 内核日志回调：level 为 logrus 级别（0..6），time 为 UnixMilli。 */
interface LogCallback {
    fun onLog(level: Short, time: Long, message: String)
}
