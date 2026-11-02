package me.shirazu.dshmizar.ui

import me.shirazu.mizar.kit.ModelCatalog
import me.shirazu.mizar.kit.ModelSelection

/**
 * What the model pill says: the model's id, then the level's name.
 *
 * The composer's pill and the home screen's pill each carried a copy of this, and
 * a copy is exactly how two pills end up naming different routes for the same
 * choice. The rules are the ones both copies already followed:
 *
 *  - the level is spelled the way the catalogue spells it, because the wire value
 *    need not read like a word ("max", "high");
 *  - a level the catalogue does not list is capitalised instead of dropped, so a
 *    host that grew a new level still gets a readable pill;
 *  - a selection with no level is the model alone, with no separator.
 *
 * `null` means "nothing chosen yet"; the caller decides what to say instead.
 */
internal fun modelLabel(selection: ModelSelection?, catalog: ModelCatalog?): String? {
    if (selection == null) return null
    val effortName = selection.reasoningEffort?.let { effort ->
        catalog?.providers
            ?.firstOrNull { it.id == selection.provider }
            ?.models?.firstOrNull { it.id == selection.model }
            ?.efforts?.firstOrNull { it.id == effort }?.name
            ?: effort.replaceFirstChar { it.uppercase() }
    }
    return listOfNotNull(selection.model, effortName).joinToString(" · ")
}
