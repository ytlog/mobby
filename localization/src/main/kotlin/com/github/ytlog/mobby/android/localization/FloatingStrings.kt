package com.github.ytlog.mobby.android.localization

/** Copy shared by the native bubble and its Compose conversation window. */
object FloatingStrings {
    private fun text(zh: String, en: String) = if (AppLanguage.current == AppLanguage.CHINESE) zh else en
    val quickChat get() = text("悬浮对话", "Floating chat")
    val welcome get() = text("随时问，随手看", "A little window for big ideas")
    val welcomeDetail get() = text("聊一个问题，或让我看看当前屏幕。", "Ask a question, or let me read your screen.")
    val recognizeScreen get() = text("识别屏幕", "Read screen")
    val screenPrompt get() = text("请读取当前手机屏幕，识别并说明屏幕上的内容。只观察，不点击、输入或修改。", "Read the current phone screen and explain its contents. Observe only; do not click, type, or modify anything.")
    val inputHint get() = text("输入问题，或点击识别屏幕", "Ask a question, or read the screen")
    val close get() = text("收起对话", "Collapse chat")
    val draftChanged get() = text("草稿或会话已改变，未发送屏幕识别请求，请重试", "The draft or conversation changed. Screen reading was not sent; please retry.")
    val openFailed get() = text("无法打开悬浮对话，请检查悬浮窗权限", "Could not open floating chat. Check overlay permission.")
}
