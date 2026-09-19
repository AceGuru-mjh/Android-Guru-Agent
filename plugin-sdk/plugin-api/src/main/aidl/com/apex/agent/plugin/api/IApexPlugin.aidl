// IApexPlugin.aidl
package com.apex.agent.plugin.api;

interface IApexPlugin {
    String getMetadataJson();
    String getToolsJson();
    String executeTool(String toolId, String argumentsJson);
    void onActivate();
    void onDeactivate();

    /**
     * 宿主在绑定插件后回调一次，注入宿主桥 binder（见 IApexPluginHost.aidl）。
     * 插件侧用 IApexPluginHost.Stub.asInterface(hostBinder) 还原接口，
     * 之后 executeTool 可把需要宿主能力（如 BrowserEngine）的调用转发回宿主。
     */
    void attachHost(IBinder hostBinder);
}
