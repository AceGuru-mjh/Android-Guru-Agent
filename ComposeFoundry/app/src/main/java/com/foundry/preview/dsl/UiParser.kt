package com.foundry.preview.dsl

import org.json.JSONObject

class UiParser {

    fun parse(json: String): Result<UiDocument> = try {
        val rootObj = JSONObject(json)
        val name = rootObj.optString("name", "Untitled")
        val theme = parseTheme(rootObj.optJSONObject("theme"))
        val device = parseDevice(rootObj.optJSONObject("device"))
        val rootElement = parseElement(rootObj.getJSONObject("root"))
        
        Result.success(UiDocument(name, theme, device, rootElement))
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun parseTheme(obj: JSONObject?): ThemeConfig {
        if (obj == null) return ThemeConfig()
        return ThemeConfig(
            primaryColor = obj.optString("primaryColor", "#FF6200EE"),
            secondaryColor = obj.optString("secondaryColor", "#FF03DAC5"),
            backgroundColor = obj.optString("backgroundColor", "#FFFFFFFF"),
            surfaceColor = obj.optString("surfaceColor", "#FFFFFFFF"),
            errorColor = obj.optString("errorColor", "#FFB00020")
        )
    }

    private fun parseDevice(obj: JSONObject?): DeviceConfig {
        if (obj == null) return DeviceConfig()
        return DeviceConfig(
            widthDp = obj.optInt("widthDp", 412),
            heightDp = obj.optInt("heightDp", 915)
        )
    }

    private fun parseElement(obj: JSONObject): UiElement {
        val type = obj.getString("type")
        val attributes = parseAttributes(obj.optJSONObject("attributes"))
        val modifier = parseModifier(obj.optJSONObject("modifier"))
        val children = parseChildren(obj.optJSONArray("children"))
        
        return UiElement(type, attributes, modifier, children)
    }

    private fun parseAttributes(obj: JSONObject?): Map<String, Any> {
        if (obj == null) return emptyMap()
        val map = mutableMapOf<String, Any>()
        obj.keys().forEach { key ->
            map[key] = obj.get(key)
        }
        return map
    }

    private fun parseModifier(obj: JSONObject?): ModifierConfig {
        if (obj == null) return ModifierConfig()
        return ModifierConfig(
            width = obj.optInt("width", -1).takeIf { it > 0 },
            height = obj.optInt("height", -1).takeIf { it > 0 },
            fillMaxWidth = obj.optBoolean("fillMaxWidth", false),
            fillMaxHeight = obj.optBoolean("fillMaxHeight", false),
            fillMaxSize = obj.optBoolean("fillMaxSize", false),
            padding = parsePadding(obj.optJSONObject("padding")),
            background = obj.optString("background", null),
            cornerRadius = obj.optInt("cornerRadius", -1).takeIf { it > 0 },
            align = obj.optString("align", null)
        )
    }

    private fun parsePadding(obj: JSONObject?): PaddingConfig? {
        if (obj == null) return null
        return PaddingConfig(
            all = obj.optInt("all", -1).takeIf { it >= 0 },
            horizontal = obj.optInt("horizontal", -1).takeIf { it >= 0 },
            vertical = obj.optInt("vertical", -1).takeIf { it >= 0 },
            start = obj.optInt("start", -1).takeIf { it >= 0 },
            top = obj.optInt("top", -1).takeIf { it >= 0 },
            end = obj.optInt("end", -1).takeIf { it >= 0 },
            bottom = obj.optInt("bottom", -1).takeIf { it >= 0 }
        )
    }

    private fun parseChildren(arr: org.json.JSONArray?): List<UiElement> {
        if (arr == null) return emptyList()
        val list = mutableListOf<UiElement>()
        for (i in 0 until arr.length()) {
            list.add(parseElement(arr.getJSONObject(i)))
        }
        return list
    }
}
