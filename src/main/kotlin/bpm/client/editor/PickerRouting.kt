package bpm.client.editor

import imgui.ImGui
import io.osrsx.vscript.editor.graph.ColorPicker
import io.osrsx.vscript.editor.graph.ValuePicker
import io.osrsx.vscript.model.FunctionPin
import io.osrsx.vscript.model.Literals
import io.osrsx.vscript.model.TypeRef
import io.osrsx.vscript.runtime.EditorDoc

/**
 * The value and colour pickers are singletons drawn once per frame from the outermost surface, into the
 * foreground list, and whatever they answer is written to the document by the widget id that opened them.
 * The id grammar is vscript's (`ValueEditors` / `OutlinePanel`); this is a port of the shell's routing.
 */
object PickerRouting {
    val anyOpen: Boolean get() = ValuePicker.isOpen || ColorPicker.openId != null

    /** Draw the pickers; [doc] receives the pick (null or a read-only doc just closes them). */
    fun render(doc: EditorDoc?, canEdit: Boolean) {
        ValuePicker.openId?.let { id ->
            val chosen = ValuePicker.render(
                ImGui.getForegroundDrawList(),
                ImGui.getWindowPosX(), ImGui.getWindowPosY(),
                ImGui.getWindowWidth(), ImGui.getWindowHeight(),
            )
            if (chosen != null && doc != null && canEdit) commit(doc, id, chosen)
        }
        ColorPicker.openId?.let { id ->
            val argb = ColorPicker.render()
            if (argb != null && doc != null && canEdit) commit(doc, id, argb)
        }
    }

    fun closeAll() {
        ValuePicker.close()
        ColorPicker.close()
    }

    fun commit(d: EditorDoc, id: String, chosen: Any) {
        when {
            id.startsWith("vd|") -> {
                val name = id.removePrefix("vd|")
                d.variable(name)?.let { v -> d.updateVariable(v.name, v.type, chosen) }
            }
            id.startsWith("vt|") -> {
                val type = chosen as? TypeRef ?: return
                val name = id.removePrefix("vt|")
                d.variable(name)?.let { v ->
                    // A host enum has no zero the compiler could supply, so a variable retyped to one starts
                    // at its first member (or the member its old default spelled) rather than at nothing —
                    // which the validator would refuse until a default was picked.
                    val enum = io.osrsx.vscript.model.HostEnums.of(type.required().name)
                    val default = if (enum != null && !type.optional) {
                        val text = v.default?.toString().orEmpty()
                        enum.members.firstOrNull { it.equals(text, ignoreCase = true) } ?: enum.members.first()
                    } else {
                        Literals.of(type, v.default?.toString() ?: "", d.enums)
                    }
                    d.updateVariable(v.name, type, default)
                }
            }
            id.startsWith("ve|") -> {
                val elem = chosen as? TypeRef ?: return
                val name = id.removePrefix("ve|")
                d.variable(name)?.let { v ->
                    val slots = (v.default as? List<*>).orEmpty().map { Literals.of(elem, it?.toString() ?: "", d.enums) }
                    d.updateVariable(v.name, TypeRef.list(elem), slots)
                }
            }
            id.startsWith("vs|") -> {
                val parts = id.split('|')
                val name = parts.getOrNull(1) ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                d.variable(name)?.let { v ->
                    val slots = (v.default as? List<*>).orEmpty().toMutableList()
                    if (index !in slots.indices) return
                    slots[index] = chosen
                    d.updateVariable(v.name, v.type, slots.toList())
                }
            }
            id.startsWith("st|") -> {
                val type = chosen as? TypeRef ?: return
                val parts = id.split('|')
                val t = d.struct(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                if (index !in t.fields.indices) return
                val fields = t.fields.toMutableList()
                fields[index] = FunctionPin(fields[index].name, type)
                d.updateStruct(t.name, fields)
            }
            id.startsWith("ft|") -> {
                val type = chosen as? TypeRef ?: return
                val parts = id.split('|')
                val fn = d.function(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(3)?.toIntOrNull() ?: return
                val ins = fn.params.toMutableList()
                val outs = fn.results.toMutableList()
                val side = if (parts.getOrNull(2) == "in") ins else outs
                if (index !in side.indices) return
                side[index] = FunctionPin(side[index].name, type)
                d.updateFunction(fn.name, ins, outs)
            }
            id.startsWith("fe|") -> {
                val elem = chosen as? TypeRef ?: return
                val parts = id.split('|')
                val fn = d.function(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(3)?.toIntOrNull() ?: return
                val ins = fn.params.toMutableList()
                val outs = fn.results.toMutableList()
                val side = if (parts.getOrNull(2) == "in") ins else outs
                if (index !in side.indices) return
                val old = side[index]
                side[index] = FunctionPin(old.name, listOf(old.type, elem), old.default)
                d.updateFunction(fn.name, ins, outs)
            }
            id.startsWith("se|") -> {
                val elem = chosen as? TypeRef ?: return
                val parts = id.split('|')
                val t = d.struct(parts.getOrNull(1) ?: return) ?: return
                val index = parts.getOrNull(2)?.toIntOrNull() ?: return
                if (index !in t.fields.indices) return
                val fields = t.fields.toMutableList()
                val old = fields[index]
                fields[index] = FunctionPin(old.name, listOf(old.type, elem), old.default)
                d.updateStruct(t.name, fields)
            }
            id.startsWith("v|") -> {
                val parts = id.split('|')
                val nodeId = parts.getOrNull(1)?.toIntOrNull() ?: return
                val pin = parts.getOrNull(3) ?: return
                d.setLiteral(nodeId, pin, chosen)
            }
        }
    }
}

/** `LIST<elem>`, optional if the list [was] — what the "Of" chip on a list-typed pin or field chooses. */
private fun listOf(was: TypeRef, elem: TypeRef): TypeRef = TypeRef.list(elem).let { if (was.optional) it.orNull() else it }
