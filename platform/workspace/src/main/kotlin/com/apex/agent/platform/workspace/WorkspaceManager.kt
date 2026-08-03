package com.apex.agent.platform.workspace

import android.content.Context
import com.apex.agent.platform.linux.LinuxRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作区管理器
 * 管理Agent的项目工作区
 */
@Singleton
class WorkspaceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linuxRuntime: LinuxRuntime
) {
    
    private val baseDir: File
        get() = File(context.filesDir, "workspaces")

    data class Workspace(
        val id: String,
        val name: String,
        val type: ProjectType,
        val path: File
    )

    enum class ProjectType {
        PYTHON, NODE, ANDROID, C_CPP, WEB, GENERIC
    }

    private val workspaces = mutableMapOf<String, Workspace>()

    fun createWorkspace(name: String, type: ProjectType): Workspace {
        val id = name.lowercase().replace(" ", "_")
        val dir = File(baseDir, id)
        dir.mkdirs()
        
        val workspace = Workspace(id, name, type, dir)
        workspaces[id] = workspace
        
        // 初始化项目结构
        initProjectStructure(workspace)
        
        return workspace
    }

    fun getWorkspace(id: String): Workspace? = workspaces[id]
    
    fun listWorkspaces(): List<Workspace> = workspaces.values.toList()

    fun deleteWorkspace(id: String): Boolean {
        val ws = workspaces[id] ?: return false
        ws.path.deleteRecursively()
        workspaces.remove(id)
        return true
    }

    /**
     * 读取项目文件
     */
    fun readFile(workspaceId: String, relativePath: String): String {
        val ws = workspaces[workspaceId] ?: return "Error: workspace not found"
        val file = File(ws.path, relativePath)
        if (!file.exists()) return "Error: file not found: $relativePath"
        return file.readText()
    }

    /**
     * 写入项目文件
     */
    fun writeFile(workspaceId: String, relativePath: String, content: String): String {
        val ws = workspaces[workspaceId] ?: return "Error: workspace not found"
        val file = File(ws.path, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "OK: written ${content.length} chars to $relativePath"
    }

    /**
     * 列出项目文件
     */
    fun listFiles(workspaceId: String, relativePath: String = "."): String {
        val ws = workspaces[workspaceId] ?: return "Error: workspace not found"
        val dir = File(ws.path, relativePath)
        if (!dir.exists() || !dir.isDirectory) return "Error: directory not found"
        
        return dir.listFiles()?.sortedBy { it.name }?.joinToString("\n") { file ->
            val prefix = if (file.isDirectory) "📁" else "📄"
            "$prefix ${file.name}"
        } ?: "(empty)"
    }

    /**
     * 在项目中执行命令（通过Linux环境）
     */
    suspend fun executeInWorkspace(workspaceId: String, command: String): String {
        val ws = workspaces[workspaceId] ?: return "Error: workspace not found"
        val result = linuxRuntime.execute(command, workDir = ws.path.absolutePath)
        return if (result.success) {
            result.stdout.ifBlank { "(completed, no output)" }
        } else {
            "Error (exit ${result.exitCode}):\n${result.stderr.ifBlank { result.stdout }}"
        }
    }

    private fun initProjectStructure(workspace: Workspace) {
        when (workspace.type) {
            ProjectType.PYTHON -> {
                File(workspace.path, "main.py").writeText("# ${workspace.name}\n\nprint('Hello from Apex Agent')\n")
                File(workspace.path, "requirements.txt").writeText("")
                File(workspace.path, "README.md").writeText("# ${workspace.name}\n")
            }
            ProjectType.NODE -> {
                File(workspace.path, "package.json").writeText("""{"name": "${workspace.name}", "version": "1.0.0"}""")
                File(workspace.path, "index.js").writeText("console.log('Hello from Apex Agent');\n")
            }
            ProjectType.C_CPP -> {
                File(workspace.path, "main.c").writeText("#include <stdio.h>\n\nint main() {\n    printf(\"Hello from Apex Agent\\n\");\n    return 0;\n}\n")
                File(workspace.path, "Makefile").writeText("all:\n\tgcc -o main main.c\n")
            }
            ProjectType.WEB -> {
                File(workspace.path, "index.html").writeText("<!DOCTYPE html>\n<html>\n<head><title>${workspace.name}</title></head>\n<body>\n<h1>${workspace.name}</h1>\n</body>\n</html>")
            }
            else -> {
                File(workspace.path, "README.md").writeText("# ${workspace.name}\n")
            }
        }
    }
}
