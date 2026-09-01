package bpm

/**
 * The names of the mod's own scripting types, as strings.
 *
 * They live here because the core needs to recognise one of them without being able to see the catalogue
 * that defines it: `LinkRenames` walks a document looking for Link-typed literals, and a document is
 * plain data. `McTypes` builds its `TypeRef`s from these, so there is still one place the name is written.
 */
object BpmTypeNames {
    const val LINK = "Link"
}
