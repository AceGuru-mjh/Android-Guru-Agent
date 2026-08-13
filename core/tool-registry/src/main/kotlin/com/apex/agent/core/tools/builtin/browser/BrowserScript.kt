package com.apex.agent.core.tools.builtin.browser

/**
 * 注入到 WebView 的 JS 片段（纯字符串，无 Android 依赖）。
 *
 * [SNAPSHOT_JS] 遍历可见可交互元素并序列化回传，[HIGHLIGHT_JS] 在调试时高亮某个 ref。
 * 返回格式与 [RawDomElement] 对齐，由 [DomParser] 解析。
 *
 * 2026 裁决要点：
 * - 稳定 ref 改为「语义哈希」`r_<hash>`（基于 role+text+tag+相对位置），写入 `data-apex-hash`，
 *   抗 SPA 局部刷新错位（替代旧的顺序/属性混合 ref `data-apex-ref`）。
 * - JS 层启发式剪枝：跳过不可见、非交互无文本、零尺寸节点；硬上限 [SNAPSHOT_MAX_ELEMENTS]。
 * - 返回的每个元素都带 `data-apex-hash`，供后续 DOM 级定位与物理触摸注入使用。
 */
object BrowserScript {

    /** 单次快照元素硬上限（Token 预算保护，超过即截断） */
    const val SNAPSHOT_MAX_ELEMENTS: Int = 50

    /**
     * 抓取当前**视口内**可见可交互元素，返回 JSON 数组字符串（需用 JSON.parse 还原）。
     * 与早期全量快照不同，本脚本在 JS 层完成过滤 + 语义哈希，极大减少 IPC 体积。
     *
     * @param strategy 剪枝策略（#19/#20）：传入 [DomParser.SnapshotStrategy]，
     *   不同策略收窄查询选择器，进一步降低回传体积。
     */
    fun snapshotJs(strategy: DomParser.SnapshotStrategy = DomParser.SnapshotStrategy.INTERACTIVE_ONLY): String {
        val sel = when (strategy) {
            DomParser.SnapshotStrategy.INTERACTIVE_ONLY ->
                "a,button,input,select,textarea,summary,area[href]," +
                "[role=button],[role=link],[role=tab],[role=option],[role=checkbox],[role=radio],[role=switch]"
            DomParser.SnapshotStrategy.FORM_FIELDS ->
                "input,select,textarea,[role=checkbox],[role=radio],[role=switch],[role=textbox],[role=searchbox]"
            DomParser.SnapshotStrategy.CONTENT_SUMMARY ->
                "a[href],h1,h2,h3,h4,p,li,[role=heading],[role=link]"
        }
        return """
        (function(){
          function hash(str){
            var h = 0;
            for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; }
            return 'r_' + (Math.abs(h)>>>0).toString(36);
          }
          var MAX = $SNAPSHOT_MAX_ELEMENTS;
          var out = [];
          var interactiveSel = ${"'$sel'"};
          var all = document.querySelectorAll(interactiveSel);
          for (var i=0;i<all.length;i++){
            if (out.length >= MAX) break;
            var el = all[i];
            var style = window.getComputedStyle(el);
            // 启发式剪枝：不可见 / 透明 / 脱离布局 / 零尺寸
            var visible = style.display !== 'none' && style.visibility !== 'hidden'
                  && parseFloat(style.opacity) > 0.05 && el.offsetParent !== null;
            if (!visible) continue;
            var rect = el.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) continue;
            var text = (el.innerText || el.value || el.placeholder || '').replace(/\s+/g,' ').trim();
            // 非交互且无文本 -> 纯布局噪音，跳过
            if (text.length === 0 && !el.hasAttribute('aria-label') && !el.hasAttribute('placeholder')
                && el.tagName !== 'INPUT' && el.tagName !== 'SELECT' && el.tagName !== 'TEXTAREA') continue;
            text = text.slice(0, 120);
            // 语义哈希 ref：role + 文本 + 标签 + 相对顶部位置（抗 SPA 局部刷新错位）
            var role = el.getAttribute('role') || el.tagName.toLowerCase();
            var semanticKey = role + '|' + text + '|' + el.tagName + '|' + Math.round(rect.top + window.scrollY);
            var ref = hash(semanticKey);
            el.setAttribute('data-apex-hash', ref);
            var attrs = { 'data-apex-hash': ref };
            var keep = ['href','name','type','placeholder','value','aria-label','title','role','alt','id'];
            for (var a=0;a<el.attributes.length;a++){
              var an = el.attributes[a].name; if (keep.indexOf(an)>=0) attrs[an] = el.attributes[a].value;
            }
            out.push({
              tag: el.tagName,
              text: text,
              attributes: attrs,
              rect: { x: Math.round(rect.left + window.scrollX), y: Math.round(rect.top + window.scrollY),
                      width: Math.round(rect.width), height: Math.round(rect.height) },
              isVisible: true,
              isInteractive: true,
              depth: 0,
              childCount: el.children.length
            });
          }
          return JSON.stringify(out);
        })();
        """.trimIndent()
    }

    /**
     * A11y 补充源（#17）：当主快照交互元素过少（CSP 阻止 JS 或页面极简）时，
     * 用 ARIA 角色补充语义元素（含有 accessible name 的容器），作为 DOM 快照的降级源。
     */
    val A11Y_FALLBACK_JS: String
        get() = """
        (function(){
          function hash(str){
            var h = 0;
            for (var i=0;i<str.length;i++){ var c = str.charCodeAt(i); h = ((h<<5)-h)+c; h|=0; }
            return 'r_' + (Math.abs(h)>>>0).toString(36);
          }
          var MAX = $SNAPSHOT_MAX_ELEMENTS;
          var out = [];
          // 有 accessible name 的节点：role / aria-label / 文本 任一即可
          var all = document.querySelectorAll('*');
          for (var i=0;i<all.length && out.length<MAX;i++){
            var el = all[i];
            var role = el.getAttribute && el.getAttribute('role');
            var name = (el.getAttribute && (el.getAttribute('aria-label')||el.getAttribute('title'))) ||
                       (el.innerText || '').replace(/\\s+/g,' ').trim().slice(0,80);
            if (!role && (!name || name.length===0)) continue;
            // 跳过纯布局容器（无语义 role 且无标签）
            if (!role && el.children.length>0 && el.innerText.trim().length>120) continue;
            var rect = el.getBoundingClientRect();
            if (rect.width===0 || rect.height===0) continue;
            var text = (name||'').toString().slice(0,120);
            var semanticKey = (role||el.tagName) + '|' + text + '|' + el.tagName;
            var ref = hash(semanticKey);
            el.setAttribute('data-apex-hash', ref);
            out.push({
              tag: el.tagName,
              text: text,
              attributes: { 'data-apex-hash': ref, 'role': role||'', 'aria-label': (el.getAttribute&&el.getAttribute('aria-label'))||'' },
              rect: { x: Math.round(rect.left), y: Math.round(rect.top), width: Math.round(rect.width), height: Math.round(rect.height) },
              isVisible: true,
              isInteractive: !!role,
              depth: 0,
              childCount: el.children.length
            });
          }
          return JSON.stringify(out);
        })();
        """.trimIndent()

    /**
     * 网络监控（#18）：拦截 fetch / XMLHttpRequest，记录 API 请求到 window.__apexNetLog，
     * 供 [browser_network_log] 工具读取。注入一次即可持续生效。
     */
    val NETWORK_MONITOR_JS: String
        get() = """
        (function(){
          if (window.__apexNetHooked) return;
          window.__apexNetLog = window.__apexNetLog || [];
          window.__apexNetHooked = true;
          function rec(method, url, status){
            window.__apexNetLog.push({ method: method, url: (url||'').toString().slice(0,200), status: status||0, t: Date.now() });
            if (window.__apexNetLog.length > 200) window.__apexNetLog.shift();
          }
          var origFetch = window.fetch;
          if (origFetch) window.fetch = function(){
            var args = arguments; var u = args[0];
            return origFetch.apply(this, args).then(function(r){ rec('fetch', u, r.status); return r; }, function(e){ rec('fetch', u, 0); throw e; });
          };
          var origXhr = window.XMLHttpRequest.prototype.open;
          window.XMLHttpRequest.prototype.open = function(m,u){ this.__apexMethod=m; this.__apexUrl=u; return origXhr.apply(this, arguments); };
          var origSend = window.XMLHttpRequest.prototype.send;
          window.XMLHttpRequest.prototype.send = function(){
            var self=this; var mu=this.__apexMethod, ul=this.__apexUrl;
            this.addEventListener('loadend', function(){ rec(mu, ul, self.status); });
            return origSend.apply(this, arguments);
          };
        })();
        """.trimIndent()

    /** 物理触摸注入：返回元素在**屏幕坐标系**中的中心点（含 WebView 自身偏移），失败返回 null */
    fun rectByRefJs(ref: String): String =
        """
        (function(){
          var el = document.querySelector('[data-apex-hash=${ref.toJsonString()}]');
          if (!el) return JSON.stringify(null);
          var r = el.getBoundingClientRect();
          return JSON.stringify({ x: r.left + r.width/2, y: r.top + r.height/2,
                                  left: r.left, top: r.top, width: r.width, height: r.height });
        })();
        """.trimIndent()

    /** 点击后验证：对比点击前后页面状态（URL / 标题 / 可交互元素数量） */
    val POST_ACTION_PROBE_JS: String
        get() = """
        (function(){
          return JSON.stringify({
            url: location.href,
            title: document.title,
            interactiveCount: document.querySelectorAll('a,button,input,select,textarea,[role=button],[role=link],[role=tab],[role=option],[role=checkbox],[role=radio],[role=switch]').length,
            scrollY: window.scrollY
          });
        })();
        """.trimIndent()

    /** 等待目标 CSS 选择器出现（用于 navigate 的 wait_for 参数），超时由 Kotlin 层控制 */
    fun waitForSelectorJs(selector: String): String =
        """
        (function(){
          return new Promise(function(resolve){
            var el = document.querySelector(${selector.toJsonString()});
            if (el) return resolve(true);
            var t = setInterval(function(){
              var e = document.querySelector(${selector.toJsonString()});
              if (e){ clearInterval(t); resolve(true); }
            }, 200);
            setTimeout(function(){ clearInterval(t); resolve(false); }, 10000);
          });
        })();
        """.trimIndent()

    /** 下拉选择：按 value 或可见文本设置 <select> 并触发 change */
    fun selectJs(ref: String, value: String, byText: Boolean): String {
        val match = if (byText) "opt.text" else "opt.value"
        return """
        (function(){
          var el = document.querySelector('[data-apex-hash=${ref.toJsonString()}]');
          if (!el || el.tagName !== 'SELECT') return false;
          var opts = el.options;
          for (var i=0;i<opts.length;i++){
            var opt = opts[i];
            if (($match) === ${value.toJsonString()}){ el.selectedIndex = i; el.dispatchEvent(new Event('change',{bubbles:true})); return true; }
          }
          return false;
        })();
        """
    }

    /** 高亮指定 ref 对应的元素（调试 / 可视化） */
    fun highlightJs(ref: String, color: String = "#1e90ff"): String =
        """
        (function(){
          var els = document.querySelectorAll('[data-apex-hash="$ref"]');
          for (var i=0;i<els.length;i++){ els[i].style.outline='2px solid $color'; }
        })();
        """.trimIndent()
}

/** 把字符串安全包成 JS 单引号字面量 */
private fun String.toJsonString(): String = "'$this'"
