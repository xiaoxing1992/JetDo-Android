# JetDo 上架 F-Droid 差距分析

> 对照 https://f-droid.org/zh_Hans/docs/Submitting_to_F-Droid_Quick_Start_Guide/
> 审计对象：本仓库（分支 `feat/migrate-vertical-slice`，HEAD `683f2f8`）
> 审计日期：2026-10-26

## 结论

### 2026-10-26 17:25 复核：还差 **1 个硬阻断 + 5 个工程项**

| 级别 | 项目 | 现状（已核实） | 处置 |
|---|---|---|---|
| 🔴 P0 | 腾讯 TBS/X5 内核 | **仍在**：`gradle/libs.versions.toml:2,67`、`app/build.gradle.kts:181`、`LoginWebViewFactory.kt` 内 **21 处** `com.tencent.*` | **删**（死代码，零功能影响） |
| 🟠 P1 | 改动尚未提交 | `git status` 有 **14 个 `M`** + 4 个未跟踪；HEAD 仍是 `683f2f8` | **先 commit** |
| 🟠 P1 | `v1.0.0` tag 不含 LICENSE | tag → `ae606fb`，LICENSE 在 `683f2f8` 才有 | **重新打 tag** |
| 🟠 P1 | fastlane 元数据缺失 | 无 `fastlane/`、无 `metadata/` | **补齐** |
| 🟡 P2 | `compileSdk = 37` | buildserver 预装只到 `platforms;android-33` / `build-tools;33.0.0`（见 2.3） | 实测或降级 |
| 🟡 P2 | `BUILD_TIME = LocalDateTime.now()` | `app/build.gradle.kts:55`，每次构建都变 | 改为提交时间 |
| 🟡 P2 | `mavenLocal()` | `settings.gradle.kts:16` | 删 |
| 🟡 P2 | 贴吧 / B 站 emoji 授权不明 | `assets/emoji_tieba_087.png`、`emoji_bili_057.png` 等 10 个 | 换 Twemoji 或申报 `NonFreeAssets` |
| 🟡 P2 | `server/__pycache__/` 未忽略 | 工作区 untracked | 加 `.gitignore` |

**已经合规的部分（本次逐项验证）**：

- MIT LICENSE ✅ 文件在仓库根、`blob/feat/migrate-vertical-slice/LICENSE` 公网 200
- Bugly 已彻底移除 ✅ 源码零命中、`proguard-rules.pro` 已清、`strings.xml` 文案已改
- 无 GMS / Firebase / 友盟 / 极光 / 高德 / 百度等闭源 SDK ✅ 全量 grep 零命中
- 无 `.so`、无 `jniLibs`、无入库 `.aar`（仅剩 `.gradle/jugg/jugg-runtime.jar` 本地工具产物，不入库）✅
- 4 个权限全部有明确用途（INTERNET / ACCESS_NETWORK_STATE / POST_NOTIFICATIONS / CAMERA）✅
- `local.properties` 已 gitignore；**构建产物不含任何上报端点/埋点** —— 崩溃现场只写本机日志 ✅
- `versionCode` / `versionName` 在 `defaultConfig` 标准位置 ✅
- 公开 GitHub 仓库 + Issue 跟踪，作者即提交者 ✅

---

## 〇、历史记录：Bugly 处置（已完成）

| 级别 | 项目 | 现状 | 处置 |
|---|---|---|---|
| 🔴 P0 | 腾讯 Bugly 崩溃上报 | ~~`com.tencent.bugly:crashreport:4.1.9.3`，`JetDoApplication.kt:131` 实际调用~~ | ✅ **已删除**（2026-10-26）；后续连 ACRA 也一并移除，最终**不做任何远程上报** |

---

## 一、P0：两个闭源 SDK（不删必被拒）

### 1.1 腾讯 Bugly —— ✅ 已处理（2026-10-26）

> 处置方式：最初选了路径 B（换 FLOSS 替代，接入 ACRA 5.13.1 上报到自建 `/crash`）；
> **2026-10-26 晚进一步整体移除**，改为「崩溃现场只写本机日志」。
>
> 移除理由：ACRA 方案要求把中继域名写进隐私政策、并通过 `BuildConfig` 编进 APK。
> 该域名与作者的其他服务（AI 网关管理面板）同属一个域名区，APK 分发即等于公开基础设施拓扑，
> 同域兄弟子域可被顺带枚举。权衡后放弃远程崩溃监控，换取零上报面。
>
> 副产品：连带的 `POST /crash` 无鉴权、gzip 解压无上限两个 P0 级服务端风险一并消失。
> 保留本节作为决策记录。

F-Droid 政策原文：*"Firebase 和 GMS 是明确不被接受的非 FOSS 库的典型例子"*，判定标准是**闭源 + 远端采集**。Bugly 两者都占：闭源 SDK，崩溃时上传堆栈 + 设备环境到腾讯服务器，还会命中 `AntiFeatures: Tracking`。

**好消息**：项目已经有完整的本地崩溃链路 —— `DebugLogStore`（内存环形缓冲）+ `FileLoggerDaemon`（沙盒 1MB 轮转文件）+ 「设置 → 诊断日志」导出。Bugly 只是第二条腿，删掉不损失功能。

改动清单：

- `app/build.gradle.kts:205` 删 `implementation(libs.bugly.crashreport)`
- `app/build.gradle.kts:57-58` 删 `BUGLY_APP_ID` buildConfigField
- `gradle/libs.versions.toml` 删 `bugly` version + `bugly-crashreport` library
- `app/src/main/kotlin/com/jetdo/android/JetDoApplication.kt` 删 `crashReportInitialized` 字段、`initCrashReportIfAllowed()` 方法、第 72 行调用
- `app/src/main/kotlin/com/jetdo/android/MainActivity.kt:253` 删那句「同意前 Bugly 未初始化」注释；确认同意回调里不再有 `initCrashReportIfAllowed()`（用 `grep -rn initCrashReportIfAllowed` 收尾）
- `app/proguard-rules.pro:52-55` 删 `-keep com.tencent.bugly.**`
- `strings.xml:656` 与 `values-en/strings.xml` 对应行：删掉「应用发生崩溃时，会通过腾讯 Bugly 上报…」整条
- `PrivacyConsentStore.kt:20` 注释里的「含 Bugly 崩溃上报声明」一并改

> 若出于商业考虑必须保留，唯一合法路径是给 F-Droid 单独做一个**不含 Bugly 的 productFlavor**（`foss`），用 `gradle: - foss` 指定。但只要最终 APK 里还有 Bugly 的字节，就过不了。建议直接删。

### 1.2 腾讯 TBS / X5 —— ⚠️ 仍未处理（唯一的硬阻断）

X5 内核闭源、运行时还会从腾讯服务器下载内核二进制 —— 命中「预编译二进制 blob」+「依赖非自由服务」两条。**这个更好办：代码里已经是死代码了。**

已确认（2026-10-26 复核）：`LoginWebViewFactory.kt:27-43` 的 `build()` 无条件走 `SystemLoginWebViewFactory`，X5 分支整段被注释；`X5LoginSupport` **只有文件内自己的 3 处引用**（两行注释 + 定义本身），外部零调用。

现状落点（4 处）：

| 文件 | 位置 | 内容 |
|---|---|---|
| `gradle/libs.versions.toml` | 第 2 行 | `tbs = "44286"` |
| `gradle/libs.versions.toml` | 第 67 行 | `tbssdk = { group = "com.tencent.tbs", ... }` |
| `app/build.gradle.kts` | 第 181 行 | `implementation(libs.tbssdk)` |
| `LoginWebViewFactory.kt` | 21 处 | `configureX5` / `setupX5Clients` / `X5KeyboardProxy` / `X5LoginSupport` 全部 `com.tencent.smtt.*` |

删除清单：

- `app/build.gradle.kts:181` 删 `implementation(libs.tbssdk)`；**第 180 行的 `implementation(libs.androidx.browser)` 也一起删** —— 全项目 grep `androidx.browser` / `CustomTabs` 零调用点，属于无用依赖
- `gradle/libs.versions.toml` 删第 2 行 `tbs` version + 第 67 行 `tbssdk` library
- `LoginWebViewFactory.kt` 删 45-115 行的 X5 分支（`configureX5` / `setupX5Clients` / `X5KeyboardProxy`）、117-153 行的 `X5LoginSupport` object、27-37 行被注释的 X5 调用块；类注释改成「系统 WebView 构造器」；顺带确认 `UserAgentProvider` / `View` 的 import 是否变成未使用
- 收尾：`grep -rn "com.tencent" app/src gradle app/*.kts` 应该零命中

> 删完记得同步删 `app/proguard-rules.pro` 里可能存在的 `com.tencent` keep 规则（当前已无）。

### 1.3 能不能不删？—— 官方允许的三条路

Inclusion Policy 原文：

> The implementation of proprietary tracking or advertising libraries and analytics tools such as Google Play Services and Firebase and Crashlytics and proprietary ad/tracking SDKs are **strictly forbidden in all applications**. Upstream developers **must implement either a FLOSS alternative or a build flavour that does not require these dependencies** when such features become necessary.

所以约束的是**产物**，不是源码。三条合法路径：

| 路径 | 做法 | 代价 | 适用 |
|---|---|---|---|
| A. 直接删 | 删依赖 + 删调用 | 近乎为零（见下） | ✅ 本项目推荐 |
| B. FLOSS 替代 | 换 ACRA（Apache-2.0） | 半天接一次 | ⚠️ 接完又移除了，见 §1.1 |
| C. 单独 flavour | `fossImplementation` / source set 分离 | 每个含闭源 SDK 的点都要抽接口 + 两套实现，且 `versionCode` 需保证不冲突 | ⚠️ 本项目不划算 |

**为什么最终连 B 也放弃**：ACRA 本身合规（Apache-2.0、F-Droid 官方点名推荐过），问题不在库而在**部署**——自建接收端的域名必须写进隐私政策并被编进 APK，等于公开基础设施拓扑。若将来要恢复崩溃监控，正确做法是**中继单独用一个域名区**，不要和任何带管理面板的域名同域。

**为什么 C 不划算**：`JetDoApplication.kt` 的 Bugly 调用、`proguard-rules.pro` 的 keep 规则、`strings.xml` 的隐私文案都得按 source set 切两套，工程复杂度上去了；而收益只是"F-Droid 用户那点量也能在你腾讯后台看到"。TBS 更是完全是死代码，为它单独拆 flavor 毫无意义。

> ⚠️ 常见误解：**AntiFeatures 标记不能豁免闭源库**。AntiFeatures 是给"本身是自由软件、但行为不受欢迎"的应用打的警示标签（政策原文：*"without necessarily disqualifying applications from inclusion"*）。而闭源 SDK 的措辞是 *"strictly forbidden in all applications"* —— 拒绝收录，不是打标放行。早期 F-Droid 确实收过带 `NonFreeDep` 标签的应用，但新应用不再走这条路。

### 1.4 有没有开源 WebView 内核可以「换掉」TBS？（2026-10-26 调研）

**结论：不需要换，也基本没有可换的。**

关键前提：本项目 TBS 已是死代码 —— `build()` 无条件返回 `SystemLoginWebViewFactory`，X5 分支整段被注释。删掉它，用的就是**系统 WebView**，不需要任何替代品。F-Droid 的要求是"产物里不能有闭源 SDK"，不是"必须自带内核"。

GitHub 上按「能不能替代 X5/TBS」筛一遍，只有两类东西：

| 项目 | 类型 | 许可 | 能否替代 TBS | 结论 |
|---|---|---|---|---|
| **GeckoView**（mozilla/geckoview） | 真·独立渲染引擎（Firefox 内核） | MPL-2.0 | ⭕ 唯一可行的开源内核 | 技术上可以，本项目管理上不划算（见下） |
| **WebViewUpgrade**（JonaNorman/WebViewUpgrde） | 换 **provider**，不改引擎 | Apache-2.0 | ❌ 它装的是别人编译好的 WebView APK | F-Droid 不收（那 APK 是 Google/华为的闭源产物） |
| **AgentWeb** / **WebViewPlus** | 系统 WebView 的封装库 | Apache-2.0 | ❌ 只是包装 | 无意义（我们已有 `accompanist.webview`） |
| **Crosswalk** | 曾经的 Chromium 内核 | BSD | ❌ 2017 年停止维护 | 已死 |
| **CEF** / **Ultralight** | 桌面为主 / 商业授权 | BSD / 闭源 | ❌ Android 支持缺失或收费 | 不可用 |

**GeckoView 为什么对本项目不划算**（不是不能做，是代价不对等）：

1. **不是 WebView API**。`WebViewClient` / `WebChromeClient` / `shouldInterceptRequest` / `addJavascriptInterface` 全部不存在，
   要换成 `GeckoSession.ProgressDelegate` / `ContentDelegate` / `ConsoleDelegate` + WebExtensions 消息通道。
   现在 `SystemLoginWebViewFactory` 里那套 6 个回调（onPageFinished / onReceivedHttpError / onReceivedError /
   onConsoleMessage / onLoadResource / dismissKeyboard）要逐个重写。
2. **Cookie 不共享**。项目现在靠 `WebSessionManager.configureCookieAccess` 让 WebView 与 OkHttp 共用 cookie jar；
   GeckoView 有自己的 cookie store，登录成功后得手动读出 session cookie 再注入 OkHttp，链路多一层、多一个坏点。
3. **APK 体积 +40~80MB**（按 ABI 拆可缓解）。
4. **Turnstile 通过率未知**。当初接 X5 的目的是"换个内核看 CF 人机验证表现"，GeckoView 同样是一次全新未知，
   很可能换完发现还是要回到 Custom Tabs。
5. F-Droid 侧：GeckoView 走 `maven.mozilla.org`，MPL-2.0 合规，但会把构建时间拉长不少。

**如果目标其实是「登录页过不了 CF 人机验证」**，更对症的是 Custom Tabs（`androidx.browser`，**当前依赖里已有但零调用点**）：

```kotlin
CustomTabsIntent.Builder().build().launchUrl(context, AppConstants.LOGIN_URL)
```

再配合已有的 `jetdo://` 深链回传 cookie。系统浏览器内核 + 完整 cookie 环境，过盾成功率通常显著高于内嵌 WebView ——
这也正是当初注释里写的那三个候选方向（换内核 / 换承载方式）里成本最低的一个。

---

## 二、P1：会卡审核 / 卡构建

### 2.1 `v1.0.0` 这个 tag 不能用

```
v1.0.0 → ae606fb  2026-10-13 12:22  chore: 首发版本号定为 1.0.0
683f2f8           2026-10-13 13:09  docs: 添加 MIT LICENSE 与徽章墙
```

tag 指向的提交里**没有 LICENSE 文件**。F-Droid 审核第一步就是"到源码库查看许可证文件"，用这个 commit 打包会以 "no license" 被打回。

处置：删 P0 之后重新发一版，把 tag 打在新提交上：

```bash
# versionCode 1 → 2，versionName 1.0.0 → 1.0.1
git tag -d v1.0.0 && git push --delete origin v1.0.0   # 若远端已推
git tag -a v1.0.1 -m "JetDo 1.0.1"
git push origin v1.0.1
```

F-Droid 要求每次 release 都有 tag，且 `AndroidManifest`/`build.gradle` 里的 `versionName` 要与 tag 对得上（`1.0.1` ↔ `v1.0.1`）。

### 2.2 fastlane 元数据（完全没有）

这是 Quick Start 里唯一明确说「上架前应**始终**先添加」的东西。需要新建：

```
fastlane/metadata/android/en-US/short_description.txt      # < 80 字符，不能有句号结尾
fastlane/metadata/android/en-US/full_description.txt
fastlane/metadata/android/en-US/changelogs/2.txt            # 对应 versionCode，< 500 字符
fastlane/metadata/android/en-US/images/icon.png             # 512x512
fastlane/metadata/android/en-US/images/phoneScreenshots/1.png … 8.png
fastlane/metadata/android/zh-CN/…                            # 同名一套中文
```

现成素材：`README.md` 的功能特性段落可直接裁成 full_description；`screenshots/*.png` 是 1212×2616，直接改名搬进 `phoneScreenshots/`（F-Droid 要求长边 ≥ 320，越接近 16:9 越好，这尺寸可用）。图标从 `mipmap-xxxhdpi/ic_launcher.png` 导出 512×512。

> 也可以放在 `metadata/en-US/` 而不用 `fastlane/metadata/android/`，两者等价。**放仓库里**（不是放 fdroiddata），这样以后改文案/翻译不用提 MR。

### 2.3 `compileSdk = 37` 是构建风险（已核实 buildserver 实际清单）

**核实结果**（读 `fdroidserver/buildserver/provision-android-sdk` 原始脚本，2026-10-26）：

- 元数据里**确实没有** `sdks` 字段，也没有 `androidbuildtoolsversion` 字段 —— 与 SDK 相关的只有 `target`（覆盖编译 target）和 `oldsdkloc`。
- buildserver **预装**的上限是 `platforms;android-33` + `build-tools;33.0.0`（`android-10` ~ `android-33` 逐个列出）。
- **但**脚本末尾把 `platforms/`、`build-tools/`、`extras/m2repository` 设为 `vagrant` 组可写：
  ```bash
  # allow gradle to install newer build-tools and platforms
  mkdir -p $ANDROID_HOME/{build-tools,platforms}
  chgrp vagrant $ANDROID_HOME/{build-tools,platforms}
  chmod g+w $ANDROID_HOME/{build-tools,platforms}
  ```
  并且 `$ANDROID_HOME/licenses/` 里已写入 `android-sdk-license` / `android-sdk-preview-license` 的 hash。
  也就是说 **AGP 在构建期可以自行 sdkmanager 下载 `platforms;android-37`**（buildserver 构建阶段有网络）。

**所以真实风险等级：中，不是必挂。** 但有三个连带风险：

1. AGP 9.3.1 + Gradle 9.5.0 + Kotlin 2.4.10 全踩在最新线上，buildserver 的 JDK（Debian trixie 默认 OpenJDK 21）跟不上就直接失败；
2. 构建期临时拉 SDK 会拉长单次构建时间，buildserver 有超时限制；
3. `fdroid lint` / 审阅者会质疑「首次构建即联网拉 SDK」。

处置建议（按顺序试）：

1. 先照现状提一次，看 GitLab CI 的试构建结论——这是最快的判据；
2. 若因 `android-37` 失败，把 `compileSdk` 降到 36 或 35（`targetSdk` 可保持 37，AGP 允许 `compileSdk < targetSdk`，只告警）；
3. 若因 JDK 失败，在元数据里用 `sudo:` 装 JDK，或用 `init:` 写 `gradle.properties` 指定 `org.gradle.java.home`。

顺带：`minSdk 26` 没问题，会直接显示为「Android 8.0+」。

---

## 三、P2：建议项

### 3.1 仓库配置

`settings.gradle.kts:17` 的 `mavenLocal()` 删掉 —— F-Droid 构建机没有 `~/.m2`，留着只会拖慢解析。

阿里云镜像本身不算违规（是 google/mavenCentral 的镜像），但 `google()` 和 `mavenCentral()` 已经在列表里（第 8-9 行、21-22 行），建议把官方源提到前面、镜像作为 fallback，这样审核人员本地复现时路径和你一致。**如果官网被墙导致你本地编不过，保持现状也行**，只要保证官方源可达即可。

### 3.2 `BUILD_TIME` 会破坏可复现构建

```kotlin
buildConfigField("String", "BUILD_TIME", "\"${LocalDateTime.now()}\"")
```

每次配置阶段取当前时间 → 同一个 commit 每次构建产物都不同 → 无法做 Reproducible Builds（F-Droid 强烈推荐，尤其新应用）。日志页要展示构建时间的话，改成从提交时间派生：

```kotlin
val gitCommitTime = providers.exec {
    commandLine("git", "log", "-1", "--format=%cd", "--date=iso")
}.standardOutput.asText.map { it.trim() }.orElse("unknown")
buildConfigField("String", "BUILD_TIME", "\"${gitCommitTime.get()}\"")
```

（`providers.exec` 是配置缓存友好的写法，AGP 9 + Gradle 9 下不会报。）或者干脆删掉这个字段。

### 3.3 二进制/生成资产

`app/src/main/assets/` 下三个文件会被审核人员问来源：

| 文件 | 大小 | 来源 | 许可 |
|---|---|---|---|
| `markdown/markdown-it.min.js` | 122 KB | markdown-it | MIT |
| `mathjax/tex-svg-full.js` | 2.2 MB | MathJax 预构建 bundle | Apache-2.0 |
| `emoji_*.png`（10 个） | — | 贴吧 / B 站表情 | ⚠️ 来源与授权不明确 |

前两个加个 `NOTICE`/`README` 说明版本号和许可即可。**表情包是真正的风险点** —— 贴吧/B 站 emoji 属于平台衍生素材，F-Droid 有 `NonFreeAssets` 这条负面特征。要么换成自绘/开源表情（如 Twemoji，CC-BY 4.0），要么在 fdroiddata 里申报 `AntiFeatures: NonFreeAssets`（会被打标但不一定拒）。建议换成 Twemoji 一劳永逸。

`linuxdo_tag_icons.json` 是站点标签图标 URL 清单，不含素材本身，没问题。

### 3.4 其他小项

- **applicationId `com.jetdo.android`**：F-Droid 倾向于开发者拥有对应域名的反向 ID。如果你有 `jetdo.com`（或对应 GitHub Pages），在 fdroiddata MR 描述里说明一下；没有的话大概率也会过，但可能被问。
- **签名差异**：F-Droid 用自己的密钥签名。现有 GitHub Releases 用户**不能覆盖安装**，必须卸载重装，数据全丢。上架前在 README 里说明，或者考虑把 F-Droid 签名作为唯一正式签名。
- **`server/relay.py`**：服务端代码（FastAPI + firebase_admin），与 APK 无关，不影响审核。但目前 `relay-config.json` 被 gitignore、`relay-config.example.json` 也没被任何 Kotlin 代码读取 —— 属于未接线的半成品，上架前建议删掉或在 README 标 "WIP"，免得审核人员误以为 APK 依赖你的私有中继服务。
- **深链 `jetdo://` + `discourse://auth_redirect`**：自定义 scheme 抢注 `discourse://` 可能与 DiscourseHub 冲突（你注释里已提到），不算违规。

---

## 四、fdroiddata 元数据（可直接用的模板）

文件路径：`fdroiddata/metadata/com.jetdo.android.yml`

```yaml
Categories:
  - Internet
License: MIT
AuthorName: xiaoxing1992
SourceCode: https://github.com/xiaoxing1992/JetDo-Android
IssueTracker: https://github.com/xiaoxing1992/JetDo-Android/issues
Changelog: https://github.com/xiaoxing1992/JetDo-Android/releases

AutoName: JetDo

RepoType: git
Repo: https://github.com/xiaoxing1992/JetDo-Android.git

Builds:
  - versionName: 1.0.1
    versionCode: 2
    commit: <完整 40 位 commit hash>   # 官方要求用齐全 hash，别用 tag 名
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.1
CurrentVersionCode: 2
```

要点：
- `commit` 官方明确要求用完整 hash；
- `gradle: - yes` 表示无 flavor，跑 `assembleRelease`；
- `UpdateCheckMode: Tags` 依赖你每次 release 打 tag，正则默认匹配 `v1.0.1` 这类；
- **不要**写 `sdks` / `androidbuildtoolsversion`，字段不存在。

提交流程：fork `gitlab.com/fdroid/fdroiddata` → 新分支 `com.jetdo.android` → 加上述文件 → `git commit -m "New App: com.jetdo.android"` → 推到自己的 fork（GitLab CI 会自动 lint + 试构建）→ 提 MR 并打 **`New App`** 标签。

---

## 五、执行顺序（2026-10-26 更新）

| # | 动作 | 状态 |
|---|---|---|
| 1 | 删 Bugly（后续连 ACRA 一并移除，最终零远程上报） | ✅ 已完成 |
| 2 | **删 TBS / X5**（4 处落点）→ `./gradlew assembleDebug` 确认通过 | ⬜ 待做 |
| 3 | 改 `BUILD_TIME` 为提交时间、删 `mavenLocal()`、`.gitignore` 加 `__pycache__/` | ⬜ 待做 |
| 4 | `./gradlew assembleRelease` → `unzip -l app-release.apk \| grep -iE "tencent\|acra"` 确认 tencent 零残留 | ⬜ 待做 |
| 5 | `git add` + commit（当前 12 M + 4 未跟踪） | ⬜ 待做 |
| 6 | 补 `fastlane/metadata/android/{en-US,zh-CN}/`（短描述 / 长描述 / changelog / icon 512 / 截图 6 张） | ⬜ 待做 |
| 7 | 版本号 bump 到 `versionCode 2 / versionName 1.0.1`，提交，重打 `v1.0.1` tag（**必须打在新提交上**） | ⬜ 待做 |
| 8 | 提 fdroiddata MR（`New App` 标签），看 CI 试构建对 `compileSdk 37` 的裁决 | ⬜ 待做 |
| 9 | 合并后 24–48h 出现在主仓库 | ⬜ 待做 |

> 注意第 7 步：`v1.0.0` 指向的 `ae606fb` 没有 LICENSE，**不能复用**。要么删掉重打，要么直接发 `v1.0.1`。
> `UpdateCheckMode: Tags` 会让 F-Droid 抓「最靠后的 tag」，旧 tag 留着不影响新版本，但会留个坏样本，建议一并删。

---

## 六、上架前自检命令

```bash
# 1. 闭源 SDK 零残留（源码层）
grep -rn "com\.tencent\|com\.google\.android\.gms\|com\.google\.firebase" \
     app/src gradle app/build.gradle.kts | grep -v "/build/"   # 期望：无输出

# 2. 产物层零残留
./gradlew assembleRelease
unzip -l app/build/outputs/apk/release/app-release-unsigned.apk \
  | grep -iE "tencent|bugly|smtt"                              # 期望：无输出

# 3. 权限数量（期望 4 条，且每条都有用途）
grep -c "uses-permission" app/src/main/AndroidManifest.xml

# 4. 无原生产物
find app/src -name "*.so" -o -name "*.aar" -o -name "*.jar"    # 期望：无输出

# 5. 产物里不应出现任何上报/统计组件
#    （期望：acra / bugly / tencent / firebase / analytics 全部无输出）
unzip -l app/build/outputs/apk/release/*.apk | grep -iE "acra|bugly|tencent|smtt|firebase|analytics"
# 期望：无输出
```
