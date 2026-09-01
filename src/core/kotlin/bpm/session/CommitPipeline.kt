package bpm.session

import bpm.library.DocumentStore
import bpm.library.DocumentCodec
import dev.ziggle.vscript.compile.Issue
import dev.ziggle.vscript.compile.Severity
import dev.ziggle.vscript.compile.Validator
import dev.ziggle.vscript.model.BuiltinNodes
import dev.ziggle.vscript.model.GraphDoc
import dev.ziggle.vscript.model.NodeCatalog
import java.util.UUID

enum class CommitStatus { OK, UNCHANGED, CONFLICT, NOT_HOLDER, NOT_FOUND, BAD_FORMAT, REJECTED, TOO_LARGE }

class CommitRequest(val docId: UUID, val baseVersion: Int, val sha256: String, val json: String, val deploy: Boolean)

class CommitOutcome(
    val status: CommitStatus,
    val version: Int,
    val sha256: String,
    val issues: List<Issue>,
    val message: String,
    /** Controllers asked to restart by this commit. */
    val deployed: Int,
) {
    val errors: Int get() = issues.count { it.severity == Severity.ERROR }
}

/** A controller bound to a document, as the pipeline sees it. */
interface Deployable {
    val enabled: Boolean
    fun requestRestart()
}

/**
 * The one way a document changes from the network: check, parse, validate, store, maybe deploy.
 *
 * Order matters and each step has its own answer: the raw size cap first (a lie about size never reaches
 * gunzip — that happened in the chunk assembler); then the document must exist and the committer must hold
 * its lease; then the version the client edited from must still be current, or it is a CONFLICT and the
 * client keeps its copy; unchanged text is UNCHANGED and stores nothing (but still deploys when asked, which
 * is how "deploy the version already saved" works); then the JSON must parse and must not carry an `on render`
 * entry (the mod hides it); validation issues come back with the result and the document is **stored even
 * with errors** — an author's half-finished work must never be lost to a validator — flagged so nothing
 * deploys it. Deploy restarts every bound *and enabled* controller and only when the document has no errors.
 */
class CommitPipeline(
    private val library: DocumentStore,
    private val catalog: NodeCatalog,
    private val bound: (UUID) -> List<Deployable>,
    private val maxRawBytes: Int = DocumentCodec.MAX_RAW_BYTES,
) {
    fun commit(isHolder: Boolean, req: CommitRequest): CommitOutcome {
        val size = req.json.toByteArray(Charsets.UTF_8).size
        val record = library[req.docId]
            ?: return CommitOutcome(CommitStatus.NOT_FOUND, 0, "", emptyList(), "the document no longer exists", 0)
        if (size > maxRawBytes) return CommitOutcome(CommitStatus.TOO_LARGE, record.version, record.sha256, emptyList(), "document is $size bytes; the limit is $maxRawBytes", 0)
        if (!isHolder) return CommitOutcome(CommitStatus.NOT_HOLDER, record.version, record.sha256, emptyList(), "you do not hold the edit lease", 0)
        if (req.baseVersion != record.version) {
            return CommitOutcome(CommitStatus.CONFLICT, record.version, record.sha256, emptyList(), "the document is at v${record.version}; you edited v${req.baseVersion}", 0)
        }
        val sha = DocumentCodec.sha256(req.json)
        if (sha == record.sha256) {
            val deployed = if (req.deploy && !record.hasErrors) deploy(req.docId) else 0
            return CommitOutcome(CommitStatus.UNCHANGED, record.version, sha, emptyList(), if (deployed > 0) "unchanged; $deployed controllers restarting" else "unchanged", deployed)
        }
        val graph = try {
            GraphDoc.fromJson(req.json)
        } catch (e: Exception) {
            return CommitOutcome(CommitStatus.BAD_FORMAT, record.version, record.sha256, emptyList(), "not a graph document: ${e.message}", 0)
        }
        graph.nodes.firstOrNull { it.type == BuiltinNodes.ENTRY_RENDER }?.let {
            return CommitOutcome(CommitStatus.REJECTED, record.version, record.sha256, emptyList(), "'On Render' is not available in bpm (node ${it.id})", 0)
        }
        val issues = Validator(catalog, library.graphSource(), tickMayWait = true).validate(graph)
        val errors = issues.count { it.severity == Severity.ERROR }
        val stored = library.store(req.docId, req.json, hasErrors = errors > 0)
            ?: return CommitOutcome(CommitStatus.NOT_FOUND, 0, "", emptyList(), "the document vanished while committing", 0)
        val deployed = if (req.deploy && errors == 0) deploy(req.docId) else 0
        val message = when {
            errors > 0 && req.deploy -> "saved v${stored.version} with $errors errors — not deployed"
            errors > 0 -> "saved v${stored.version} with $errors errors"
            req.deploy -> "saved v${stored.version}; $deployed controllers restarting"
            else -> "saved v${stored.version}"
        }
        return CommitOutcome(CommitStatus.OK, stored.version, stored.sha256, issues, message, deployed)
    }

    private fun deploy(docId: UUID): Int {
        var n = 0
        for (c in bound(docId)) {
            if (!c.enabled) continue
            c.requestRestart()
            n++
        }
        return n
    }
}
