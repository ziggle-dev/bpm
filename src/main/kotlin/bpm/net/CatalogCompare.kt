package bpm.net

/**
 * The login handshake's verdict: null when both sides built the same catalogue, otherwise a message naming
 * what differs — the packs one side has and the other lacks, or, when the packs match, a version whose node
 * definitions changed (same ids, different hash).
 */
object CatalogCompare {
    fun mismatch(serverHash: String, serverPacks: List<String>, clientHash: String, clientPacks: List<String>): String? {
        if (serverHash == clientHash) return null
        val onlyServer = serverPacks.toSet() - clientPacks.toSet()
        val onlyClient = clientPacks.toSet() - serverPacks.toSet()
        val parts = ArrayList<String>()
        if (onlyServer.isNotEmpty()) parts += "missing on your client: ${onlyServer.sorted().joinToString(", ")}"
        if (onlyClient.isNotEmpty()) parts += "not on the server: ${onlyClient.sorted().joinToString(", ")}"
        if (parts.isEmpty()) {
            val same = serverPacks.toSet().intersect(clientPacks.toSet()).sorted()
            parts += "same packs (${same.joinToString(", ")}) but different node definitions — versions differ"
        }
        return "bpm node catalogue mismatch: " + parts.joinToString("; ") + " [server ${serverHash.take(8)}, client ${clientHash.take(8)}]"
    }
}
