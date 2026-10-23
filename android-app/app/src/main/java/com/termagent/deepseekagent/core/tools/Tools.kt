package com.termagent.deepseekagent.core.tools

import com.termagent.deepseekagent.core.Tool
import com.termagent.deepseekagent.core.ToolContext
import com.termagent.deepseekagent.core.ToolResult
import java.io.File
import java.io.FileWriter
import java.util.regex.Pattern

object ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    fun schemas(): List<Map<String, Any>> = tools.values.map { tool ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to tool.parameters
            )
        )
    }

    fun init(context: ToolContext) {
        register(BashTool(context))
        register(ReadTool(context))
        register(WriteTool(context))
        register(EditTool(context))
        register(LsTool(context))
        register(GlobTool(context))
        register(GrepTool(context))
        register(TodoTool(context))
    }
}

class BashTool(private val context: ToolContext) : Tool {
    override val name = "bash"
    override val description = "Execute a shell command in the workspace"
    override val readOnly = false
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "command" to mapOf("type" to "string", "description" to "The shell command to execute"),
            "description" to mapOf("type" to "string", "description" to "Brief description of what the command does")
        ),
        "required" to listOf("command")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val command = args["command"] as? String ?: return ToolResult("error: missing command", true)
        val workDir = File(context.workspacePath)
        if (!workDir.exists()) workDir.mkdirs()

        try {
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { output.append(it).append("\n") }
            }
            val exitCode = process.waitFor()

            val result = output.toString().trim()
            if (exitCode != 0) {
                return ToolResult("exit code $exitCode:\n$result", true)
            }
            return ToolResult(if (result.isEmpty()) "(no output)" else result)
        } catch (e: Exception) {
            return ToolResult("error: ${e.message}", true)
        }
    }
}

class ReadTool(private val context: ToolContext) : Tool {
    override val name = "read"
    override val description = "Read a file from the workspace"
    override val readOnly = true
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Relative path to file"),
            "offset" to mapOf("type" to "integer", "description" to "Line offset (0-based)"),
            "limit" to mapOf("type" to "integer", "description" to "Max lines to read")
        ),
        "required" to listOf("path")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val path = args["path"] as? String ?: return ToolResult("error: missing path", true)
        val offset = (args["offset"] as? Int) ?: 0
        val limit = (args["limit"] as? Int) ?: 2000

        val file = File(context.workspacePath, path)
        if (!file.exists()) return ToolResult("error: file not found: $path", true)
        if (!file.isFile) return ToolResult("error: not a file: $path", true)

        try {
            val lines = file.readLines()
            val start = offset.coerceAtMost(lines.size)
            val end = (start + limit).coerceAtMost(lines.size)
            val selected = lines.subList(start, end)
            val prefix = if (offset > 0) "... ($offset lines skipped)\n" else ""
            val suffix = if (end < lines.size) "\n... (${lines.size - end} more lines)" else ""
            return ToolResult("$prefix${selected.joinToString("\n")}$suffix")
        } catch (e: Exception) {
            return ToolResult("error reading file: ${e.message}", true)
        }
    }
}

class WriteTool(private val context: ToolContext) : Tool {
    override val name = "write"
    override val description = "Write a file to the workspace"
    override val readOnly = false
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Relative path to file"),
            "content" to mapOf("type" to "string", "description" to "File content")
        ),
        "required" to listOf("path", "content")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val path = args["path"] as? String ?: return ToolResult("error: missing path", true)
        val content = args["content"] as? String ?: return ToolResult("error: missing content", true)

        if (context.approveCallback != null && !context.approveCallback!!("write", "Write file: $path")) {
            return ToolResult("error: user denied write", true)
        }

        val file = File(context.workspacePath, path)
        file.parentFile?.mkdirs()
        try {
            FileWriter(file).use { it.write(content) }
            context.emitCallback?.invoke("write: $path")
            return ToolResult("written: $path")
        } catch (e: Exception) {
            return ToolResult("error writing file: ${e.message}", true)
        }
    }
}

class EditTool(private val context: ToolContext) : Tool {
    override val name = "edit"
    override val description = "Edit a file by replacing text"
    override val readOnly = false
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Relative path to file"),
            "old_string" to mapOf("type" to "string", "description" to "Text to replace"),
            "new_string" to mapOf("type" to "string", "description" to "Replacement text"),
            "replace_all" to mapOf("type" to "boolean", "description" to "Replace all occurrences")
        ),
        "required" to listOf("path", "old_string", "new_string")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val path = args["path"] as? String ?: return ToolResult("error: missing path", true)
        val oldStr = args["old_string"] as? String ?: return ToolResult("error: missing old_string", true)
        val newStr = args["new_string"] as? String ?: return ToolResult("error: missing new_string", true)
        val replaceAll = (args["replace_all"] as? Boolean) ?: false

        if (context.approveCallback != null && !context.approveCallback!!("edit", "Edit file: $path")) {
            return ToolResult("error: user denied edit", true)
        }

        val file = File(context.workspacePath, path)
        if (!file.exists()) return ToolResult("error: file not found: $path", true)

        try {
            var content = file.readText()
            val newContent = if (replaceAll) content.replace(oldStr, newStr) else content.replaceFirst(oldStr, newStr)
            if (newContent == content) {
                return ToolResult("error: old_string not found in file", true)
            }
            FileWriter(file).use { it.write(newContent) }
            context.emitCallback?.invoke("edit: $path")
            return ToolResult("edited: $path")
        } catch (e: Exception) {
            return ToolResult("error editing file: ${e.message}", true)
        }
    }
}

class LsTool(private val context: ToolContext) : Tool {
    override val name = "ls"
    override val description = "List files in a directory"
    override val readOnly = true
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "path" to mapOf("type" to "string", "description" to "Relative directory path (default: .)")
        )
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val path = (args["path"] as? String) ?: "."
        val dir = File(context.workspacePath, path)
        if (!dir.exists()) return ToolResult("error: directory not found: $path", true)
        if (!dir.isDirectory) return ToolResult("error: not a directory: $path", true)

        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyArray()
        val sb = StringBuilder()
        for (f in files) {
            val prefix = if (f.isDirectory) "📁 " else "📄 "
            sb.append("$prefix${f.name}\n")
        }
        return ToolResult(if (sb.isEmpty()) "(empty)" else sb.toString().trim())
    }
}

class GlobTool(private val context: ToolContext) : Tool {
    override val name = "glob"
    override val description = "Find files matching a glob pattern"
    override val readOnly = true
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "pattern" to mapOf("type" to "string", "description" to "Glob pattern (e.g. **/*.kt)"),
            "path" to mapOf("type" to "string", "description" to "Base directory (default: .)")
        ),
        "required" to listOf("pattern")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val pattern = args["pattern"] as? String ?: return ToolResult("error: missing pattern", true)
        val basePath = (args["path"] as? String) ?: "."
        val baseDir = File(context.workspacePath, basePath)

        if (!baseDir.exists()) return ToolResult("error: directory not found: $basePath", true)

        val regex = pattern
            .replace(".", "\\.")
            .replace("**/", ".*/")
            .replace("*", "[^/]*")
        val patternCompiled = Pattern.compile("^$regex$")

        val results = mutableListOf<String>()
        fun scan(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                val relPath = "$prefix${f.name}"
                if (f.isDirectory) {
                    scan(f, "$relPath/")
                } else if (patternCompiled.matcher(relPath).matches()) {
                    results.add(relPath)
                }
            }
        }
        scan(baseDir, "")

        return ToolResult(if (results.isEmpty()) "(no matches)" else results.joinToString("\n"))
    }
}

class GrepTool(private val context: ToolContext) : Tool {
    override val name = "grep"
    override val description = "Search for pattern in files"
    override val readOnly = true
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "pattern" to mapOf("type" to "string", "description" to "Regex pattern to search"),
            "path" to mapOf("type" to "string", "description" to "Directory to search (default: .)"),
            "include" to mapOf("type" to "string", "description" to "File pattern (e.g. *.kt)")
        ),
        "required" to listOf("pattern")
    )

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val pattern = args["pattern"] as? String ?: return ToolResult("error: missing pattern", true)
        val basePath = (args["path"] as? String) ?: "."
        val include = args["include"] as? String
        val baseDir = File(context.workspacePath, basePath)

        if (!baseDir.exists()) return ToolResult("error: directory not found: $basePath", true)

        val regex = Pattern.compile(pattern)
        val results = mutableListOf<String>()

        fun scan(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                val relPath = "$prefix${f.name}"
                if (f.isDirectory) {
                    scan(f, "$relPath/")
                } else if (include == null || Pattern.compile(include.replace("*", ".*")).matcher(f.name).matches()) {
                    try {
                        f.readLines().forEachIndexed { idx, line ->
                            if (regex.matcher(line).find()) {
                                results.add("$relPath:${idx + 1}: $line")
                            }
                        }
                    } catch (e: Exception) {
                        // Skip unreadable files
                    }
                }
            }
        }
        scan(baseDir, "")

        return ToolResult(if (results.isEmpty()) "(no matches)" else results.joinToString("\n"))
    }
}

class TodoTool(private val context: ToolContext) : Tool {
    override val name = "todo"
    override val description = "Manage a todo list"
    override val readOnly = false
    override val parameters = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "action" to mapOf("type" to "string", "enum" to listOf("add", "update", "remove", "list", "clear"), "description" to "Action to perform"),
            "id" to mapOf("type" to "string", "description" to "Todo ID (for update/remove)"),
            "content" to mapOf("type" to "string", "description" to "Todo content (for add/update)"),
            "status" to mapOf("type" to "string", "enum" to listOf("pending", "in_progress", "completed", "cancelled"), "description" to "Todo status")
        ),
        "required" to listOf("action")
    )

    private val todos = mutableMapOf<String, TodoItem>()
    private var nextId = 1

    private data class TodoItem(val id: String, var content: String, var status: String)

    override fun execute(args: Map<String, Any>, context: ToolContext): ToolResult {
        val action = args["action"] as? String ?: return ToolResult("error: missing action", true)

        return when (action) {
            "add" -> {
                val content = args["content"] as? String ?: return ToolResult("error: missing content", true)
                val status = (args["status"] as? String) ?: "pending"
                val id = "todo_$nextId"
                nextId++
                todos[id] = TodoItem(id, content, status)
                context.emitCallback?.invoke("todo added: $id")
                ToolResult("added: $id - $content [$status]")
            }
            "update" -> {
                val id = args["id"] as? String ?: return ToolResult("error: missing id", true)
                val item = todos[id] ?: return ToolResult("error: todo not found: $id", true)
                (args["content"] as? String)?.let { item.content = it }
                (args["status"] as? String)?.let { item.status = it }
                context.emitCallback?.invoke("todo updated: $id")
                ToolResult("updated: $id - ${item.content} [${item.status}]")
            }
            "remove" -> {
                val id = args["id"] as? String ?: return ToolResult("error: missing id", true)
                todos.remove(id) ?: return ToolResult("error: todo not found: $id", true)
                context.emitCallback?.invoke("todo removed: $id")
                ToolResult("removed: $id")
            }
            "list" -> {
                if (todos.isEmpty()) ToolResult("(no todos)") else ToolResult(todos.values.joinToString("\n") { "${it.id}: ${it.content} [${it.status}]" })
            }
            "clear" -> {
                todos.clear()
                context.emitCallback?.invoke("todos cleared")
                ToolResult("cleared all todos")
            }
            else -> ToolResult("error: unknown action: $action", true)
        }
    }
}