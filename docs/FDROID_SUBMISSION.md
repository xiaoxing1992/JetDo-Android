# JetDo 上架 F-Droid 差距分析

> 对照 https://f-droid.org/zh_Hans/docs/Submitting_to_F-Droid_Quick_Start_Guide/
> 审计对象：本仓库（分支 `feat/migrate-vertical-slice`）
> 审计日期：2026-10-26 / **最后复核 2026-10-27 14:40**

## 结论

### 2026-10-27 14:40 复核：闭源 SDK 已清零且**已推送**，还差 **1 个 P1 + 5 个 P2**

| 级别 | 项目 | 现状（已实测核实） | 处置 |
|---|---|---|---|
| ✅ P0 | 腾讯 TBS/X5 内核 | **已删净并已推送**：`libs.versions.toml` / `build.gradle.kts` / `LoginWebViewFactory.kt` 三处清理完毕，产物 `com/tencent/smtt` = **0** | 已完成 |
| ✅ P1 | fastlane 元数据缺失 | **已补齐**：`fastlane/metadata/android/{en-US,zh-CN}/` 含短描述、完整描述、`changelogs/2.txt`、`images/icon.png`（1024²）、6 张截图 | 已完成 |
| ✅ P1 | 版本号未 bump | 已升到 `versionCode = 2` / `versionName = "1.0.1"` | 已完成 |
| 🟠 P1 | `v1.0.0` tag 不含 LICENSE | tag → `c8ef9c8`；LICENSE 在 `9d3239c` 才引入，**在 tag 之后** | **重打 tag**（建议 `v1.0.1`） |
| 🟠 P1 | 隐私政策未披露内置 DoH | `DohManager.kt:24,29` 内置 `edge.47258.xyz` / `jetdo.ddd.oaifree.com`，**用户删不掉**（`removeServer()` 对 builtin 直接 return）；隐私政策第 30 行只说"你**自行配置**的" | **如实披露**或换公共 DoH |
| ✅ P2 | `BUILD_TIME = LocalDateTime.now()` | 已改为 `git log -1 --pretty=%cI`（提交时间），破坏可复现构建的问题消除 | 已完成 |
| ✅ P2 | `mavenLocal()` | 已从 `settings.gradle.kts` 删除 | 已完成 |
| ✅ P2 | `allowBackup="true"` 无规则 | 已补 `data_extraction_rules.xml`（API 31+）+ `backup_rules.xml`（API ≤ 30），排除 sharedpref/database/file/external/root/no_backup 全部域 | 已完成 |
| 🟡 P2 | `compileSdk / targetSdk = 37` | buildserver 预装只到 `platforms;android-33` | 见 §2.3（可构建期下载，风险中） |
| 🟡 P2 | 贴吧 / B 站 emoji 授权不明 | `assets/emoji_tieba_087.png`（贴吧「水果」表情）、`emoji_bili_057.png`（B 站柴犬），共 10 个 | 换 Twemoji 或申报 `NonFreeAssets` |
| 🟡 P2 | `MIXED_CONTENT_ALWAYS_ALLOW` 6 处 | `ArkWebNetworkBridge.kt:296` / `WebViewPage.kt:354` / `LoginWebViewFactory.kt:34` / `CfChallengeScreen.kt:160` / `ConnectWebHost.kt:62` / `LdcCreditWebHost.kt:76` | **暂不改**：社区正文含 http 图片，改 NEVER_ALLOW 会裂图；属功能风险项，需单独评估 |
| 🟡 P2 | `server/` 与 `relay-config.example.json` 是未接线的半成品 | APK 不依赖，但审阅者会看到 | 删掉或标 WIP |
| ✅ — | `androidx.browser` 悬空依赖 | 已删（全仓零 import）；`libs.versions.toml` 中的 version + library 一并清掉 | 已完成 |
| ✅ — | firebase keep 规则残留 | `proguard-rules.pro` 中已无 firebase 依赖，keep 规则已删 | 已完成 |
| ✅ — | `store-listing.md` 虚假描述 | 原写"崩溃信息发送到开发者自建服务器"——移除远程上报后已不属实，改为"只写入设备本机" | 已完成 |
| ✅ — | 权限表缺 CAMERA | `store-listing.md` 权限表已补 CAMERA 一行 | 已完成 |

### 2026-10-27 实测验证记录（可复现）

```bash
# ① 模拟 F-Droid 构建环境：干净副本 + 无 local.properties
rsync -a --exclude='.git' --exclude='build/' --exclude='local.properties' ./ /tmp/jd-clean/
cd /tmp/jd-clean && ./gradlew :app:assembleRelease
# → BUILD SUCCESSFUL in 5m 49s
# → 产物 app-release-unsigned.apk（15 MB，未签名，F-Droid 会自行重签 ✅）

# ② 产物残留扫描（14 项全部 0）
com/tencent/smtt = 0    tbssdk = 0        Lorg/acra = 0
com/tencent/bugly = 0   com/google/firebase = 0
com/google/android/gms = 0   androidx.browser = 0
<个人邮箱前缀> = 0      <自建域名> = 0     <服务器 IP> = 0
<开发者账号名> = 0      <第三方域名> = 0

# 复现命令（工具在仓库内，私有词表在仓库外）
python3 tools/scan_apk_residue.py \
  /tmp/jd-clean/app/build/outputs/apk/release/app-release-unsigned.apk \
  --patterns /tmp/jd-secret-patterns.txt
# → ✅ 全部 0：产物中无闭源 SDK、无私有资产字面量（退出码 0）

# ③ 权限：4 条，与隐私政策逐条对应
INTERNET / ACCESS_NETWORK_STATE / POST_NOTIFICATIONS / CAMERA
```

### 2026-10-26 17:25 复核：还差 1 个硬阻断 + 5 个工程项

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
- 无 `local.properties` 也能构建 ✅（F-Droid 构建机没有该文件，已实测）
- release 包 R8 混淆 + 资源压缩开启，`lintVitalRelease` 通过 ✅

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

### 1.2 腾讯 TBS / X5 —— ✅ 已删除（2026-10-27）

X5 内核闭源、运行时还会从腾讯服务器下载内核二进制 —— 命中「预编译二进制 blob」+「依赖非自由服务」两条。**这个更好办：代码里已经是死代码了。**

已确认（2026-10-26 复核）：`LoginWebViewFactory.kt` 的 `build()` 无条件走系统 WebView，X5 分支整段被注释；`X5LoginSupport` 外部零调用。

**2026-10-27 已完成删除**（4 处 + 重构）：

- `gradle/libs.versions.toml`：删 `tbs = "44286"` version + `tbssdk` library
- `app/build.gradle.kts:174-175`：删 `implementation(libs.androidx.browser)`（X5 实验块内重复声明，全项目零调用）+ `implementation(libs.tbssdk)`
- `LoginWebViewFactory.kt`：整体重写为单一 `object LoginWebViewFactory.create(...)`，删除 `configureX5` / `setupX5Clients` / `X5KeyboardProxy` / `X5LoginSupport` 全部 `com.tencent.smtt.*` 引用；`LoginScreen.kt` 同步改为 `LoginWebViewFactory.create(ctx, ...)`
- 收尾：`grep -rn "com.tencent" app/src gradle app/*.kts` 零命中

**验证**（2026-10-27 Debug 构建，华为 DCO-AL00 实机）：
- `./gradlew :app:assembleDebug` BUILD SUCCESSFUL
- APK 逐文件字节扫描 `com/tencent/smtt` = **0**（删除前为 777）、`tbssdk` = 0、`com/tencent/bugly` = 0、`Lorg/acra` = 0、自建域名 = 0
- 实机 `adb install -r` 成功，应用启动无 FATAL EXCEPTION，进程常驻（前台 Activity = `com.jetdo.android/.MainActivity`）

> 注：登录页功能与删 TBS 前完全一致（X5 分支此前就是注释态，本就走系统 WebView）。

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

#### 1.5 复核补充：2026-10-27 的两个决定性事实

上一节结论方向正确（不需要自带内核），但有两处依据需要修正 —— 都是在真机上实测出来的：

**事实一：系统 WebView 本身就是 Chromium，「系统 WebView 能力差」不是引擎弱。**

实测本项目主力测试机（华为 DCO-AL00）：

```
$ adb shell dumpsys webviewupdate
Current WebView package (name, version): (com.huawei.webview, 132.0.6.309)
Preferred WebView package (name, version): (com.huawei.webview, 132.0.6.309)
    com.google.android.webview is NOT installed.
```

Chromium **132** —— 与桌面 Chrome 同期的主流版本，V8 引擎。真正的问题不是"引擎弱"，而是：

| 真实症结 | 说明 |
|---|---|
| **国内 ROM 的 WebView 供给不均** | 这台机器只有华为自研的 `com.huawei.webview`，没有 Google WebView，且华为版本随 ROM 走、不随应用更新 |
| **版本碎片化** | 应用装在不同厂商机器上，内核版本可能是 Chromium 80~140 不等，无法假设任何新 Web API 可用 |
| **厂商魔改** | 华为/小米的内核有定制行为（UA 改写、注入脚本），与其他厂商不一致 |

所以"换一款更快的 WebView"这个诉求，**真正对标的不是引擎性能，而是版本一致性与可控性**。GeckoView 恰好解决的是后者，但它用的是 SpiderMonkey 而非 V8。

**事实二：GeckoView 在 F-Droid 上有明确的构建障碍（且至今未解）。**

`fdroid/fdroidserver` 的 issue **#982 "Whitelist Mozilla maven repo"** 自 2022-04-09 开到今天，状态仍是 `opened`：

```
$ curl -sS 'https://gitlab.com/api/v4/projects/fdroid%2Ffdroidserver/issues/982'
{"state": "opened", "title": "Whitelist Mozilla maven repo", "closed_at": null}
```

F-Droid 收录政策明文只允许这几个预构建仓库来源：

> 应用可以从可信 Maven 存储库下载特定状态的预构建 FLOSS 二进制文件。这些存储库包括 Maven Central、Google Maven、
> OSS Sonatype、OSS JFrog、JitPack.io 和 Clojars。

`maven.mozilla.org` 不在其中。实测确认 GeckoView 也**不在** Maven Central：

```
$ curl 'https://search.maven.org/solrsearch/select?q=g:org.mozilla.geckoview'
→ Maven Central 无 geckoview
→ org.mozilla.components 命中 0
```

结论：**GeckoView 无法通过 F-Droid 的依赖来源检查**，除非申请豁免或自建源码构建流程（把 mozilla-central 编译成 AAR 塞进仓库 —— 对本项目而言是自杀级工程量）。

唯一可行的参照是 **Mull**（`us.spotco.fennec_dos`）：它能被收录，是因为整个浏览器**从 mozilla-central 源码自行编译**，不依赖预构建 AAR，绕开了这个限制。APK 体积 88~92 MiB（arm64/armv7）。

#### 1.6 候选方案横向对比（2026-10-27 结论）

| 方案 | 引擎 | F-Droid 可行性 | 体积增量 | 本项目改造量 | 性能 | 判定 |
|---|---|---|---|---|---|---|
| **系统 WebView**（现状） | Chromium 132（V8） | ✅ 零障碍 | 0 | 0 | 基准水平 | **保留** |
| **GeckoView** | SpiderMonkey | ❌ 依赖来源不合规（#982 未解） | +40~92 MB | 极高（2386 行 WebView 代码全改） | V8 之下（合成基准慢 5~15%） | **否决** |
| **Custom Tabs** | 系统浏览器内核（Chromium） | ✅ 零障碍 | 0 | 中 | 同上，但环境更完整 | **仅限站外链接**（见下） |
| **WebViewUpgrade** | 换 provider | ❌ 装的是闭源 APK | — | — | — | **否决** |
| **Chromium 自编译**（chromeview） | Chromium | ⚠️ 需自建构建体系 | +60 MB+ | 极高 | 持平 | **否决** |
| **Crosswalk** | Chromium | ❌ 2017 停维 | — | — | — | **否决** |

**最终建议：不换引擎，按「承载方式 + 边界」分工。**

1. **登录页 / 人机验证 / 站点授权：维持 App 内 WebView（现状），不要改走 Custom Tabs。**
   ⚠️ 2026-10-27 复核修正：本项目**历史上已经用过 Custom Tabs 并主动放弃**，原因记录在
   `ui/screens/BrowserAuthWebPage.kt:53-56` 与 `services/auth/BrowserAuthLoginFlow.kt:19-21`：

   > 原来：Custom Tabs 拉起系统浏览器 → 那台浏览器自己得能通（实测华为机上被安全浏览云策略拦 +
   > 无 DoH 解析失败，点「授权」直接 403，深链永远不回）。
   > 现在：复用 App 内的通用 WebViewPage（挂 WebViewDohInterceptor，站内请求走 OkHttp/DoH）

   设备侧已确认华为浏览器**确实提供** Custom Tabs 服务（`com.huawei.browser/.customtab.CustomTabConnectionService`，
   浏览器 17.9.2.300），所以**不是协议不支持**，而是**外部浏览器拿不到 App 的 DoH 解析链、
   又被厂商安全浏览策略拦**。Custom Tabs 的收益（复用浏览器内核与 cookie）恰好被它的代价
   （进程在浏览器侧，无法拦截请求、无法注入 App 的 DoH）抵消。

   设备侧复核命令：
   ```bash
   adb shell pm query-services -a android.support.customtabs.action.CustomTabsService
   ```

2. **Custom Tabs 的唯一适用场景：打开纯站外网页**（`WebViewPage` 里用户点击站外链接）。
   此时不需要 App 的 cookie、不需要过盾、不需要 DoH 拦截，交给浏览器反而更自然。
   `androidx.browser:browser:1.8.0` 依赖已在 `libs.versions.toml:2,65` 与 `app/build.gradle.kts:116`
   声明，但**源码零 import**（悬空依赖）；`app/build.gradle.kts:114-116` 的注释还写着
   「浏览器授权登录：Custom Tabs 拉起系统浏览器」，与实际实现矛盾，需一并订正。

3. **Markdown / MathJax 渲染保留离屏 WebView**（`MarkdownRenderEngine` 145 行 + `MathRenderHost`）。
   这类是本地静态渲染，不涉及网络与验证，无换引擎必要。

4. **若将来确需统一内核**，唯一合规路径是像 Mull 那样把 mozilla-central 源码自编译进仓库 —— 那是另一个量级的工程，
   应单独立项评估，而不是塞进本次上架。

> 一句话：**没有"又快又合规又能塞进仓库"的第三方 WebView SDK。** 系统 WebView 已经是 Chromium 132，
> 性能不是瓶颈；瓶颈是"国内 ROM 的 WebView 供给与版本碎片化"。而 Custom Tabs 恰恰因为跑在浏览器进程，
> 拿不到 App 侧的 DoH 与拦截链，在本项目的核心链路上是负收益。

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

## 五、执行顺序（2026-10-27 11:20 更新）

| # | 动作 | 状态 |
|---|---|---|
| 1 | 删 Bugly（后续连 ACRA 一并移除，最终零远程上报） | ✅ 已完成 |
| 2 | **删 TBS / X5**（4 处落点）→ 重新构建确认通过 | ✅ 已完成（工作区，待提交） |
| 3 | **commit + push 5 个文件的 TBS 删除改动**（远端 `a1086b0` 里仍有 TBS） | ⬜ **待做 · 最优先** |
| 4 | 改 `BUILD_TIME` 为提交时间、删 `mavenLocal()` | ⬜ 待做 |
| 5 | `versionCode 2 / versionName 1.0.1` + 重打 `v1.0.1` tag（**必须打在新提交上**） | ⬜ 待做 |
| 6 | 补 `fastlane/metadata/android/{en-US,zh-CN}/`（short_description / full_description / changelogs/2 / icon / phoneScreenshots） | ⬜ 待做 |
| 7 | 隐私政策如实披露两个内置 DoH 端点（或换成公共 DoH） | ⬜ 待做 |
| 8 | emoji 换 Twemoji 或申报 `NonFreeAssets`；补 `allowBackup` 规则；收窄 `MIXED_CONTENT`；`store-listing` 权限表补 CAMERA | ⬜ 待做 |
| 9 | 删掉或标注 WIP：`server/`、`relay-config.example.json`、proguard 里的 firebase 残留规则 | ⬜ 待做 |
| 10 | 提 fdroiddata MR（`New App` 标签），看 CI 试构建对 `compileSdk 37` 的裁决 | ⬜ 待做 |
| 11 | 合并后 24–48h 出现在主仓库 | ⬜ 待做 |

> 注意第 5 步：`v1.0.0` 指向的 `c8ef9c8` 没有 LICENSE 文件（LICENSE 在 `9d3239c` 才引入，在 tag 之后），
> **不能复用**。要么删掉重打，要么直接发 `v1.0.1`。
> `UpdateCheckMode: Tags` 会让 F-Droid 抓「最靠后的 tag」，旧 tag 留着不影响新版本，但会留个坏样本，建议一并删。

> 注意第 3 步：F-Droid 是从 **GitHub 远端** 拉源码构建的。本地删了 TBS 不等于远端删了 ——
> `git show origin/feat/migrate-vertical-slice:gradle/libs.versions.toml` 里 `tbs = "44286"` 还在。
> **不 push 的话，审核员拉到的还是带 TBS 的版本。**

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

### 6.4 模拟 F-Droid 构建环境（2026-10-27 新增，最有价值的一条）

F-Droid 的构建机**没有** `local.properties`，也不做签名。必须验证「干净副本 + 无 local.properties」能构建：

```bash
# 造一个干净副本（排除 .git / build / local.properties）
rsync -a --exclude='.git' --exclude='build/' --exclude='*/build/' \
      --exclude='.gradle' --exclude='local.properties' ./ /tmp/jd-clean/

cd /tmp/jd-clean && ./gradlew :app:assembleRelease
# 期望：BUILD SUCCESSFUL
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk（未签名 ✅ F-Droid 自己重签）

# 产物逐字节扫描（比 unzip -l 更严，能扫到 strings 里的域名）
# 私有化词表不落在仓库里：单独放一份 gitignore 的本地文件，扫描时读进来
cat > /tmp/jd-secret-patterns.txt <<'EOF'
# 每行一个待查字面量（自建域名、服务器 IP、个人邮箱前缀、开发者账号名…）
<把要查的字面量逐行写这里，不要提交进 Git>
EOF

# 用仓库内置的工具扫（等价于手写 unzip + 逐字节 count）
python3 tools/scan_apk_residue.py \
  /tmp/jd-clean/app/build/outputs/apk/release/app-release-unsigned.apk \
  --patterns /tmp/jd-secret-patterns.txt
# 期望：全部 0，退出码 0
```

> 说明：这一条比 `unzip -l | grep` 严格得多 —— `unzip -l` 只看**文件名**，
> 而脚本扫的是**文件内容**，能抓到藏在 `resources.arsc`、dex 字符串常量里的域名与类名。
> 上一轮就是靠它发现 `oaifree` 仍残留在 `classes2.dex` 里。
>
> ⚠️ **私有词表必须放在仓库外**。把「要查的字面量」直接写进 `docs/` 里的扫描脚本，
> 等于在脱敏完成后又把真实域名/IP/账号名写回仓库 —— 扫的是 APK，泄露的是 Git。
> 工具默认读 `tools/scan-patterns.local.txt`（已在 `.gitignore` 中），
> 也可用 `--patterns` 指向任意仓库外路径。
