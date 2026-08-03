package com.apex.agent.core.tools.builtin

import com.apex.agent.core.tools.AgentTool
import kotlinx.serialization.json.*

/**
 * 项目文件读取工具
 */
class ProjectReadFileTool(
    private val fileReader: suspend (String, String) -> String
) : AgentTool {
    override val id = "project_read_file"
    override val name = "Read Project File"
    override val description = "Read the content of a file in a project workspace."
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "workspace": {"type": "string", "description": "Project name"},
                "path": {"type": "string", "description": "Relative file path"}
            },
            "required": ["workspace", "path"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val workspace = json["workspace"]?.jsonPrimitive?.content ?: return "Error: missing workspace"
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: missing path"
        return fileReader(workspace, path)
    }
}

/**
 * 项目文件写入工具
 */
class ProjectWriteFileTool(
    private val fileWriter: suspend (String, String, String) -> String
) : AgentTool {
    override val id = "project_write_file"
    override val name = "Write Project File"
    override val description = """
        Write content to a file in a project workspace.
        Creates parent directories if needed.
        Use this to create or modify source code files.
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "workspace": {"type": "string", "description": "Project name"},
                "path": {"type": "string", "description": "Relative file path"},
                "content": {"type": "string", "description": "File content"}
            },
            "required": ["workspace", "path", "content"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val workspace = json["workspace"]?.jsonPrimitive?.content ?: return "Error: missing workspace"
        val path = json["path"]?.jsonPrimitive?.content ?: return "Error: missing path"
        val content = json["content"]?.jsonPrimitive?.content ?: return "Error: missing content"
        return fileWriter(workspace, path, content)
    }
}

/**
 * 项目命令执行工具
 */
class ProjectExecuteTool(
    private val executor: suspend (String, String) -> String
) : AgentTool {
    override val id = "project_execute"
    override val name = "Execute in Project"
    override val description = """
        Execute a command inside a project workspace (Linux environment).
        Use for: running scripts, installing dependencies, building, testing.
        Examples:
        - "python main.py"
        - "pip install requests beautifulsoup4"
        - "npm install && npm run build"
        - "gcc -o main main.c && ./main"
    """.trimIndent()
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "workspace": {"type": "string", "description": "Project name"},
                "command": {"type": "string", "description": "Command to execute"},
                "timeout": {"type": "integer", "description": "Timeout seconds (default 60)"}
            },
            "required": ["workspace", "command"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val workspace = json["workspace"]?.jsonPrimitive?.content ?: return "Error: missing workspace"
        val command = json["command"]?.jsonPrimitive?.content ?: return "Error: missing command"
        return executor(workspace, command)
    }
}

/**
 * 项目文件列表工具
 */
class ProjectListFilesTool(
    private val lister: suspend (String, String) -> String
) : AgentTool {
    override val id = "project_list_files"
    override val name = "List Project Files"
    override val description = "List files and directories in a project workspace."
    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "workspace": {"type": "string"},
                "path": {"type": "string", "description": "Relative dir path (default '.')"}
            },
            "required": ["workspace"]
        }
    """.trimIndent()

    override suspend fun execute(arguments: String): String {
        val json = Json.parseToJsonElement(arguments).jsonObject
        val workspace = json["workspace"]?.jsonPrimitive?.content ?: return "Error: missing workspace"
        val path = json["path"]?.jsonPrimitive?.content ?: "."
        return lister(workspace, path)
    }
}
