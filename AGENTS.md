# AGENTS.md — AI 代理协作指南

## 项目简介

OpenList for Android：把 [OpenList](https://github.com/OpenListTeam/OpenList) 文件服务器跑在 Android 设备上的原生客户端。Kotlin + Jetpack Compose 实现 UI 与前台服务，Go 内核经 gomobile 编译为 AAR 内嵌 APK，另集成 EasyTier（Rust JNI，no-tun 模式）提供免 VPN 内网映射。

## 技术栈与结构

- Kotlin 2.2.0 + Jetpack Compose（BOM 2025.06.01）/ Material3、Hilt 2.56.2（KSP）、Navigation Compose、DataStore、kotlinx.serialization；AGP 8.10.1、JDK 17、compileSdk 36 / minSdk 21；Go（版本见 `alist-lib/go.mod`）与 Rust 仅由 CI 使用
- `app/` 应用主体，包名 `com.xaxka.openlist`：
  - `bridge/` Go AAR 绑定封装（OpenListEngine / CoreEngine）
  - `service/` OpenList 前台服务与服务器状态机（ServerManager / ServerState）
  - `easytier/` EasyTier 实例管理、状态解析与自愈（`service/EasyTierService.kt` 为独立前台服务）
  - `ui/` main / settings / web / theme；`data/` 日志缓冲与偏好；`system/` 磁贴、快捷方式、SAF；`net/` TrafficStats 流量统计
  - `src/stub/kotlin` 无 Go AAR 时的 alistlib 编译桩（`app/libs` 缺 AAR 时自动启用）
- `alist-lib/` Go 内核绑定层：`alistlib/*.go` 桥接代码 + `scripts/`（init_alist.sh → init_web.sh → init_gomobile.sh → gobind.sh）
- `.github/workflows/build.yml`、`.github/scripts/build-easytier-jni.sh` 为全部 CI

## 构建与 CI（仅作参考，本地禁止执行）

- workflow：`.github/workflows/build.yml`；push 到 `main`（忽略 `*.md`、`_docs/**`）、PR、手动触发
- `easytier-jni` job：在固定 commit 的 EasyTier 仓库内用 cargo-ndk 分 ABI 交叉编译 `libeasytier_android_jni.so`（Rust 1.95 + NDK r26），产物按内容寻址缓存
- `beta` job：`alist-lib/scripts/` 依次执行 init_alist.sh beta、init_web.sh、init_gomobile.sh、gobind.sh 产出 Go AAR → 下载 EasyTier .so 注入 `app/src/main/jniLibs` → `./gradlew :app:assembleRelease --no-daemon` 签名并按 ABI 分包 → 发布到 `dev` Release
- 改动是否可用以 GitHub CI 编译通过为准

## 硬性工作规则

- 禁止本地编译：不要在本地运行任何构建/编译/测试命令（gradlew、go、cargo、cmake 等）；改动是否可用以 GitHub CI 编译通过为准
- 每完成一个改动立即 commit 并 push，再进行下一项改动
- 所有提交使用 xaxka 身份（本 clone 已配置 user.name=xaxka，user.email=73456104+xaxka@users.noreply.github.com，不要改动）
- 任务结束后清理本地 clone

## 代码约定

- Kotlin 为主；注释与 UI 文案用中文，用户可见文案集中在 `ui/main/Strings.kt` 并注明来源（如 `intl_zh.arb: xxx`、源 .dart 文件行号）
- Gradle 脚本内含大量解释设计意图的中文注释，修改时保持该风格
- 依赖注入用 Hilt（`@AndroidEntryPoint` + `@Inject`），状态用 StateFlow + `collectAsStateWithLifecycle`
- 常量大写 SNAKE_CASE，各类在 companion object 中定义 `TAG`；应用内日志走 `data/log/`（LogBuffer / ServerLog）
- 资源名带 `openlist_` 前缀；提交信息为中文 Conventional Commits（如 `feat: EasyTier 独立于 OpenList 服务启停`）

## 关键文档/敏感区

- 动手前先读 `README.md`（功能、下载与发布机制、内核打包流程、项目结构）
- `app/libs/`（Go AAR）与 `app/src/main/jniLibs/`（EasyTier .so）均为 CI 产物、不入库：本地缺失时功能自动降级或启用 stub，不要手工放置或提交二进制
- 改 `alist-lib` 的 Go 绑定 API 时必须同步 `app/src/stub/kotlin` 编译桩，否则无 AAR 环境下 CI 编译失败
- `app/build.gradle.kts` 的版本号（日期制 versionName/versionCode）、签名、ABI splits 逻辑敏感：debug 签名固定用入库的 `app/debug.keystore`，release 签名读 `local.properties`（严禁提交该文件）
- CI 缓存 key 依赖 EasyTier 固定 commit 与 `hashFiles('alist-lib/alistlib/**/*.go', 'alist-lib/scripts/gobind.sh')`，改 Go 绑定或 gobind.sh 时留意缓存命中与产物一致性
- 纯文档改动（`*.md`、`_docs/**`）不会触发 CI 构建，无编译验证
