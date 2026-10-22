# 开发手册 · 版本管理与代码图谱

本项目用 **git 管版本** + **CodeGraph 管代码结构**。两者已联动：每次提交后自动更新代码图谱。

---

## 仓库结构

```
阅读器/
├── app/src/main/java/com/kpa/reader/   源码（Kotlin）
│   ├── ui/       界面：MainActivity / ReaderActivity / PageView / ShelfAdapter
│   ├── doc/      文档模型与排版：Document / LayoutEngine / HtmlBlockParser
│   ├── book/     格式解析：TextLoader / EpubParser / MobiParser / ChapterSplitter
│   ├── data/     持久化：Book / BookStore / ReaderSettings
│   └── input/    手柄按键映射：Gamepad
├── app/src/test/                       单元测试（24 个）
├── screenshots/                        实机截图，README 引用
├── .githooks/                          版本化 git hooks
├── .workbuddy/memory/                  开发档案：构建链约束、踩坑记录、架构决策
└── .codegraph/                         代码图谱索引（不入库，可重建）
```

**不纳入版本控制**：`build/`、`.gradle/`、`.kotlin/`、`local.properties`（含本机 SDK 路径）、`.codegraph/`。
`release.keystore` **故意入库** —— 自签名证书，密码本就明文在 `app/build.gradle.kts`，入库能保证签名一致、APK 可覆盖升级。

---

## 日常流程

```bash
./build.sh                      # 构建签名 release APK
./build.sh debug                # 构建 debug APK
gradle :app:testDebugUnitTest   # 跑单元测试（24 个）
```

提交：

```bash
git add -A
git commit -m "feat: 说明做了什么"
```

**提交信息前缀**：`feat:` 新功能 / `fix:` 修 bug / `perf:` 性能 / `refactor:` 重构 / `docs:` 文档 / `chore:` 杂务 / `test:` 测试。

---

## git hooks

hooks 放在 `.githooks/` 并随仓库版本化，克隆后用一条命令启用：

```bash
git config core.hooksPath .githooks
```

| hook | 作用 |
|------|------|
| `pre-commit` | 拦住 `local.properties`、`build/`、`*.apk` 等误入库。确需提交用 `git commit --no-verify` |
| `post-commit` | 后台静默跑 `codegraph sync`，索引始终跟得上代码。日志在 `.git/codegraph-sync.log` |

---

## CodeGraph 代码图谱

索引了 25 个文件 / 604 个符号 / 1177 条关系。**核心用途：改代码前先看清调用链和影响面。**

```bash
codegraph status                     # 索引状态与统计
codegraph query LayoutEngine         # 按名字搜符号（定位到文件:行）
codegraph explore "pagination"       # 探索某主题：相关符号 + 源码 + 调用路径 + 影响面
codegraph node paginate              # 单个符号的源码 + 上下游调用
codegraph callers paginate           # 谁调用了它
codegraph callees paginate           # 它调用了谁
codegraph impact LayoutEngine        # 改动这个类会波及什么
codegraph affected <文件>            # 哪些测试受这些改动影响
codegraph files                      # 按索引看项目结构
```

### 注意事项

- **查询用英文标识符**。CodeGraph 按代码里的名字匹配，不做中文语义检索 ——
  `codegraph explore "分页行距"` 查不到东西，要写 `codegraph explore "pagination line spacing"`。
- 索引过期了跑 `codegraph sync`（增量）或 `codegraph index`（全量重建）。
- MCP 已接入 opencode 和 WorkBuddy，agent 可直接调用图谱工具；
  命令行 `codegraph serve --mcp` 即 MCP 服务本体。

---

## 构建环境的硬约束（别踩）

- **Gradle 9.7.1 + AGP 9.1.0**。AGP 8.x 在 Gradle 9.6+ 上会报内部 API 移除。
- **AGP 9 内置 Kotlin**，再加 `org.jetbrains.kotlin.android` 插件直接报错。
- **项目不放 Gradle wrapper** —— `services.gradle.org` 国内不通，wrapper 生成必失败，`build.sh` 直接调系统 `gradle`。
- 更细的坑（行距 multiplier、MOBI extraFlags、Dialog 高度裁剪等）见 `.workbuddy/memory/` 下的开发档案。
