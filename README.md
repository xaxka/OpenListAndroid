# OpenList for Android

把 [OpenList](https://github.com/OpenListTeam/OpenList) 文件服务器跑在 Android 手机上，让设备随身的存储变成一台便携文件服务器。Kotlin + Jetpack Compose 原生实现；Go 内核经 gomobile 编译为 AAR 内嵌 APK。

## 功能

- **一键启停服务器**：前台服务常驻（specialUse，规避 Android 15+ 前台服务时长限制），通知栏 / 快速设置磁贴 / 桌面快捷方式均可控制
- **内置网页**：底部导航 WebView 直开 OpenList Web 管理页，服务启动后可自动跳转
- **内网映射（EasyTier）**：no-tun 模式、不申请 VPN，把本机端口（默认 5244）映射进 EasyTier 虚拟局域网供组网设备访问；支持对等节点、网络密钥、QUIC 代理；内置后台冻结/清理自愈与端口转发动态对账
- **视频洗码**：指定目录批量扫描/还原，WorkManager 后台执行
- **其他**：开机自启、保持唤醒、深色模式/动态配色、数据目录自定义

## 下载

每次推送 `main` 后由 GitHub Actions 编译并发布到 [dev Release](https://github.com/xaxka/OpenListAndroid/releases/tag/dev)：

- 按 ABI 分包：`arm64-v8a` / `armeabi-v7a` / `x86_64`
- 版本号日期制 `yy.MM.dd`，OpenList 内核跟随上游 `beta` 分支
- debug 签名已固定入库（`app/debug.keystore`），dev 包之间可直接覆盖安装

## 本地构建

需要 JDK 17 与 Android SDK。

```bash
./gradlew :app:assembleDebug    # 或 :app:assembleRelease
```

- `app/libs` 无 Go AAR 时自动启用 alistlib 编译桩，无 Go 环境也能完整编译（运行服务器功能需要真实 AAR）
- 自行打包内核：安装 Go（版本见 `alist-lib/go.mod`）与 NDK，依次执行 `alist-lib/scripts/` 下 `init_alist.sh` → `init_web.sh` → `init_gomobile.sh` → `gobind.sh`，产物落入 `app/libs`
- EasyTier 原生库（Rust 交叉编译）由 CI 产出并注入 `app/src/main/jniLibs`，本地缺失时内网映射功能自动降级

## 项目结构

```
app/                 应用主体（Compose UI / 前台服务）
├─ easytier/         EasyTier 实例管理、状态解析与自愈
├─ service/          OpenList 前台服务与状态机
├─ ui/               主页 / 设置 / 导航
└─ video/            视频洗码
alist-lib/           Go 内核绑定层（gomobile 脚本 + alistlib 桥接代码）
.github/workflows/   CI：EasyTier JNI（Rust）+ Go AAR 内容寻址缓存 + 签名分包发布
```

## 致谢

- [OpenListTeam/OpenList](https://github.com/OpenListTeam/OpenList)（AGPL-3.0）— 文件服务器内核
- [EasyTier/EasyTier](https://github.com/EasyTier/EasyTier)（LGPL-3.0）— 虚拟局域网

## 许可证

因内嵌 OpenList 内核，本项目遵循 AGPL-3.0。
