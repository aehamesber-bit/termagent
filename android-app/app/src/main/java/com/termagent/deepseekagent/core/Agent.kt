package com.termagent.deepseekagent.core

import com.google.gson.Gson
import com.termagent.deepseekagent.bridge.DeepSeekBridge
import com.termagent.deepseekagent.core.tools.ToolRegistry
import kotlinx.coroutines.*

class Agent(
    private val config: AgentConfig,
    private val bridge: DeepSeekBridge,
    private val context: ToolContext,
    private val scope: CoroutineScope
) {

    private val messages = mutableListOf<ChatMessage>()
    private val gson = Gson()
    private var isRunning = false
    private var currentTurn = 0

    interface AgentListener {
        fun onEvent(event: AgentEvent)
    }

    private var listener: AgentListener? = null

    fun setListener(listener: AgentListener) {
        this.listener = listener
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        ToolRegistry.init(context)
        addSystemPrompt()
        scope.launch { runAgentLoop() }
    }

    fun stop() {
        isRunning = false
    }

    fun sendUserMessage(text: String) {
        messages.add(ChatMessage.user(text))
        scope.launch { runAgentLoop() }
    }

    private fun addSystemPrompt() {
        val systemText = if (config.systemPrompt.isNotBlank()) {
            config.systemPrompt
        } else {
            defaultSystemPrompt()
        }
        val workspaceInfo = "\nEnvironment:\n- workspace: ${context.workspacePath}\n- platform: Android\nTools available: ${ToolRegistry.all().joinToString(", ") { it.name }}."
        messages.add(ChatMessage.system(systemText + workspaceInfo))
    }

    private fun defaultSystemPrompt(): String {
        return """You are DeepSeek Agent, an interactive coding agent running on Android.
You help with software engineering tasks by using tools.

Operating principles:
- Be concise. Do not add commentary the user did not ask for.
- Use tools to inspect the environment before assuming anything.
- Prefer reading files before editing them; never guess file contents.
- When editing files, preserve existing style and conventions.
- Do not add comments to code unless asked.
- Do not run destructive commands unless the user asks.
- When a task needs multiple steps, use the todo tool to track them.
- Answer in plain text; use markdown sparingly.
"""
    }

    private suspend fun runAgentLoop() {
        while (isRunning && currentTurn < config.maxTurns) {
            currentTurn++
            
            val schemas = ToolRegistry.schemas()
            val response = sendToDeepSeek(messages, schemas)
            
            if (response == null) {
                emit(AgentEvent.Error("Failed to get response from DeepSeek"))
                break
            }

            val assistantMsg = ChatMessage.assistant(response.content)
            if (response.toolCalls.isNotEmpty()) {
                assistantMsg.toolCalls = response.toolCalls
            }
            messages.add(assistantMsg)
            
            emit(AgentEvent.AssistantDone(response.content))

            if (response.toolCalls.isEmpty()) {
                break
            }

            for (toolCall in response.toolCalls) {
                val fn = toolCall.function
                val args = parseArgs(fn.arguments)
                
                emit(AgentEvent.ToolStart(fn.name, args))
                
                val tool = ToolRegistry.get(fn.name)
                val result = if (tool != null) {
                    tool.execute(args, context)
                } else {
                    ToolResult("error: unknown tool '${fn.name}'", true)
                }
                
                emit(AgentEvent.ToolResult(fn.name, args, result.content, result.isError))
                
                messages.add(ChatMessage.tool(result.content, toolCall.id, fn.name))
            }
        }
        
        if (currentTurn >= config.maxTurns) {
            emit(AgentEvent.Error("Max turns reached; stopping to avoid runaway tool use."))
        }
    }

    private suspend fun sendToDeepSeek(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>
    ): DeepSeekResponse? {
        return suspendCancellableCoroutine { cont ->
            val jsonMessages = messages.map { msg ->
                val map = mutableMapOf<String, Any>("role" to msg.role)
                msg.content?.let { map["content"] = it }
                msg.toolCalls?.let { map["tool_calls"] = it }
                msg.toolCallId?.let { map["tool_call_id"] = it }
                msg.name?.let { map["name"] = it }
                map
            }

            // Send via bridge - we need to format for DeepSeek web chat
            val userContent = jsonMessages.lastOrNull()?.content as? String ?: ""
            bridge.sendMessage(userContent)
            
            // Get response
            bridge.getResponse { response ->
                // Parse response for tool calls and content
                val parsed = parseDeepSeekResponse(response, tools)
                cont.resume(parsed)
            }
        }
    }

    private fun parseDeepSeekResponse(response: String, tools: List<Map<String, Any>>): DeepSeekResponse {
        // Try to extract tool calls from response
        // DeepSeek web chat doesn't return structured tool calls
        // We need to parse the text response for tool invocations
        
        // For now, treat as plain text response
        // In a real implementation, we'd use a specific format or function calling
        return DeepSeekResponse(response, emptyList())
    }

    private fun parseArgs(raw: String?): Map<String, Any> {
        if (raw == null || raw.isBlank()) return emptyMap()
        return try {
            gson.fromJson(raw, Map::class.java)
        } catch (e: Exception) {
            mapOf("_raw" to raw)
        }
    }

    private fun emit(event: AgentEvent) {
        listener?.onEvent(event)
    }

    data class DeepSeekResponse(
        val content: String,
        val toolCalls: List<ToolCall>
    )
}