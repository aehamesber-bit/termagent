package com.termagent.deepseekagent.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.termagent.deepseekagent.R
import com.termagent.deepseekagent.bridge.DeepSeekBridge
import com.termagent.deepseekagent.core.Agent
import com.termagent.deepseekagent.core.AgentConfig
import com.termagent.deepseekagent.core.ChatMessage
import com.termagent.deepseekagent.core.ToolCall
import com.termagent.deepseekagent.core.ToolContext
import com.termagent.deepseekagent.core.ToolResult
import com.termagent.deepseekagent.core.AgentEvent
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ChatAdapter
    private lateinit var messageInput: EditText
    private lateinit var sendButton: FloatingActionButton
    private lateinit var connectionStatus: LinearLayout
    private lateinit var statusIcon: TextView
    private lateinit var statusText: TextView
    private lateinit var connectBtn: android.widget.Button
    private lateinit var approvalBar: LinearLayout
    private lateinit var approvalText: TextView
    private lateinit var approveBtn: android.widget.Button
    private lateinit var denyBtn: android.widget.Button

    private var agent: Agent? = null
    private var bridge: DeepSeekBridge? = null
    private var config: AgentConfig = AgentConfig()
    private var pendingToolCall: ToolCall? = null
    private var pendingToolArgs: Map<String, Any>? = null
    private var approveCallback: ((Boolean) -> Unit)? = null

    private val prefsName = "DeepSeekAgentPrefs"
    private val workspaceKey = "workspace_path"
    private val autoApproveKey = "auto_approve"
    private val useXBrowserKey = "use_xbrowser"
    private val xbrowserPackageKey = "xbrowser_package"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadConfig()
        setupRecyclerView()
        setupListeners()
        initWorkspace()
    }

    private fun initViews() {
        setSupportActionBar(findViewById(R.id.toolbar))
        adapter = ChatAdapter()
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        connectionStatus = findViewById(R.id.connectionStatus)
        statusIcon = findViewById(R.id.statusIcon)
        statusText = findViewById(R.id.statusText)
        connectBtn = findViewById(R.id.connectBtn)
        approvalBar = findViewById(R.id.approvalBar)
        approvalText = findViewById(R.id.approvalText)
        approveBtn = findViewById(R.id.approveBtn)
        denyBtn = findViewById(R.id.denyBtn)
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        sendButton.setOnClickListener { sendMessage() }
        
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        connectBtn.setOnClickListener { connectToDeepSeek() }

        approveBtn.setOnClickListener {
            approveCallback?.invoke(true)
            approveCallback = null
            hideApprovalBar()
        }

        denyBtn.setOnClickListener {
            approveCallback?.invoke(false)
            approveCallback = null
            hideApprovalBar()
        }

        findViewById<FloatingActionButton>(R.id.fabMenu).setOnClickListener { showMenu() }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        config = AgentConfig(
            workspacePath = prefs.getString(workspaceKey, File(filesDir, "workspace").absolutePath),
            autoApprove = prefs.getBoolean(autoApproveKey, false),
            useXBrowser = prefs.getBoolean(useXBrowserKey, false),
            xbrowserPackage = prefs.getString(xbrowserPackageKey, "com.xbrowser.play") ?: "com.xbrowser.play"
        )
    }

    private fun saveConfig() {
        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
        prefs.putString(workspaceKey, config.workspacePath)
        prefs.putBoolean(autoApproveKey, config.autoApprove)
        prefs.putBoolean(useXBrowserKey, config.useXBrowser)
        prefs.putString(xbrowserPackageKey, config.xbrowserPackage)
        prefs.apply()
    }

    private fun initWorkspace() {
        File(config.workspacePath).mkdirs()
        showStatus("Ready", false)
    }

    private fun connectToDeepSeek() {
        showStatus(getString(R.string.connecting), true)
        
        val bridgeConfig = DeepSeekBridge.DeepSeekBridgeConfig(
            url = "https://chat.deepseek.com",
            useXBrowser = config.useXBrowser,
            xbrowserPackage = config.xbrowserPackage
        )

        bridge = DeepSeekBridge(this, bridgeConfig, object : DeepSeekBridge.BridgeListener {
            override fun onConnected() {
                runOnUiThread {
                    showStatus(getString(R.string.connected), false)
                    connectionStatus.visibility = View.GONE
                    if (agent != null) agent!!.start()
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    showStatus(getString(R.string.disconnected), true)
                    connectionStatus.visibility = View.VISIBLE
                }
            }

            override fun onLoginRequired() {
                runOnUiThread {
                    showStatus(getString(R.string.login_required), true)
                    connectionStatus.visibility = View.VISIBLE
                }
            }

            override fun onMessageReceived(message: String) {
                runOnUiThread {
                    adapter.addMessage(ChatMessage.assistant(message))
                    scrollToBottom()
                }
            }

            override fun onStreamChunk(chunk: String) {
                // Handle streaming if needed
            }

            override fun onToolCall(toolCall: ToolCall) {
                // Not used - we handle tools locally
            }

            override fun onError(error: String) {
                runOnUiThread {
                    adapter.addError(error)
                    scrollToBottom()
                }
            }

            override fun onStatus(status: String) {
                runOnUiThread {
                    showStatus(status, false)
                }
            }
        })

        // Create WebView for bridge
        val webView = android.webkit.WebView(this)
        bridge!!.initialize(webView)

        // Initialize agent after bridge
        val toolContext = ToolContext(
            workspacePath = config.workspacePath,
            approveCallback = { toolName, description ->
                if (config.autoApprove) return@ToolContext true
                showApprovalDialog(toolName, description)
                // This is blocking - we'll use a callback approach
                // For now return false to defer
                false
            },
            emitCallback = { msg ->
                runOnUiThread { adapter.addStatus(msg) }
            }
        )

        agent = Agent(config, bridge!!, toolContext, lifecycleScope)
        agent!!.setListener(object : Agent.AgentListener {
            override fun onEvent(event: AgentEvent) {
                runOnUiThread { handleAgentEvent(event) }
            }
        })
    }

    private fun showApprovalDialog(toolName: String, description: String) {
        pendingToolCall = null // Will be set when tool is about to execute
        approvalText.text = "Allow $toolName? $description"
        approvalBar.visibility = View.VISIBLE
        
        // We'll handle the callback when tool actually executes
    }

    private fun hideApprovalBar() {
        approvalBar.visibility = View.GONE
    }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Text -> {
                // Streaming text - append to last assistant message or create new
            }
            is AgentEvent.ToolStart -> {
                pendingToolCall = ToolCall("temp", "function", com.termagent.deepseekagent.core.FunctionCall(event.name, com.google.gson.Gson().toJson(event.args)))
                adapter.addToolCall(pendingToolCall!!)
                scrollToBottom()
                
                if (!config.autoApprove) {
                    showApprovalDialog(event.name, "Execute ${event.name} with args: ${event.args}")
                    approveCallback = { approved ->
                        // The agent will handle approval via context callback
                    }
                }
            }
            is AgentEvent.ToolResult -> {
                adapter.updateToolCallStatus("temp", if (event.isError) "failed" else "completed")
                adapter.addToolResult(ToolResult(event.result, event.isError))
                scrollToBottom()
            }
            is AgentEvent.AssistantDone -> {
                adapter.addMessage(ChatMessage.assistant(event.content))
                scrollToBottom()
            }
            is AgentEvent.Error -> {
                adapter.addError(event.message)
                scrollToBottom()
            }
            is AgentEvent.Status -> {
                adapter.addStatus(event.message)
                scrollToBottom()
            }
            is AgentEvent.Meta -> {
                // Handle meta events
            }
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        
        adapter.addMessage(ChatMessage.user(text))
        messageInput.text.clear()
        scrollToBottom()
        
        agent?.sendUserMessage(text)
    }

    private fun scrollToBottom() {
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.chatRecyclerView).scrollToPosition(adapter.itemCount - 1)
    }

    private fun showStatus(message: String, isWarning: Boolean) {
        statusText.text = message
        statusIcon.setTextColor(if (isWarning) getColor(R.color.accent_orange) else getColor(R.color.accent_green))
        connectionStatus.setBackgroundColor(if (isWarning) getColor(R.color.chat_tool_bg) else getColor(R.color.chat_user_bg))
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.settings),
            getString(R.string.workspace),
            getString(R.string.auto_approve),
            getString(R.string.save_session),
            getString(R.string.load_session),
            getString(R.string.clear_chat)
        )
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showSettingsDialog()
                    1 -> showWorkspaceDialog()
                    2 -> toggleAutoApprove()
                    3 -> saveSession()
                    4 -> loadSession()
                    5 -> clearChat()
                }
            }
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val autoApproveCheck = view.findViewById<android.widget.CheckBox>(R.id.autoApproveCheck)
        val useXBrowserCheck = view.findViewById<android.widget.CheckBox>(R.id.useXBrowserCheck)
        val xbrowserPackageEdit = view.findViewById<EditText>(R.id.xbrowserPackageEdit)
        val maxTurnsEdit = view.findViewById<EditText>(R.id.maxTurnsEdit)
        val systemPromptEdit = view.findViewById<EditText>(R.id.systemPromptEdit)

        autoApproveCheck.isChecked = config.autoApprove
        useXBrowserCheck.isChecked = config.useXBrowser
        xbrowserPackageEdit.setText(config.xbrowserPackage)
        maxTurnsEdit.setText(config.maxTurns.toString())
        systemPromptEdit.setText(config.systemPrompt)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                config = config.copy(
                    autoApprove = autoApproveCheck.isChecked,
                    useXBrowser = useXBrowserCheck.isChecked,
                    xbrowserPackage = xbrowserPackageEdit.text.toString(),
                    maxTurns = maxTurnsEdit.text.toString().toIntOrNull() ?: 24,
                    systemPrompt = systemPromptEdit.text.toString()
                )
                saveConfig()
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showWorkspaceDialog() {
        // Use system file picker
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val path = getPathFromUri(uri)
                config = config.copy(workspacePath = path)
                saveConfig()
                File(path).mkdirs()
                Toast.makeText(this, "Workspace: $path", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getPathFromUri(uri: android.net.Uri): String {
        // Simplified - in production use DocumentsContract
        return uri.path ?: File(filesDir, "workspace").absolutePath
    }

    private fun toggleAutoApprove() {
        config = config.copy(autoApprove = !config.autoApprove)
        saveConfig()
        Toast.makeText(this, "Auto-approve: ${config.autoApprove}", Toast.LENGTH_SHORT).show()
    }

    private fun saveSession() {
        // Save messages to file
        val sessionFile = File(config.workspacePath, "session_${System.currentTimeMillis()}.json")
        try {
            val json = com.google.gson.Gson().toJson(adapter.items.mapNotNull { 
                if (it is ChatAdapter.ChatItem.Message) it.message else null 
            })
            sessionFile.writeText(json)
            Toast.makeText(this, getString(R.string.session_saved), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSession() {
        // List session files and let user pick
        val dir = File(config.workspacePath)
        val sessions = dir.listFiles { _, name -> name.startsWith("session_") && name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() } ?: emptyArray()
        
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No sessions found", Toast.LENGTH_SHORT).show()
            return
        }

        val names = sessions.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.load_session)
            .setItems(names) { _, which ->
                try {
                    val json = sessions[which].readText()
                    val messages = com.google.gson.Gson().fromJson(json, Array<ChatMessage>::class.java)
                    adapter.clear()
                    messages.forEach { adapter.addMessage(it) }
                    scrollToBottom()
                    Toast.makeText(this, getString(R.string.session_loaded), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun clearChat() {
        adapter.clear()
        agent = null
        bridge?.disconnect()
        connectToDeepSeek()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> { showSettingsDialog(); true }
            R.id.action_workspace -> { showWorkspaceDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }
}