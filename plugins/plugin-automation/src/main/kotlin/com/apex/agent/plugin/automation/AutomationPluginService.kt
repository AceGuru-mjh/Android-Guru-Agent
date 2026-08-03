package com.apex.agent.plugin.automation

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.apex.agent.plugin.api.IApexPlugin
import kotlinx.serialization.json.*

/**
 * Automation plugin — task automation primitives for the Apex Agent.
 *
 * Mirrors the structure of [com.apex.agent.plugin.workflow.WorkflowPluginService];
 * the difference is the tool surface: this plugin exposes generic automation
 * primitives (trigger, schedule, observe) rather than recorded workflows.
 */
class AutomationPluginService : Service() {

    private val binder = AutomationPluginBinder()

    override fun onBind(intent: Intent): IBinder = binder

    inner class AutomationPluginBinder : IApexPlugin.Stub() {

        override fun getMetadataJson(): String {
            return buildJsonObject {
                put("id", "com.apex.agent.plugin.automation")
                put("name", "自动化引擎")
                put("version", 1)
                put("versionName", "1.0.0")
                put("minHostVersion", 1)
                put("description", "自动化能力：定时任务、事件触发、模式匹配、状态监听")
            }.toString()
        }

        override fun getToolsJson(): String {
            return buildJsonArray {
                addJsonObject {
                    put("id", "automation/schedule")
                    put("name", "Schedule Task")
                    put("description", "Schedule a task to run at a specific time or on a recurring schedule")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("name") { put("type", "string") }
                            putJsonObject("cron") { put("type", "string") }
                            putJsonObject("action") { put("type", "object") }
                        }
                        putJsonArray("required") { add("name"); add("cron"); add("action") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "automation/trigger")
                    put("name", "Register Trigger")
                    put("description", "Register a trigger that fires when an event matches the given predicate")
                    put("parametersSchema", buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("event") { put("type", "string") }
                            putJsonObject("predicate") { put("type", "object") }
                            putJsonObject("action") { put("type", "object") }
                        }
                        putJsonArray("required") { add("event"); add("action") }
                    }.toString())
                }
                addJsonObject {
                    put("id", "automation/list")
                    put("name", "List Automations")
                    put("description", "List all registered automation rules")
                    put("parametersSchema", """{"type":"object","properties":{}}""")
                }
            }.toString()
        }

        override fun executeTool(toolId: String, argumentsJson: String): String {
            return when (toolId) {
                "automation/schedule" -> handleSchedule(argumentsJson)
                "automation/trigger" -> handleRegisterTrigger(argumentsJson)
                "automation/list" -> handleList()
                else -> "Error: Unknown tool $toolId"
            }
        }

        override fun onActivate() {}
        override fun onDeactivate() {}
    }

    private fun handleSchedule(args: String): String {
        // TODO: persist scheduled task and register with WorkManager
        return "OK: scheduled"
    }

    private fun handleRegisterTrigger(args: String): String {
        // TODO: register an event subscription and dispatch action on match
        return "OK: trigger registered"
    }

    private fun handleList(): String {
        // TODO: enumerate persisted automation rules
        return "[]"
    }
}
