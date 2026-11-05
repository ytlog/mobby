package com.github.ytlog.mobby.android.conversation.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal const val ScreenSkillRef = "plugin:device:screen"

private val screenRequestExclusions = Regex(
    listOf(
        """^(?:请问|为什么|如何|怎么|怎样|什么是|(?:请|帮我)?(?:不要|不用|别))""",
        """^(?:请|帮我)?(?:写|开发|实现|设计|修改|分析|解释|翻译)""",
        """^(?:how|what|why|(?:please\s+)?(?:do not|don't|write|build|implement|explain|analy[sz]e))\b""",
    ).joinToString("|"),
    RegexOption.IGNORE_CASE,
)

private val screenRequestActions = Regex(
    listOf(
        """(?:操作|控制|查看|读取|滑动|滚动|点击|截取).{0,10}(?:虚拟屏|副屏|屏幕|手机界面|平板界面|手机)""",
        """(?:点(?:击|按)?|轻触|按下).{0,10}(?:按钮|图标|选项|菜单|屏幕)""",
        """(?:在|用)(?:这台|我的)?(?:手机|平板|副屏|虚拟屏)(?:上|里)?.{0,16}(?:打开|输入|查|搜索|点|滑|运行)""",
        """(?:打开|启动|进入).{0,16}(?:微信|支付宝|相机|浏览器|手机设置|系统设置|设置页面|蓝牙设置|应用程序|App\b)""",
        """(?:进入|打开)(?:一下)?设置(?:[，。\s]|$)""",
        """^(?:请|帮我|给我|我要|我想|能不能)*\s*(?:截(?:个|一张)?(?:屏|图)|拍(?:个|一张)屏幕)""",
        """\b(?:take|capture)\s+(?:a\s+)?screenshot\b""",
        """\b(?:tap|click|press|scroll|swipe|read|control)\b.{0,32}\b(?:screen|display|button|icon|menu|phone)\b""",
        """\b(?:open|launch)\b.{0,32}\b(?:app|settings|camera|browser)\b""",
    ).joinToString("|"),
    RegexOption.IGNORE_CASE,
)

internal fun requestsScreenSkill(text: String): Boolean {
    val request = text.trim()
    return !screenRequestExclusions.containsMatchIn(request) && screenRequestActions.containsMatchIn(request)
}

/** Shows one offer per matching stretch of a draft. Sending can surface it before the typing delay. */
@Composable
internal fun rememberScreenSkillSuggestion(
    conversation: String,
    draft: String,
    eligible: Boolean,
    onAdd: () -> Unit,
): (String) -> Boolean {
    var offered by rememberSaveable(conversation) { mutableStateOf(false) }
    var visible by rememberSaveable(conversation) { mutableStateOf(false) }
    LaunchedEffect(conversation, draft, eligible, offered) {
        if (!requestsScreenSkill(draft)) {
            visible = false
            offered = false
        } else if (eligible && !offered) {
            delay(800)
            offered = true
            visible = true
        }
    }
    if (visible && eligible && requestsScreenSkill(draft)) {
        AlertDialog(
            onDismissRequest = { visible = false },
            containerColor = raisedColor(),
            shape = RoundedCornerShape(24.dp),
            title = { Text(UiStrings.suggestScreenSkillTitle) },
            text = { Text(UiStrings.suggestScreenSkillBody) },
            confirmButton = {
                TextButton(onClick = { visible = false; onAdd() }) { Text(UiStrings.suggestScreenSkillAdd) }
            },
            dismissButton = {
                TextButton(onClick = { visible = false }) { Text(UiStrings.cancel) }
            },
        )
    }
    return { text ->
        if (eligible && requestsScreenSkill(text) && (!offered || visible)) {
            offered = true
            visible = true
            true
        } else false
    }
}
