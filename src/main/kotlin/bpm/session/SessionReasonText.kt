package bpm.session


/** What to tell the player when their session state changed. Null when nothing needs saying. */
object SessionReasonText {
    fun describe(reason: SessionReason, isHolder: Boolean, holderName: String): String? = when (reason) {
        SessionReason.NONE -> if (!isHolder && holderName.isNotEmpty()) "$holderName is editing — you are viewing" else null
        SessionReason.GRANTED -> if (isHolder) "you have the edit lease" else null
        SessionReason.RELEASED -> if (isHolder) null else "the document is free to edit"
        SessionReason.STOLEN -> if (isHolder) "you took over the document" else "$holderName took over the document — you are viewing"
        SessionReason.EXPIRED -> if (isHolder) null else "your edit lease expired — you are viewing"
        SessionReason.DELETED -> "the document was deleted"
        SessionReason.HOLDER_LEFT -> "the editor left — the document is free"
    }
}
