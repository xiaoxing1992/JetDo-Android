# fastlane 元数据（F-Droid / 商店上架）

本目录是 F-Droid 官方推荐的**应用展示元数据**布局，F-Droid 与 fastlane 都识别它：

```text
fastlane/metadata/android/
├── en-US/
│   ├── short_description.txt          # < 80 字符，结尾不带句点
│   ├── full_description.txt
│   ├── changelogs/
│   │   └── 2.txt                      # 文件名 = versionCode
│   └── images/
│       ├── icon.png                   # 1024×1024 PNG
│       └── phoneScreenshots/
│           ├── 1.png … 6.png          # 1212×2616 PNG
└── zh-CN/                             # 同结构
```

> F-Droid 的**构建元数据**（`metadata/com.jetdo.android.yml`，定义 Gradle 构建与自动更新）
> 不在本仓库，需单独提交到 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 仓库。

## 当前状态

| 项 | 状态 |
|---|---|
| 短描述 / 完整描述 | ✅ en-US + zh-CN 双语 |
| 更新日志 | ✅ `changelogs/2.txt`（对应 `versionCode = 2` / `versionName = 1.0.1`） |
| 图标 | ✅ 1024×1024，取自 `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` |
| 截图 | ✅ 6 张（首页 / 话题详情 / 消息 / 搜索 / 设置 / 筛选抽屉） |

## 截图纪律（重要）

`screenshots/profile.png` **不可用于任何商店**——它包含开发者本人的社区账号名与统计数据。
本目录只收录了不含本人账号的 6 张。若要换新截图，先确认画面里没有：

- 本人的社区账号名 / 头像 / 等级 / 徽章
- 未公开的私信、通知、邮箱
- 任何真实可关联到个人的 ID

## 发布前自查

```bash
# 1. 短描述长度（F-Droid 要求 < 80 字符、结尾无句点）
for f in fastlane/metadata/android/*/short_description.txt; do
  printf '%s: %s 字符\n' "$f" "$(wc -m < "$f" | tr -d ' ')"
done

# 2. 产物残留扫描（闭源 SDK + 私有资产字面量）
python3 tools/scan_apk_residue.py <apk> --patterns <仓库外的本地词表>

# 3. 元数据目录里不能出现真实域名/IP/账号名
#    （用仓库外的本地词表逐条比对，别把词表本身写进文档）
git grep -nF -f <仓库外的本地词表> -- fastlane/
```

## 参考

- F-Droid 提交指南：<https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/>
- fastlane 元数据规范：<https://docs.fastlane.tools/app-store/metadata/>
