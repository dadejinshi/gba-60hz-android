# GBA 60Hz · Android 版

把现有的 **单文件 HTML + WASM 内核** GBA 模拟器，做成可安装、可上架的原生 Android App。
**内核与全部 UI 一行不改**，100% 复用现有资产，只补一层原生壳与能力桥接。

## 方案一句话

Kotlin 原生壳 + WebView + WebViewAssetLoader（把 assets 映射到 https origin）+ JS↔原生桥接，
补齐文件导入、分享、震动、全屏、返回键、后台生命周期等 WebView 缺失的原生能力。

详细论证见 [`docs/01-架构与方案选型.md`](docs/01-架构与方案选型.md)。

## 目录

```
GBA-Android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/gba60hz/app/
│       │   ├── MainActivity.kt     ← WebView + 资源拦截 + SAF + 全屏 + 返回键
│       │   └── GbaBridge.kt        ← JS 桥接：震动/分享/保存
│       ├── assets/GBA-60Hz.html    ← 单文件成品（含内联 wasm）
│       └── res/                    ← 主题/图标/备份规则
├── .github/workflows/build-apk.yml ← GitHub Actions 云端出包（手动/推送触发）
├── tools/sync_assets.sh            ← 同步最新 web 成品到 assets
├── tools/push_to_github.sh         ← 一键推送到 GitHub
└── docs/
    ├── 01-架构与方案选型.md
    ├── 02-技术难题解决方案.md      ★ 15 个真实难题的成因与方案
    └── 03-构建签名与上架.md
```

## 快速开始

### 方式 A：GitHub 云端出包（无需本地 SDK，推荐）

工程已配置 GitHub Actions，push 后在网页即可下载 APK：

1. 把工程推到 GitHub（见下方「上传到 GitHub」）。
2. 打开仓库 **Actions** 标签 → 左侧选 **Build GBA 60Hz APK**。
3. 点 **Run workflow** → 可选填版本号 → 确认。
4. 约 3–6 分钟后跑完，点进这次运行，在 **Artifacts** 下载 `GBA-60Hz-APK`，
   解压即得 `GBA-60Hz-v1.6.0.apk`，传到手机安装即可。

也支持 push 到 `main` 或打 `v*` 标签时自动出包。ART 工作流见
[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)。

### 方式 B：本地 Android Studio 构建

1. 用 **Android Studio** 打开 `GBA-Android/` 目录。
2. 等待 Gradle 同步（首次会下载 Gradle 8.9 / SDK 35）。
3. 连接真机，点 **Run 'app'**。

命令行：
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 上传到 GitHub

方式一（推荐，在有 `gh` 登录的机器上一条命令）：
```bash
REPO=<你的用户名>/gba-60hz-android bash tools/push_to_github.sh
```

方式二（任意机器）：
```bash
git remote add origin https://github.com/<你的用户名>/gba-60hz-android.git
git branch -M main
git push -u origin main
```

> 仓库需为**公开**，GitHub Actions 免费额度才可正常出包（私有仓库也可，但按量计费）。
> 推送后到 Actions 页手动 Run 一次即可拿 APK。

## 更新内容

改了网页版前端后：
```bash
cd GBA-60Hz/web && python3 build.py
cd ../../GBA-Android && bash tools/sync_assets.sh
```

## 必读文档

- **想理解为什么这么做** → `docs/01`
- **遇到具体 bug（白屏/无声/存档/导入/全屏/返回键…）** → `docs/02` 按表定位
- **要打包上架** → `docs/03`（签名、AAB、Play 合规、真机验证清单）

## 状态说明

- 项目代码按 Android Studio 标准结构编写，**15 项技术难题均已在代码中落地**。
- 生成环境无 Android SDK，因此 APK 需在装有 SDK 的 Android Studio / CI 中构建验证。
- 内核（WASM）与所有 UI/前端逻辑零改动——本工程是"壳 + 桥接"，不是重写。

## 版本

| 项 | 值 |
|---|---|
| 应用版本 | 1.6.0（versionCode 1） |
| 前端成品 | GBA 60Hz Web v1.6（350KB 单文件） |
| minSdk / targetSdk | 24 / 35 |
| 内核 | 原 WASM（84KB），未改动 |
