// IApexPlugin.aidl
package com.apex.agent.plugin.api;

interface IApexPlugin {
    String getMetadataJson();
    String getToolsJson();
    String executeTool(String toolId, String argumentsJson);
    void onActivate();
    void onDeactivate();
}
