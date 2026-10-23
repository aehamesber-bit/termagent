package com.termagent.deepseekagent.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.termagent.deepseekagent.core.AgentEvent
import com.termagent.deepseekagent.core.ChatMessage
import com.termagent.deepseekagent.core.ToolCall
import com.termagent.deepseekagent.core.ToolResult
import java.util.concurrent.ConcurrentLinkedQueue

class DeepSeekBridge(
    private val context: Context,
    private val config: DeepSeekBridgeConfig,
    private val listener: BridgeListener
) {

    private var webView: WebView? = null
    private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()
    private val pendingCallbacks = mutableMapOf<String, (String) -> Unit>()
    private val gson = Gson()
    private var isConnected = false
    private var isLoggedIn = false
    private var sessionId: String? = null
    private val handler = Handler(Looper.getMainLooper())

    interface BridgeListener {
        fun onConnected()
        fun onDisconnected()
        fun onLoginRequired()
        fun onMessageReceived(message: String)
        fun onStreamChunk(chunk: String)
        fun onToolCall(toolCall: ToolCall)
        fun onError(error: String)
        fun onStatus(status: String)
    }

    data class DeepSeekBridgeConfig(
        val url: String = "https://chat.deepseek.com",
        val useXBrowser: Boolean = false,
        val xbrowserPackage: String = "com.xbrowser.play",
        val injectionScript: String = DEFAULT_INJECTION_SCRIPT
    )

    companion object {
        const val DEFAULT_INJECTION_SCRIPT = """
            (function() {
                window.DeepSeekBridge = {
                    callbacks: {},
                    messageId: 0,
                    pendingResponses: {},
                    
                    send: function(message) {
                        // Find the chat input and send button
                        const input = document.querySelector('textarea[placeholder*="Message"], textarea[data-testid="chat-input"], #chat-input, .chat-input textarea');
                        const sendBtn = document.querySelector('button[data-testid="send-button"], button[aria-label*="Send"], .send-button');
                        
                        if (input && sendBtn) {
                            input.value = message;
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            sendBtn.click();
                            return true;
                        }
                        return false;
                    },
                    
                    getLastResponse: function() {
                        // Find the last assistant message
                        const messages = document.querySelectorAll('[data-testid="message"], .message, [class*="message"]');
                        for (let i = messages.length - 1; i >= 0; i--) {
                            const msg = messages[i];
                            if (msg.textContent.includes('assistant') || msg.classList.contains('assistant') || msg.getAttribute('data-author') === 'assistant') {
                                return msg.textContent.trim();
                            }
                        }
                        return null;
                    },
                    
                    getStreamingResponse: function() {
                        // Get currently streaming response
                        const streaming = document.querySelector('[data-streaming="true"], .streaming, [class*="streaming"]');
                        return streaming ? streaming.textContent : null;
                    },
                    
                    isLoggedIn: function() {
                        // Check if user is logged in
                        return !document.querySelector('button:contains("Login"), button:contains("Sign in"), a[href*="login"], [data-testid="login-button"]');
                    },
                    
                    waitForResponse: function(messageId, timeout) {
                        return new Promise((resolve, reject) => {
                            const startTime = Date.now();
                            const check = () => {
                                const response = this.getLastResponse();
                                if (response && response.length > 10) {
                                    resolve(response);
                                } else if (Date.now() - startTime > timeout) {
                                    reject(new Error('timeout'));
                                } else {
                                    setTimeout(check, 500);
                                }
                            };
                            check();
                        });
                    }
                };
                
                // Override fetch/XHR to intercept API calls
                const originalFetch = window.fetch;
                window.fetch = async function(...args) {
                    const response = await originalFetch.apply(this, args);
                    const url = args[0];
                    if (typeof url === 'string' && url.includes('/api/chat') || url.includes('/completion')) {
                        response.clone().json().then(data => {
                            if (data.choices && data.choices[0]) {
                                window.DeepSeekBridge.onApiResponse(data);
                            }
                        }).catch(() => {});
                    }
                    return response;
                };
                
                window.DeepSeekBridge.onApiResponse = function(data) {
                    if (window.DeepSeekBridge.onResponseCallback) {
                        window.DeepSeekBridge.onResponseCallback(data);
                    }
                };
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun initialize(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.setSupportZoom(false)
        webView.settings.loadsImagesAutomatically = true
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectBridgeScript()
                checkLoginStatus()
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                super.onReceivedError(view, request, error)
                listener.onError("WebView error: ${error?.description}")
            }
        }
        webView.addJavascriptInterface(BridgeJavaInterface(), "AndroidBridge")
        webView.loadUrl(config.url)
    }

    private fun injectBridgeScript() {
        webView?.evaluateJavascript(config.injectionScript) { result ->
            Log.d("DeepSeekBridge", "Injection result: $result")
        }
    }

    private fun checkLoginStatus() {
        webView?.evaluateJavascript("window.DeepSeekBridge.isLoggedIn()") { result ->
            val loggedIn = result == "true"
            isLoggedIn = loggedIn
            if (loggedIn) {
                isConnected = true
                listener.onConnected()
                processQueue()
            } else {
                listener.onLoginRequired()
            }
        }
    }

    fun sendMessage(message: String) {
        val queued = QueuedMessage(message, System.currentTimeMillis())
        messageQueue.add(queued)
        if (isConnected && isLoggedIn) {
            processQueue()
        }
    }

    private fun processQueue() {
        handler.post {
            val msg = messageQueue.poll()
            if (msg != null) {
                sendToDeepSeek(msg.content)
            }
        }
    }

    private fun sendToDeepSeek(message: String) {
        val js = """
            (function() {
                const sent = window.DeepSeekBridge.send(${
                    gson.toJson(message)
                });
                if (!sent) {
                    AndroidBridge.onError('Failed to send message - input not found');
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js) { result ->
            Log.d("DeepSeekBridge", "Send result: $result")
        }
    }

    fun getResponse(callback: (String) -> Unit) {
        val js = """
            (function() {
                const response = window.DeepSeekBridge.getLastResponse();
                AndroidBridge.onResponse(response);
            })();
        """.trimIndent()
        pendingCallbacks["response"] = callback
        webView?.evaluateJavascript(js, null)
    }

    fun startStreaming(callback: (String) -> Unit) {
        pendingCallbacks["stream"] = callback
        pollStreaming()
    }

    private fun pollStreaming() {
        if (!isConnected) return
        val js = """
            (function() {
                const chunk = window.DeepSeekBridge.getStreamingResponse();
                AndroidBridge.onStreamChunk(chunk);
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
        handler.postDelayed({ pollStreaming() }, 500)
    }

    fun disconnect() {
        isConnected = false
        isLoggedIn = false
        handler.removeCallbacksAndMessages(null)
        webView?.loadUrl("about:blank")
        listener.onDisconnected()
    }

    inner class BridgeJavaInterface {
        @JavascriptInterface
        fun onResponse(response: String?) {
            pendingCallbacks["response"]?.let { cb ->
                cb(response ?: "")
                pendingCallbacks.remove("response")
            }
        }

        @JavascriptInterface
        fun onStreamChunk(chunk: String?) {
            pendingCallbacks["stream"]?.let { cb ->
                cb(chunk ?: "")
            }
        }

        @JavascriptInterface
        fun onError(error: String) {
            listener.onError(error)
        }

        @JavascriptInterface
        fun onLoginStateChanged(loggedIn: Boolean) {
            isLoggedIn = loggedIn
            if (loggedIn) {
                isConnected = true
                listener.onConnected()
                processQueue()
            } else {
                listener.onLoginRequired()
            }
        }
    }

    private data class QueuedMessage(val content: String, val timestamp: Long)

    fun isReady(): Boolean = isConnected && isLoggedIn
}

// XBrowser integration via Accessibility Service or Intent
class XBrowserBridge(
    private val context: Context,
    private val packageName: String,
    private val listener: DeepSeekBridge.BridgeListener
) {
    private var accessibilityServiceConnected = false

    fun connect() {
        // Try to launch xbrowser with DeepSeek URL
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.putExtra("url", "https://chat.deepseek.com")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            listener.onStatus("Launched XBrowser - please login to DeepSeek")
        } else {
            listener.onError("XBrowser not installed. Package: $packageName")
        }
    }

    fun sendMessage(message: String) {
        // Use Accessibility Service to inject text into xbrowser
        // This requires the user to grant accessibility permission
        listener.onStatus("XBrowser integration requires Accessibility Service permission")
    }

    fun disconnect() {
        // Close xbrowser or clear state
    }
}