package com.foundry.preview.dsl

class UiValidator {

    fun validate(doc: UiDocument): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        validateElement(doc.root, "", diagnostics)
        return diagnostics
    }

    private fun validateElement(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        val currentPath = if (path.isEmpty()) element.type else "$path.${element.type}"
        
        when (element.type) {
            "Text" -> validateText(element, currentPath, diagnostics)
            "Button" -> validateButton(element, currentPath, diagnostics)
            "TextField" -> validateTextField(element, currentPath, diagnostics)
            "Image" -> validateImage(element, currentPath, diagnostics)
            "Column", "Row", "Box", "Scroll", "Surface", "Card" -> 
                validateContainer(element, currentPath, diagnostics)
            "Spacer", "Divider" -> 
                validateSimple(element, currentPath, diagnostics)
            else -> 
                diagnostics.add(Diagnostic(DiagnosticLevel.ERROR, currentPath, "Unknown element type: ${element.type}"))
        }
        
        element.children.forEach { child ->
            validateElement(child, currentPath, diagnostics)
        }
    }

    private fun validateText(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        if (!element.attributes.containsKey("text")) {
            diagnostics.add(Diagnostic(DiagnosticLevel.WARNING, path, "Text element missing 'text' attribute"))
        }
    }

    private fun validateButton(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        if (!element.attributes.containsKey("text")) {
            diagnostics.add(Diagnostic(DiagnosticLevel.WARNING, path, "Button element missing 'text' attribute"))
        }
    }

    private fun validateTextField(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        if (!element.attributes.containsKey("label") && !element.attributes.containsKey("placeholder")) {
            diagnostics.add(Diagnostic(DiagnosticLevel.INFO, path, "TextField should have 'label' or 'placeholder'"))
        }
    }

    private fun validateImage(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        if (!element.attributes.containsKey("contentDescription")) {
            diagnostics.add(Diagnostic(DiagnosticLevel.INFO, path, "Image should have 'contentDescription' for accessibility"))
        }
    }

    private fun validateContainer(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        // Containers can have children, no specific validation needed
    }

    private fun validateSimple(element: UiElement, path: String, diagnostics: MutableList<Diagnostic>) {
        // Simple elements with no required attributes
    }
}

data class Diagnostic(
    val level: DiagnosticLevel,
    val path: String,
    val message: String
)

enum class DiagnosticLevel {
    ERROR,
    WARNING,
    INFO
}
