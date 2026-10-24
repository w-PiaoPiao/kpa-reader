# 项目长期约定

> 详细说明见 `DEVELOPMENT.md`，这里只记跨会话必须遵守的硬约定。

## 版本号 x.y.z

| 段 | 何时 +1 | 谁决定 |
|---|---|---|
| x | 只有用户明确要求 | 用户 |
| y | GitHub 发 release，或用户明确要求 | 用户 / 发版 |
| z | 改了 `app/src/` 下的代码 | AI 自动判断 |

- **纯文档改动不升版本**（README、注释、截图、memory）。
- `versionCode = x*10000 + y*100 + z`，只改 `app/build.gradle.kts` 的 `defaultConfig`。
- 当前基线：**1.6.0 / 10600**。

## 构建

- Gradle 9.7.1 + AGP 9.1.0，**不能用 AGP 8.x**；AGP 9 内置 Kotlin，不要再加 kotlin 插件。
- 没有 Gradle wrapper（国内拉不到 distribution），一律 `./build.sh`。
- 签名证书与密码在 `local.properties` + 本地 `release.keystore`，**都不入库**。

## 掌机按键（v1.6.1 起，可在设置里改绑）

默认映射：
十字键翻页（固定）/ **A 下页** / **R1 下章** / **R2 上章** / **L1 加书签** /
**L2 书签列表** / X 目录 / Y 搜索 / START 菜单 / SELECT 夜间 / **B 后退（双击确认）**。

- 可自定义：A / X / Y / L1 / R1 / L2 / R2 / START / SELECT（`input/Keymap.kt`）
- **不可自定义**：十字键（导航基础）、B（后退键，捕获时也用作取消）
- 绑定到已占用的键会与对方**交换**，不会让任何动作变成"未设置"
- 存储：SharedPreferences `reader_keys`，键名是 Action 枚举名

## 文档分工

- `README.md` — 面向使用者：安装、按键、功能、版本历史
- `DEVELOPMENT.md` — 面向开发：构建、版本规则、hooks、CodeGraph
- `AGENTS.md` — 给 AI 协作者的硬约束
- `.workbuddy/memory/` — 踩坑记录与架构决策（按日期，append-only）
