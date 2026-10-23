package com.termagent.deepseekagent.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.termagent.deepseekagent.R
import com.termagent.deepseekagent.core.AgentEvent
import com.termagent.deepseekagent.core.ChatMessage
import com.termagent.deepseekagent.core.ToolCall
import com.termagent.deepseekagent.core.ToolResult
import com.google.gson.Gson

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatItem>()
    private val gson = Gson()

    sealed class ChatItem {
        data class Message(val message: ChatMessage) : ChatItem()
        data class ToolCall(val toolCall: ToolCall, val status: String) : ChatItem()
        data class ToolResult(val result: ToolResult) : ChatItem()
        data class Status(val text: String) : ChatItem()
        data class Error(val text: String) : ChatItem()
    }

    fun addMessage(message: ChatMessage) {
        items.add(ChatItem.Message(message))
        notifyItemInserted(items.size - 1)
    }

    fun addToolCall(toolCall: ToolCall) {
        items.add(ChatItem.ToolCall(toolCall, "executing"))
        notifyItemInserted(items.size - 1)
    }

    fun updateToolCallStatus(toolCallId: String, status: String) {
        items.indexOfFirst { it is ChatItem.ToolCall && it.toolCall.id == toolCallId }.also { idx ->
            if (idx >= 0) {
                val item = items[idx] as ChatItem.ToolCall
                items[idx] = ChatItem.ToolCall(item.toolCall, status)
                notifyItemChanged(idx)
            }
        }
    }

    fun addToolResult(result: ToolResult) {
        items.add(ChatItem.ToolResult(result))
        notifyItemInserted(items.size - 1)
    }

    fun addStatus(text: String) {
        items.add(ChatItem.Status(text))
        notifyItemInserted(items.size - 1)
    }

    fun addError(text: String) {
        items.add(ChatItem.Error(text))
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ChatItem.Message -> 0
            is ChatItem.ToolCall -> 1
            is ChatItem.ToolResult -> 2
            is ChatItem.Status -> 3
            is ChatItem.Error -> 4
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            0 -> MessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false))
            1 -> ToolCallViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_tool_call, parent, false))
            2, 3, 4 -> MessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false))
            else -> MessageViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is MessageViewHolder -> {
                holder.bind(item)
            }
            is ToolCallViewHolder -> {
                holder.bind(item as ChatItem.ToolCall)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val roleLabel: TextView = view.findViewById(R.id.roleLabel)
        private val messageText: TextView = view.findViewById(R.id.messageText)

        fun bind(item: ChatItem) {
            when (item) {
                is ChatItem.Message -> {
                    val msg = item.message
                    roleLabel.text = when (msg.role) {
                        "user" -> "You"
                        "assistant" -> "DeepSeek"
                        "system" -> "System"
                        "tool" -> "Tool (${msg.name})"
                        else -> msg.role
                    }
                    roleLabel.setTextColor(when (msg.role) {
                        "user" -> itemView.context.getColor(R.color.primary)
                        "assistant" -> itemView.context.getColor(R.color.accent_green)
                        "tool" -> itemView.context.getColor(R.color.accent_orange)
                        "error" -> itemView.context.getColor(R.color.accent_red)
                        else -> itemView.context.getColor(R.color.text_secondary)
                    })
                    messageText.text = msg.content ?: ""
                }
                is ChatItem.Status -> {
                    roleLabel.text = "Status"
                    roleLabel.setTextColor(itemView.context.getColor(R.color.text_secondary))
                    messageText.text = item.text
                }
                is ChatItem.Error -> {
                    roleLabel.text = "Error"
                    roleLabel.setTextColor(itemView.context.getColor(R.color.accent_red))
                    messageText.text = item.text
                }
                is ChatItem.ToolResult -> {
                    roleLabel.text = if (item.result.isError) "Tool Error" else "Tool Result"
                    roleLabel.setTextColor(if (item.result.isError) itemView.context.getColor(R.color.accent_red) else itemView.context.getColor(R.color.accent_green))
                    messageText.text = item.result.content
                }
            }
        }
    }

    inner class ToolCallViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val toolName: TextView = view.findViewById(R.id.toolName)
        private val toolStatus: TextView = view.findViewById(R.id.toolStatus)
        private val toolArgs: TextView = view.findViewById(R.id.toolArgs)

        fun bind(item: ChatItem.ToolCall) {
            toolName.text = item.toolCall.function.name
            toolStatus.text = item.status.uppercase()
            toolStatus.setTextColor(when (item.status) {
                "executing" -> itemView.context.getColor(R.color.accent_orange)
                "completed" -> itemView.context.getColor(R.color.accent_green)
                "failed" -> itemView.context.getColor(R.color.accent_red)
                else -> itemView.context.getColor(R.color.text_secondary)
            })
            toolArgs.text = item.toolCall.function.arguments
        }
    }
}