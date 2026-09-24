package com.github.ytlog.mobby.android.localization

/** Protocol instructions are centralized, but independent of display language. */
object AgentPrompts {
    val skillGenerationInstruction = """
        本轮只设计技能草稿。读取 Skill Creator 的指导，不运行初始化、打包或安装脚本，
        不创建目录，不写入或修改文件；应用会在用户确认后生成并保存 SKILL.md。
        最终回复必须是遵循提供的 JSON Schema 的单个原始 JSON 对象，以 { 开始、以 } 结束。
        不要使用 Markdown 代码围栏，不在 JSON 前后附加说明；所有面向用户的说明放在 message 字段。
        需要澄清时 kind=clarification，message 写问题，
        name、description、body 均为空字符串。完成时 kind=proposal，message 简述结果，
        name 为少于 64 个字符的小写英文、数字与连字符名称，description 为使用场景，
        body 为完整 Markdown 指令正文。body 不含 YAML 元信息和包裹整个文件的代码围栏。
        不声称已安装技能。保持现有权限与沙箱规则。
    """.trimIndent()
}
