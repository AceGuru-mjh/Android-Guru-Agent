package com.apex.agent.plugin.webautomation

/**
 * 浏览器自动化工具目录：15 个 browser_* 工具的静态描述符（id / name /
 * description / parametersSchema），与宿主 app 模块 BrowserAgentTools.kt 的
 * 内置工具逐字段一致（description 原文照抄、parametersSchema 为同一份 JSON
 * 字符串），保证函数调用参数校验在插件声明与宿主实现两侧完全一致。
 *
 * 分工：本插件只**声明与分发**工具清单；实际执行经 IApexPluginHost.executeHostTool
 * 回到宿主进程（BrowserEngine / BrowserAgentTools 都在宿主，插件进程没有浏览器）。
 */
object BrowserToolCatalog {

    /** 单个浏览器工具的描述符——与宿主 AgentTool 的四要素（id/name/description/parametersSchema）一一对应。 */
    data class BrowserToolDescriptor(
        val id: String,
        val name: String,
        val description: String,
        val parametersSchema: String
    )

    val tools: List<BrowserToolDescriptor> = listOf(
        BrowserToolDescriptor(
            id = "browser_navigate",
            name = "browser_navigate",
            description = "在内置浏览器中打开 URL 并加载页面（支持 JS 渲染）。可指定 wait_for 等待某个 CSS 选择器出现，避免拿到骨架页。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "url":{"type":"string","description":"目标网址，可带或不带 http(s) 前缀"},
                "new_tab":{"type":"boolean","description":"是否在新标签打开，默认 false"},
                "wait_for":{"type":"string","description":"可选 CSS 选择器，等待该元素出现后再返回（如 .search-results）"},
                "timeout_ms":{"type":"integer","description":"加载/等待超时毫秒，默认 15000"}
            },
            "required":["url"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_snapshot",
            name = "browser_snapshot",
            description = "获取当前页面的结构化快照：可交互元素列表（带稳定 ref，如 r_3k9f）与页面概要。Agent 应先调用本工具了解页面，再用 ref 操作元素。" +
                "可用 focus 指定剪枝策略：form（仅表单元素）/ content（仅文本链接标题）/ 不填或 all（全部交互元素）。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "focus":{"type":"string","description":"剪枝策略: all(默认,全交互元素) | form(仅表单输入) | content(仅文本/链接/标题)"}
            }
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_click",
            name = "browser_click",
            description = "点击页面元素（由 browser_snapshot 给出的 ref 定位）。采用物理触摸注入，比坐标更可靠，适用于按钮/链接/选项/勾选框等。",
            parametersSchema = """{
            "type":"object",
            "properties":{"ref":{"type":"string","description":"browser_snapshot 返回的元素稳定引用，如 r_3k9f"}},
            "required":["ref"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_input",
            name = "browser_input",
            description = "向某输入框（browser_snapshot 给出的 ref）填入文本，适用于搜索框、表单等。会触发 input/change 事件。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"输入框元素的稳定引用"},
                "text":{"type":"string","description":"要填入的文本"}
            },
            "required":["ref","text"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_select",
            name = "browser_select",
            description = "在下拉框（ref 定位）中选择选项：默认按 option 的 value 匹配，by_text=true 时按可见文本匹配。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"select 元素的稳定引用"},
                "value":{"type":"string","description":"要选中的 option 的 value 或可见文本"},
                "by_text":{"type":"boolean","description":"true 时按可见文本匹配，默认 false 按 value"}
            },
            "required":["ref","value"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_toggle",
            name = "browser_toggle",
            description = "切换勾选框 / 单选框（ref 定位），返回切换后状态。",
            parametersSchema = """{
            "type":"object",
            "properties":{"ref":{"type":"string","description":"checkbox/radio 元素的稳定引用"}},
            "required":["ref"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_scroll",
            name = "browser_scroll",
            description = "在页面内滚动，delta_y 为正向下、为负向上。wait_for_new=true 时检测无限滚动新内容加载。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "delta_y":{"type":"integer","description":"滚动像素，正向下负向上，默认 400"},
                "wait_for_new":{"type":"boolean","description":"是否在滚动后等待新内容加载并报告新增元素数"}
            }
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_screenshot",
            name = "browser_screenshot",
            description = "对当前浏览器页面视口截图，返回 PNG 的 base64。供视觉模型理解渲染效果或验证码等图形内容。",
            parametersSchema = """{"type":"object","properties":{}}"""
        ),
        BrowserToolDescriptor(
            id = "browser_show",
            name = "browser_show",
            description = "展开内置浏览器浮窗，进入人工接管模式（用于登录/过验证码）。人类完成后应点击浮窗上的「我已完成操作」交还 Agent。expand=false 收起浮窗。",
            parametersSchema = """{
            "type":"object",
            "properties":{"expand":{"type":"boolean","description":"true 展开并进入接管，false 收起，默认 true"}}
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_file_upload",
            name = "browser_file_upload",
            description = "在文件选择对话框挂起时，由 Agent 提供本地文件路径完成上传。若未处于等待状态，需先用 browser_show 让人手动选择。",
            parametersSchema = """{
            "type":"object",
            "properties":{"path":{"type":"string","description":"本地文件绝对路径"}}
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_date_input",
            name = "browser_date_input",
            description = "为日期/时间类输入框（input[type=date|time|datetime-local]，browser_snapshot 给出的 ref）设置值。" +
                "value 使用 ISO 格式：date 为 YYYY-MM-DD，time 为 HH:MM，datetime-local 为 YYYY-MM-DDTHH:MM。会触发 input/change 事件。",
            parametersSchema = """{
            "type":"object",
            "properties":{
                "ref":{"type":"string","description":"日期/时间输入框元素的稳定引用"},
                "value":{"type":"string","description":"ISO 格式的日期/时间值，如 2026-08-13 或 14:30"}
            },
            "required":["ref","value"]
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_debug_dump",
            name = "browser_debug_dump",
            description = "导出最近 N 步浏览器工具调用的完整 trace（工具名/参数/结果/耗时/URL/状态），用于调试 Agent 行为。",
            parametersSchema = """{
            "type":"object",
            "properties":{"limit":{"type":"integer","description":"导出最近多少步，默认 20，最大 100"}}
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_context_summary",
            name = "browser_context_summary",
            description = "生成浏览器任务进度压缩摘要：最近 3 步保留详情，更早步骤压缩为单行。用于多步任务中控制上下文体积。",
            parametersSchema = """{"type":"object","properties":{}}"""
        ),
        BrowserToolDescriptor(
            id = "browser_network_log",
            name = "browser_network_log",
            description = "读取内置浏览器已发生的网络请求（fetch / XMLHttpRequest）日志，含 method / url / status。可用于判断页面数据是否加载完成，或直接获取 API 响应线索。",
            parametersSchema = """{
            "type":"object",
            "properties":{"limit":{"type":"integer","description":"返回最近 N 条，默认 50"}}
        }"""
        ),
        BrowserToolDescriptor(
            id = "browser_download_list",
            name = "browser_download_list",
            description = "读取内置浏览器最近一次触发的文件下载记录（文件名 / 来源 URL）。网页触发下载后，可用本工具确认下载已发起，再结合系统\"下载\"目录读取文件内容。",
            parametersSchema = """{"type":"object","properties":{}}"""
        )
    )

    /** id → 描述符（executeTool 的入参校验用）。 */
    val byId: Map<String, BrowserToolDescriptor> = tools.associateBy { it.id }
}
