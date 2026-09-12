# Liquid Glass 玻璃组件系统 — Selective Liquid Glass UI

> 普通Android UI + 精确使用的 Liquid Glass 组件 + 自然层级 + 轻微动态材质 + 高性能。
> 而不是：整个 App → 透明 → blur → 一坨玻璃。

## 1. 设计原则

1. **选择性玻璃化** —— 只有适合的交互组件用玻璃：卡片、返回/图标按钮、Drawer 导航项、悬浮件、对话框、输入容器、小型状态组件。页面背景、大面积内容、终端渲染区一律不玻璃。
2. **诚实材质声明** —— 没有真实 backdrop 采样的地方只称 Frosted；未实现的 Refraction 明确标注 NOT IMPLEMENTED。禁止"alpha + blur + gradient + border"冒充 Liquid Glass。
3. **主题动态派生** —— tint、边缘高光、镜面高光全部从 MaterialTheme 解析，Light / Dark / Dynamic Color 自适应；不存在固定的 `white alpha 0.1` 万金油材质。
4. **业务层零底层耦合** —— 业务 UI 只使用 `ui.glass` 包的组件 API；Haze 调用被封装在 `GlassSurface` 单点，替换底层库不波及业务。

## 2. 架构

```
                    业务 UI
                      │
            ┌─────────┴─────────┐
   Backdrop 档组件        Frosted 档组件
  state: HazeState!        state: null
            │                 │
            └────────┬────────┘
                 GlassSurface
          激活度动画 / 边缘光 / 高光 / 深度
                     │
                GlassStyle 七档
                     │
              Haze 1.4.0（唯一底层）
         GraphicsLayer 采样 + RenderEffect
                     │
                 Android GPU
```

### 两个材质档

| 档 | 条件 | 能力 |
|----|------|------|
| **Backdrop** | `state: HazeState` 且组件悬浮于 `hazeSource` 内容之上 | 真实采样背后 UI；API 32+ GPU RenderEffect 模糊 + tint + noise；API < 32 自动 scrim 降级 |
| **Frosted** | `state = null` | 主题色薄霜渐变 + 边缘光 + 镜面高光 + 深度。不声明 backdrop |

**为什么列表内卡片是 Frosted**：Haze 1.4.0 不支持 hazeEffect 嵌套在 hazeSource 子树内（该修复在 1.5.0 才引入）；且为每个卡片独立建源违反性能规范（禁止每卡 full-screen capture）。聊天消息列表内的 Tool 卡 / Plan 卡因此诚实降级为 Frosted 档。

### 七档 GlassStyle

| 档 | blur | tint | 边缘 | 用途 |
|----|------|------|------|------|
| Subtle | 8dp | 0.42 | 0.08 | 小型状态组件 |
| Control | 10dp | 0.50 | 0.16 | 图标按钮 / 输入容器 |
| Card | 14dp | 0.58 | 0.18 | 工具卡 / 状态卡 |
| Navigation | 12dp | 0.46 | 0.13 | Drawer 导航项（常态必须"非常轻"） |
| Floating | 18dp | 0.62 | 0.24 | FAB / 悬浮输入栏 |
| Dialog | 22dp | 0.66 | 0.24 | 对话框面板 |
| Strong | 26dp | 0.74 | 0.28 | 特殊高聚焦场景 |

## 3. 组件清单

全部位于 `com.apex.agent.ui.glass`：

- `GlassSurface` —— 渲染核心：材质层 + 激活度动画 + 边缘光 + 高光 + 深度
- `GlassCard` / `GlassToolCard`（状态着色）/ `GlassBadge`
- `GlassIconButton`（40dp 高圆角轻玻璃）/ `GlassButton`（胶囊）
- `GlassFloatingButton`（48dp，比卡片更强的边缘与高光）
- `GlassNavigationItem`（Normal 轻量 / Selected 提亮 / Pressed 短暂高光 / Disabled 降权）
- `GlassDialog`（state 非 null 时经 HazeDialog 跨窗口采样 Activity 内容）

交互状态机：`Normal 0 / Focused 0.35 / Selected 0.55 / Pressed 1` 驱动 140ms 单向激活动画（非循环），影响高光亮度、边缘强度、按压缩放与选中浸染。

## 4. 已迁移组件

| 组件 | 档 | Backdrop 源 |
|------|-----|-------------|
| Drawer 导航项 ×9 | Backdrop | 抽屉氛围背景（渐变 + 双光晕 + 细网格） |
| 聊天悬浮输入栏 | Backdrop | 消息 LazyColumn |
| 回到底部 FAB | Backdrop | 消息 LazyColumn |
| 顶栏菜单钮 | Frosted | ——（顶栏无重叠内容，诚实降级） |
| 工具卡 / 运行中工具卡 | Frosted | ——（源内嵌套不可用） |
| Plan / Spec / 确认卡 | Frosted | ——（同上） |
| 任务状态卡 | Frosted | ——（直排区无重叠） |
| 玻璃实验室验证屏 | Backdrop | 验证区 Canvas（网格 + 文字 + 漂移光斑） |

**明确不玻璃化**：终端渲染区（TerminalRenderer 保持纯色 + 高对比 + 低延迟）、页面背景、聊天气泡正文、ContextMeterBar。

## 5. 性能设计

- 每个 haze 源仅一次 `GraphicsLayer` 记录（单共享源层），无逐帧 Bitmap 分配
- 模糊经 GPU `RenderEffect`；API < 32 设备走 scrim 降级，不做软件模糊
- 玻璃叠加层（边缘/高光/深度）全部为单次 `drawBehind` 绘制，零额外图层
- 激活动画 140ms tween 单向；除玻璃实验室验证屏（验证实时采样所必需的漂移光斑）外，业务界面无循环动画

## 6. 最终真实性验收

| 能力 | 状态 | 真实实现 |
|------|------|----------|
| Backdrop | PASS | Haze GraphicsLayer 采样背后内容 |
| Blur | PASS | API 32+：RenderEffect GPU 模糊；API < 32：scrim 降级（无 blur，不虚报） |
| Material response | PASS | 激活度动画驱动亮度/边缘/形变 |
| Edge lighting | PASS | drawOutline 内描边渐变（上强下弱受光） |
| Depth | PASS | 外阴影 + 底部内阴影双级线索 |
| Specular | PASS | 顶部高光扫掠（绘制层效果） |
| Refraction | **NOT IMPLEMENTED** | 未实现折射位移 —— 拒绝冒充 |
| Interaction | PASS | Pressed / Focused / Selected / Disabled 四态 |
| Performance | PASS | 单共享源层，无逐帧 Bitmap 分配 |
| Fallback | PASS | API < 32 自动 scrim；无源区域自动 Frosted |

以上清单同时内置于"玻璃实验室"验证屏，运行时按设备 SDK 级别如实展示 —— Blur 行在低版本设备显示 FALLBACK 而非 PASS。

## 7. 验证方式

抽屉 → **玻璃实验室**：

1. 拖动玻璃片扫过网格 / 文字 / 漂移光斑 —— 玻璃内部画面实时变化即 backdrop 真实采样的证据
2. 对话框打开时面板模糊的是验证区真实内容（HazeDialog 跨窗口采样）
3. 档位阶梯肉眼对比七档材质强度
4. 聚焦输入框观察材质受光上升，失焦回落
5. 诚实验收清单随设备运行时求值

## 8. 文件索引

| 文件 | 职责 |
|------|------|
| `ui/glass/GlassStyle.kt` | 七档材质参数 + 主题调色板派生 |
| `ui/glass/GlassSurface.kt` | 渲染核心：材质层 + 激活度 + 叠加层绘制 |
| `ui/glass/GlassComponents.kt` | 业务层组件 API 全集 |
| `ui/screen/glass/GlassLabScreen.kt` | 验证屏 |
| `ui/ApexDrawerContent.kt` | 抽屉迁移：氛围背景源 + GlassNavigationItem |
| `ui/screen/agent/AgentChatScreen.kt` | 聊天迁移：haze 源 + 悬浮玻璃输入栏 + FAB |
