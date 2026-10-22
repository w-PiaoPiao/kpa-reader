## 项目性质

这是 **KONKR Pocket Advance 安卓掌机阅读器**（Android 12、3.5 寸 960×640 横屏、纯实体按键操作），
不是 GBA ROM 自制软件，不要往 GBA 方向返工。

完整开发说明见 `DEVELOPMENT.md`，历史踩坑与架构决策见 `.workbuddy/memory/`。

## 改代码前先查图谱

本项目已建 CodeGraph 索引（25 文件 / 604 符号 / 1177 关系），MCP 已接入。
**动任何符号之前**，先用图谱看清上下游，别靠猜：

- `codegraph impact <符号>` — 改它会影响什么
- `codegraph callers <符号>` — 谁在调用
- `codegraph explore "<英文关键词>"` — 相关实现与调用路径

注意：查询必须用**英文标识符**，中文检索匹配不到。

## 硬约束

- Gradle 9.7.1 + AGP 9.1.0，**不能用 AGP 8.x**；AGP 9 内置 Kotlin，不要再加 kotlin 插件。
- 项目**没有 Gradle wrapper**（国内拉不到 distribution），构建一律走 `./build.sh`。
- 提交信息用中文，前缀 `feat:` / `fix:` / `perf:` / `refactor:` / `docs:` / `chore:`。
- `.githooks/` 已启用（`core.hooksPath`），提交后会自动同步图谱，不用手动 index。
