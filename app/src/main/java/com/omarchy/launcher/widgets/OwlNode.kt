package com.omarchy.launcher.widgets

/**
 * A scoped subset inspired by omalauncher's OWL widget language
 * (https://github.com/SimonSchubert/omalauncher/tree/main/owl, MIT licensed) - a small
 * declarative, on-device-editable format for home-screen widgets. This is NOT a clone of OWL:
 * it drops shell/HTTP/stream data sources, reactive `state`/`let`, canvases/shaders, and the
 * widget marketplace. What's kept is the spirit - plain-text `Name { prop: value; children }`
 * nodes, grid-ish sizing, `${...}` interpolation, and onTap handlers - scaled down to something
 * a single pass can implement and verify correctly.
 */
sealed class OwlNode {
    abstract val props: Map<String, String>

    data class Column(override val props: Map<String, String>, val children: List<OwlNode>) : OwlNode()
    data class Row(override val props: Map<String, String>, val children: List<OwlNode>) : OwlNode()
    data class Box(override val props: Map<String, String>, val children: List<OwlNode>) : OwlNode()
    data class Text(override val props: Map<String, String>) : OwlNode()
    data class Spacer(override val props: Map<String, String>) : OwlNode()
    data class Button(override val props: Map<String, String>) : OwlNode()
}

/** A parsed widget file: its declared grid size and its single root node. */
data class OwlWidget(val sizeCols: Int, val sizeRows: Int, val root: OwlNode)

class OwlParseException(message: String) : Exception(message)
