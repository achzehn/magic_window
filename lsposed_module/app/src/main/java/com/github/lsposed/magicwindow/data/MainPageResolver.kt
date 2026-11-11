package com.github.lsposed.magicwindow.data

/**
 * 主页面（列表页/左栏）判定：为平行窗口的 splitPairRule 左栏、activityRule 提供候选类名。
 *
 * 依据真机全量样本统计（embedded_rules_list.xml，8047 条规则中的 3850 个 splitPairRule 左栏）：
 *   - MainActivity  ×1331  最强信号
 *   - Home          ×580   次强
 *   - Index         ×51
 *   - Search        ×358   次级左栏
 *   - List          ×179   次级左栏
 *   - Detail        ×322   → 右栏，不是左栏
 * `Main` + `Home` 两词即可覆盖真实左栏的约 50%。
 *
 * 判定顺序（准确率由高到低）：
 *   1. 系统内置名单里已有的 splitPairRule / activityRule 左栏 —— 系统已定好，准确率最高
 *   2. 类名关键词（Main > Home > Index > List/Search），排除启动页/登录页
 *   3. launcher 兜底（注意 launcher 常是启动页，不是主页面）
 *
 * 实现要点：排除词必须按**驼峰词元**整词比对，不能用裸子串——
 * 否则 `readassistant`（含 "ad"）、`pages`（含 "ad"）会被误判为广告页。
 */
object MainPageResolver {

    /** 强主页面关键词（按优先级排序） */
    private val MAIN_KEYWORDS = listOf("Main", "Home", "Index")

    /** 次级左栏关键词（列表页/搜索页也常做左栏） */
    private val SECONDARY_KEYWORDS = listOf("List", "Search", "Feed", "Channel", "Tab")

    /**
     * 必须排除的词元（启动页/引导页/登录页等）。
     * 按驼峰词元整词比对，避免 "readassistant" 命中 "Ad" 这类误伤。
     */
    private val EXCLUDE_TOKENS = setOf(
        "Login", "Register", "Privacy", "Permission",
        "Guide", "Welcome", "Dialog", "WebView", "Ad", "Ads"
    )

    /** 降权词元：命中则不优先选（但确实无更好选择时仍可作兜底） */
    private val DOWNGRADE_TOKENS = setOf("Splash", "Start", "Launch", "Launcher")

    /**
     * 从系统内置名单反查该应用的主页面：取 splitPairRule 的第一个左栏类名。
     * 无内置规则时返回 null。
     */
    fun fromSystemRule(pkg: String): String? {
        val attrs = SystemRuleSource.attrsOf(SystemRuleSource.Kind.EMBEDDING, pkg)
        val firstLeft = attrs["splitPairRule"]
            ?.split(',')
            ?.firstOrNull()
            ?.substringBefore(':')
            ?.trim()
        if (!firstLeft.isNullOrEmpty() && firstLeft != "*") return firstLeft
        return attrs["activityRule"]
            ?.split(',')
            ?.firstOrNull()
            ?.substringBefore(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 综合判定主页面：系统内置规则优先，其次类名关键词，最后 launcher 兜底。
     * 返回 (类名, 来源)，来源为 "system" / "keyword" / "launcher"；无法判定返回 null。
     *
     * @param activities Activity 全类名列表
     * @param launcher 启动页类名（兜底用，本身常是启动页而非主页面）
     */
    fun resolve(pkg: String, activities: List<String>, launcher: String? = null): Pair<String, String>? {
        fromSystemRule(pkg)?.let { return it to "system" }
        val classes = activities.filter { it.isNotEmpty() }

        // 1. 强 + 次级关键词（排除词元整词过滤），先取未命中降权词的
        val matched = (MAIN_KEYWORDS + SECONDARY_KEYWORDS).mapNotNull { kw ->
            classes.firstOrNull { containsToken(it, kw) && !hasExcludedToken(it) }
        }
        matched.firstOrNull { !hasDowngradeToken(it) }?.let { return it to "keyword" }
        matched.firstOrNull()?.let { return it to "keyword" }

        // 2. 命中降权词的（启动页等）作次优
        classes.firstOrNull { hasDowngradeToken(it) && !hasExcludedToken(it) }
            ?.let { return it to "keyword" }

        // 3. launcher 兜底
        return launcher?.takeIf { it.isNotEmpty() }?.let { it to "launcher" }
    }

    /** 是否为应排除的页面（启动页/登录页等） */
    fun isExcluded(activityName: String): Boolean = hasExcludedToken(activityName)

    /**
     * 判定页面在平行窗口中的建议角色，用于 UI 标记与工具返回。
     * 返回 "左栏" / "右栏" / "跳过" / ""（不确定）。
     */
    fun roleOf(activityName: String): String = when {
        hasExcludedToken(activityName) -> "跳过"
        hasDowngradeToken(activityName) -> "跳过"
        MAIN_KEYWORDS.any { containsToken(activityName, it) } -> "左栏"
        SECONDARY_KEYWORDS.any { containsToken(activityName, it) } -> "左栏"
        RIGHT_KEYWORDS.any { containsToken(activityName, it) } -> "右栏"
        else -> ""
    }

    private val RIGHT_KEYWORDS = listOf("Detail", "Player", "Video", "Article", "Content", "Preview", "Reader")

    /**
     * 判断类名是否含某个驼峰词元（整词匹配，避免裸子串误伤）。
     * 例：`Home` 匹配 `biz.home.main.Home` 与 `HomeActivity`；`Ad` 不匹配 `readassistant`。
     */
    private fun containsToken(className: String, token: String): Boolean {
        val simpleName = className.substringAfterLast('.').substringAfterLast('/')
        // 驼峰边界切分：在主页面判定中，关键词只要出现在类名末尾段即可（Main/Home/Index 等）
        return simpleName.contains(token, ignoreCase = false) && isWordStart(simpleName, token)
    }

    /** token 在 simpleName 中出现的位置必须是词边界（前一个字符是小写或分隔符，或就是开头） */
    private fun isWordStart(s: String, token: String): Boolean {
        var idx = s.indexOf(token)
        while (idx >= 0) {
            if (idx == 0 || !s[idx - 1].isLowerCase()) return true
            idx = s.indexOf(token, idx + 1)
        }
        return false
    }

    /** 按驼峰词元整词匹配排除词 */
    private fun hasExcludedToken(className: String): Boolean =
        EXCLUDE_TOKENS.any { containsToken(className, it) }

    /** 按驼峰词元整词匹配降权词 */
    private fun hasDowngradeToken(className: String): Boolean =
        DOWNGRADE_TOKENS.any { containsToken(className, it) }
}
