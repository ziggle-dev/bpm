package bpm.client

import bpm.client.mc.WorkbenchScreen
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos

/** Entry points common code reaches into the client through — only ever executed on the client. */
object ClientHooks {
    /** Opens the editor, attached to the controller at [pos] when given. Deferred a tick so it opens over whatever screen is closing. */
    fun openWorkbench(pos: BlockPos?) {
        val mc = Minecraft.getInstance()
        // Nothing to edit without a world and a connection to speak to.
        if (mc.level == null || mc.player == null || mc.connection == null) return
        mc.tell { mc.setScreen(WorkbenchScreen(pos)) }
    }
}
