# 本子天国 (JMReader)

> 干净、无广告的第三方漫画阅读客户端，仅作为访问接口的工具，不提供任何内容资源。

一个基于 Jetpack Compose + Material 3 的现代 Android 漫画阅读器，专为流畅阅读体验而设计。支持直连与后端两种模式，离线下载、内容过滤、多域名测速切换等实用功能。

---

## ⚖️ 免责声明

**本项目仅供个人学习、技术交流和研究使用。**

1. 本项目为第三方开源客户端，不提供任何漫画内容资源，仅作为访问接口的工具。
2. 项目所有使用者必须遵守所在国家/地区的法律法规。
3. 本项目不鼓励、不支持任何侵犯版权的行为。请尊重原作者和版权方的合法权益，支持正版内容。
4. 项目作者不承担任何因使用本软件产生的法律责任、账号封禁、数据丢失或其他任何形式的后果。
5. 未成年人禁止使用本软件。
6. 使用本软件即表示您已阅读并同意本声明。如不同意，请立即停止使用并删除本软件。

**珍爱生命，爱护身体，理性使用，远离风险。**

首次启动 App 会弹出上述声明，需阅读 5 秒后方可继续。也可在「设置 → 关于」随时查看。

---

## ✨ 功能特性

### 阅读体验
- 📖 **双阅读模式**：上下滚动 / 左右翻页，可在设置中切换
- 🎯 **章节恢复**：自动记忆上次阅读位置（漫画/章节/页码）
- 🔢 **页码指示器 + 跳页滑块**：长文档快速定位
- 🖼️ **图片缩放**：基于 Telephoto 的双指缩放与拖拽
- ⚡ **高刷新率锁定**：自动启用屏幕最高刷新率（Android 11+），列表滚动更流畅

### 内容管理
- 🔍 **多维度搜索**：关键词搜索（按最新/观看/评论/图片数排序）、按标签搜索、按作者搜索
- 📚 **收藏 / 历史**：本地收藏夹 + 浏览历史
- ⬇️ **离线下载**：整本下载，断点续传，原子落盘避免半成品文件，下载完整性校验
- 🚫 **内容过滤**：屏蔽 Tag / 屏蔽作者 / 屏蔽名称关键词（繁简归一化匹配）

### 网络与连接
- 🌐 **直连模式**（默认）：App 直接访问 API，单机即可使用，无需部署后端
- 🛠️ **后端模式**（可选）：填后端地址走 Python `jmcomic` 库，适合需要服务端整本下载、Cookie 共享等场景
- 🔄 **多域名测速切换**：内置域名池，支持测速选最优、自动拉取最新域名、添加自定义域名
- 🔐 **账号登录**：可选登录以同步收藏/历史（登录防重复点击 + 错误反馈）

### UI 与个性化
- 🎨 **Material 3 + 动态取色**：跟随壁纸生成配色（Android 12+）
- 🌗 **三主题**：跟随系统 / 浅色 / 深色
- 📱 **边缘到边缘**：适配全面屏

### 其他
- 🧭 **六大主页面**：首页（最新/排行）/ 搜索 / 收藏 / 下载 / 讨论区 / 设置
- 🔎 **图片搜图**：集成 Saucenao 反向图片搜索（原图上传 + WebView 混合方案）
- 📋 **日志诊断**：内置日志查看页，便于排查问题
- 🛡️ **首次启动免责声明**：5 秒强制阅读 + 持久化

---

## 📱 运行环境

| 项目 | 要求 |
|------|------|
| 最低 Android 版本 | Android 7.0 (API 24) |
| 目标 Android 版本 | Android 15 (API 36) |
| 架构 | ARM64 / ARM32 / x86_64 |

---

## 🛠️ 技术栈

- **语言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **架构**：MVVM + ViewModel + StateFlow
- **网络**：OkHttp + Retrofit + Moshi
- **图片**：Coil + Telephoto（缩放）
- **存储**：DataStore Preferences（设置）+ 文件系统（下载）
- **HTML 解析**：Jsoup
- **导航**：Navigation Compose
- **构建**：Gradle 8.10+ / AGP 8.7+ / Kotlin 2.0+

---

## 🚀 从源码构建

### 环境要求
- JDK 17
- Android SDK（compileSdk 36，build-tools 36.0.0）
- Gradle 8.10+（项目自带 gradlew）

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/<你的用户名>/JMReader.git
cd JMReader

# 2. 配置 SDK 路径（任选其一）
#    方式 A：环境变量
export ANDROID_HOME=/path/to/your/Android/Sdk
#    方式 B：local.properties（首次构建自动生成，或手动创建）
echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties

# 3. 生成 release 签名（仅打正式包需要，调试包可跳过）
keytool -genkeypair -v \
    -keystore release.keystore \
    -alias jmreader \
    -keyalg RSA -keysize 2048 \
    -validity 10000 \
    -storepass <你的密码> -keypass <你的密码> \
    -dname "CN=JMReader, OU=Dev, O=Personal, L=Shanghai, ST=Shanghai, C=CN"
# 项目默认期望 release.keystore 在项目根目录，alias=jmreader
# 如使用不同的密码/别名，请修改 app/build.gradle.kts 中 signingConfigs.release

# 4. 构建 Debug APK
./gradlew :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# 5. 构建 Release APK（含 R8 混淆 + 资源压缩）
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

---

## 🐍 后端（可选）

`backend/` 目录是一个基于 [jmcomic](https://github.com/hect0x7/JMComic-Crawler-Python) 的 Python 后端示例，提供直连模式不具备的能力（整本下载、Cookie 共享等）。

```bash
cd backend
pip install -r requirements.txt
python main.py
```

详见 [backend/main.py](backend/main.py) 与 [backend/option.yaml](backend/option.yaml)。

> 绝大多数用户无需部署后端，App 默认直连模式即可使用全部基础功能。

---

## 📂 项目结构

```
JMReader/
├── app/
│   └── src/main/java/com/jmreader/
│       ├── MainActivity.kt              # 入口，splash + 免责声明 + 主题
│       ├── core/                        # 日志、加解密等基础设施
│       ├── data/
│       │   ├── api/                     # 网络层（直连 client + Saucenao）
│       │   ├── download/                # 下载管理器
│       │   ├── local/                   # DataStore + 本地存储
│       │   ├── repository/              # 仓库层（Resource 模式）
│       │   └── AppContainer.kt          # 依赖注入容器
│       └── ui/
│           ├── components/              # 通用组件 + 免责声明对话框
│           ├── nav/                     # 导航
│           ├── theme/                   # Material 3 主题
│           └── screen/                  # 各页面（detail/downloads/favorites/
│                                       #         forum/home/imagesearch/logs/
│                                       #         reader/search/settings）
├── backend/                            # 可选 Python 后端
├── build.gradle.kts                    # 项目级构建
├── settings.gradle.kts                 # 仓库镜像配置
└── gradle/libs.versions.toml           # 依赖版本目录
```

---

## 🤝 贡献

欢迎通过 Issue 反馈 bug 或提交 PR。提交前请：
1. 确认 bug 可复现
2. 附上日志（App 内「设置 → 关于 → 查看日志 / 诊断」可导出）
3. 说明你的设备型号、Android 版本、App 版本

---

## 📄 许可证

[MIT License](LICENSE) — 仅供学习交流，不提供任何内容资源，使用者需遵守当地法律法规。

---

## ⚠️ 重要提醒

- 本项目**不提供任何漫画内容资源**，仅作为访问接口的工具。
- 使用前请确认你所在国家/地区的法律法规允许此类工具的使用。
- 请支持正版，尊重版权方权益。
- 项目作者不对使用本软件产生的任何后果承担责任。
