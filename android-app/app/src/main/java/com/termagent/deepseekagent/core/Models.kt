package com.termagent.deepseekagent.core

import com.google.gson.annotations.SerializedName

data class AgentConfig(
    @SerializedName("api_key") var apiKey: String = "",
    @SerializedName("base_url") var baseUrl: String = "https://chat.deepseek.com",
    @SerializedName("model") var model: String = "deepseek-chat",
    @SerializedName("system") var systemPrompt: String = "",
    @SerializedName("max_turns") var maxTurns: Int = 24,
    @SerializedName("auto_approve") var autoApprove: Boolean = false,
    @SerializedName("workspace_path") var workspacePath: String = "",
    @SerializedName("use_xbrowser") var useXBrowser: Boolean = false,
    @SerializedName("xbrowser_package") var xbrowserPackage: String = "com.xbrowser.play"
) {
    fun toJson(): String = com.google.gson.Gson().toJson(this)

    companion object {
        fun fromJson(json: String): AgentConfig = com.google.gson.Gson().fromJson(json, AgentConfig::class.java)
    }
}

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionCall
)

data class FunctionCall(
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String
)

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String? = null,
    @SerializedName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id") val toolCallId: String? = null,
    @SerializedName("name") val name: String? = null
) {
    companion object {
        fun user(content: String) = ChatMessage("user", content)
        fun assistant(content: String) = ChatMessage("assistant", content)
        fun system(content: String) = ChatMessage("system", content)
        fun tool(content: String, toolCallId: String, name: String) = ChatMessage("tool", content, null, toolCallId, name)
    }
}

sealed class AgentEvent {
    data class Text(val content: String) : AgentEvent()
    data class ToolStart(val name: String, val args: Map<String, Any>) : AgentEvent()
    data class ToolResult(val name: String, val args: Map<String, Any>, val result: String, val isError: Boolean) : AgentEvent()
    data class AssistantDone(val content: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Meta(val key: String, val value: Any) : AgentEvent()
    data class Status(val message: String) : AgentEvent()
}

data class ToolResult(
    val content: String,
    val isError: Boolean = false
)

interface Tool {
    val name: String
    val description: String
    val parameters: Map<String, Any> // JSON schema
    val readOnly: Boolean = false

    fun execute(args: Map<String, Any>, context: ToolContext): ToolResult
}

data class ToolContext(
    val workspacePath: String,
    val approveCallback: ((String, String) -> Boolean)? = null,
    val emitCallback: ((String) -> Unit)? = null
)