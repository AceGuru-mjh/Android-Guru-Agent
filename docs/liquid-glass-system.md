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
| Agent 流式/完成态/思考气泡（v5） | Frosted | ——（源内嵌套不可用，与展开态工具卡同档；流式与完成态同 shape 同档，切换无容器跳变） |
| Coding 屏悬浮栈（v5：输入栏/模式行/错误条） | Backdrop | CodeStreamTimeline（时间轴为 haze 源，底部栈悬浮其上） |
| Coding 流式结论/思考卡（v5） | Frosted | ——（源内嵌套不可用，与 Agent 屏气泡同一套玻璃语言） |
| 顶栏菜单钮 | Frosted | ——（顶栏无重叠内容，诚实降级） |
| 工具卡 / 运行中工具卡 | Frosted | ——（源内嵌套不可用） |
| Plan / Spec / 确认卡 | Frosted | ——（同上） |
| 任务状态卡 | Frosted | ——（直排区无重叠） |
| 玻璃实验室验证屏 | Backdrop | 验证区 Canvas（网格 + 文字 + 漂移光斑） |

**明确不玻璃化**：终端渲染区（TerminalRenderer 保持纯色 + 高对比 + 低延迟）、页面背景、气泡/卡片内的正文排版（Markdown 文本永远普通绘制——玻璃只作用于容器层）、ContextMeterBar。

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
| `ui/screen/agent/AgentChatMessages.kt` | v5 流式玻璃气泡（Agent/Streaming/Thinking 三气泡）；v7 三气泡换 AgentBubbleGlass 真模糊材质 |
| `ui/screen/code/CodeScreen.kt` | v5 Coding 玻璃悬浮层：时间轴 haze 源 + 悬浮栈 + Floating 输入栏；v6 错误条/提问卡接线 |
| `ui/screen/code/stream/CodeStreamCards.kt` | v5 Coding 流式结论/思考卡玻璃化 |
| `ui/glass/TerminalGlass.kt` | v6 终端语义玻璃：恒定深色材质，Haze 真采样（白天模式深色磨砂而非实心黑板） |
| `ui/glass/CloudyFrost.kt` | v7 Cloudy 真模糊霜面材质 + AgentBubbleGlass 气泡壳（cloudy 集成唯一文件） |
| `ui/screen/code/stream/CodeTerminalPanel.kt` | v6 终端尾窗接 TerminalGlass + 尾窗 260→168dp 瘦身 |

> v5 变更详情（流式玻璃 + 技能 chip 输入框 + 按钮防挤压 + 函数调用文案纠偏）见 [chat-input-v5-glass-chips.md](chat-input-v5-glass-chips.md)。

## 9. v6 悬浮栈全玻璃接线（白天模式收口）

v5 只接线了 Coding 屏输入栏，悬浮栈其余成员仍是实色 Surface / ElevatedCard
—— 白天模式下「输入栏是磨砂玻璃、旁边是死色色块」的割裂观感是 v6 的
直接动机：

| 悬浮栈成员 | v5 | v6 |
|------|------|------|
| 输入栏 | GlassCard(Floating) 真采样 | 不变 |
| 终端尾窗 | 实色深底 Surface | TerminalGlassSurface 真采样（恒定深色材质） |
| 提问卡 | ElevatedCard 实色 | GlassCard(Card) 真采样（Agent 屏调用不传 state 保持原形态） |
| 错误条 | 实色 errorContainer | GlassCard(Floating) + error accent 真采样 |

同批收口的流水紧凑化（终端尾窗 260→168dp、胶囊 ~49→44dp、chip 行
48→32dp、回底 FAB 56→40dp、卡片垂直内边距全面下调）与白天模式语义色
修复（胶囊 exit-0 绿 4ADE80 → ExtendedColors 成对槽位）详见 PR 描述。

## 10. v7 Cloudy 真模糊材质（agent 回复气泡）

### 用户痛点

「agent 的回复的白天模式液态玻璃 UI 做的简直没有用到 Cloudy，所有显示的不行」
—— 完成态 / 流式 / 思考三气泡此前用 Frosted 档（垂直渐变假霜面），白天
模式下是一块「平而死白」的乳白块，没有磨砂玻璃的光影纵深。根因结构性
存在：气泡位于 hazeSource（消息 LazyColumn）子树内，Haze 1.4 不支持嵌套
采样，Backdrop 档永远拿不到真模糊 —— 无论怎么调渐变参数都是「假霜」。

### 方案：Cloudy 自体模糊材质（CloudyFrost）

引入 [Cloudy](https://github.com/skydoves/Cloudy)（com.github.skydoves:cloudy），
`Modifier.cloudy(radius)` 把**材质层自身内容**做真实位图模糊：材质层
（`ui/glass/CloudyFrost.kt` 的 `CloudyFrostLayer`）手绘斜向光带 ×3 +
垂直渐变，整层经真实模糊扩散成柔和发光磨砂 —— 白天 = 乳白底 + 柔和
受光带（治死白），夜间 = 白/primary 霓虹光雾。文字是兄弟节点，
**永不被模糊**；边缘光/镜面高光叠加层保持在模糊材质之上 crisp 绘制。

### 版本选型（Maven Central 元数据 + 源码核实）

| 版本 | Compose 要求 | Kotlin 要求 | 结论 |
|------|-------------|-------------|------|
| 1.0.0-alpha01 | 1.8.x | **2.4**（toolchain 不兼容） | ✗ |
| 0.2.7 | **1.8.1**（BOM 2024.12.01 = 1.7.6 ✗） | 2.0 | ✗ |
| **0.2.3** | **1.7.1**（项目 1.7.6 向后兼容） | **2.0.20**（消费端 2.0.21 一版兼容） | ✓ |

其他核实：AAR minSdk 21 < 项目 26 ✓；纯 Kotlin + JNI，无反射，无需
proguard keep；自带 baseline profile；四 ABI 原生库 ≈0.37~0.42MB/个。

### 与 Haze 的分工（两套互补的真模糊能力）

| 引擎 | 语义 | 适用位置 | 降级路径 |
|------|------|----------|----------|
| Haze 1.4（Backdrop 档） | 采样**背后内容**再模糊 | 悬浮组件（输入栏/终端尾窗/抽屉导航/对话框） | API < 32 → scrim |
| Cloudy 0.2.3（Frosted 档 Cloudy 变体） | 模糊**材质层自身**（自绘光带纹理） | 列表内嵌组件（聊天气泡）—— Haze 无法嵌套采样的死角 | 见下表 |

### 降级行为表（源码级核实，非 README 转述）

| 环境 | 行为 |
|------|------|
| 真机 / 模拟器（**全 API 级别**） | 原生 RenderScriptToolkit（NEON/SIMD CPU，自有线程池）迭代模糊 —— 0.2.3 源码中**没有** RenderEffect 分支，API 31+ 也是同一条 CPU 路径（高版本走 GPU 的是 0.2.7+ / 1.0.0 的实现） |
| Android Studio 预览（LocalInspectionMode） | 自动回退 `Modifier.blur`（系统 blur 修饰符） |
| 位图回读 / 模糊抛异常 | Cloudy 层不绘制 —— 霜面零底（GlassSurface 渐变 background）仍在，观感回到 Gradient 变体，不出现空白气泡 |

### 性能边界（适用范围裁决）

- Cloudy 0.2.3 的 draw 路径：材质层 record 进 GraphicsLayer →
  `toImageBitmap()` 位图回读 → `runBlocking(Dispatchers.IO)` 原生模糊 →
  绘制模糊位图。**只在材质层节点（重）绘制时发生**（出现 / 尺寸变化 /
  主题切换）；静态内容不逐帧重模糊，纯滚动位移不触发重录（RenderNode
  平移复用）。
- 每个使用点一个离屏层 + 一次 CPU 模糊（NEON 毫秒级）—— 因此**只用于
  低数量、高价值表面**：目前仅 agent 三气泡（一屏可见通常 < 15 个）；
  工具卡 / 时间线 / 输入栏等密集或高频重排表面维持 Haze + Gradient，
  风险与性能隔离。
- LazyColumn 复用 OK（item 复用时节点与 GraphicsLayer 一并复用）；流式
  气泡高度增长按换行频率重模糊（NEON 单趟毫秒级，可接受）。

### 架构落点（玻璃系统纪律）

- `com.skydoves.cloudy` 的 import 全仓库**只出现在**
  `ui/glass/CloudyFrost.kt`（单点集成，换库只改这一个文件）；
- 业务侧新 API：`AgentBubbleGlass`（agent 三气泡统一玻璃壳），内部 =
  `GlassSurface(frostMaterial = Cloudy)` + `GlassStyle.Bubble` 专用档
  （scrim 0.55→0.44 更透、blur 14→16dp 更柔，白天列表内容经半透明霜面
  透出）；
- 其余 GlassCard 调用点（工具卡/输入栏/抽屉/对话框）零改动。
