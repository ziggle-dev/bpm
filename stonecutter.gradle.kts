plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.1"

/*
 * **Only the ACTIVE node's source is real.**
 *
 * Stonecutter rewrites the working tree in place for whichever version is active, so a plain
 * `:neoforge:1.21.4:build` compiles whatever the tree currently says — the ACTIVE node's variant, not
 * that node's. It looks like it worked and it did not, which is the most dangerous shape a build problem
 * can take.
 *
 * So: switch first, then build. `gradlew "Set active project to 1.21.4"` rewrites the tree and then the
 * ordinary per-node tasks mean what they say. `Reset active project` puts it back to 1.21.1, and it must
 * be run before committing — the line above is what a commit records, and a tree committed in another
 * version's state produces a whole-tree diff and conflicts in files nobody touched.
 *
 * A chiseled task would automate the switching for CI. Stonecutter 0.9.8 exposes `stonecutter.tasks` with
 * `named`/`order` for this, but registering one there produced no visible task, so it is left for when CI
 * needs it rather than guessed at.
 */
