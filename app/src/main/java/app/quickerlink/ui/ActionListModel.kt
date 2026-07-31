package app.quickerlink.ui

import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.data.SavedAction

internal data class ActionListSection(
    val key: String,
    val title: String,
    val actions: List<SavedAction>,
)

internal fun buildActionListSections(
    actions: List<SavedAction>,
    query: String,
): List<ActionListSection> {
    val normalizedQuery = query.trim()
    val groupedActions = linkedMapOf<String, MutableActionListSection>()

    actions.asSequence()
        .filter { action -> action.matchesQuery(normalizedQuery) }
        .forEach { action ->
            val (sectionKey, sectionTitle) = if (action.quickerActionId == null) {
                MANUAL_SECTION_KEY to MANUAL_SECTION_TITLE
            } else {
                val scene = action.sourceScene ?: QuickerPanelActionsProtocol.GLOBAL_SCENE
                val group = action.sourceGroup?.takeIf(String::isNotBlank)
                val title = "${action.syncedSceneLabel()} · ${group ?: UNGROUPED_SECTION_TITLE}"
                "scene:$scene:group:${group.orEmpty()}" to title
            }
            groupedActions.getOrPut(sectionKey) {
                MutableActionListSection(title = sectionTitle, actions = mutableListOf())
            }.actions += action
        }

    return groupedActions.map { (key, section) ->
        ActionListSection(key = key, title = section.title, actions = section.actions)
    }
}

internal fun resolveActionSectionIndex(
    sections: List<ActionListSection>,
    selectedKey: String?,
): Int = sections.indexOfFirst { it.key == selectedKey }.takeIf { it >= 0 } ?: 0

internal fun visibleActionsForSection(
    actions: List<SavedAction>,
    selectedKey: String?,
    query: String,
): List<SavedAction> = buildActionListSections(actions, query)
    .firstOrNull { it.key == selectedKey }
    ?.actions
    .orEmpty()

internal fun SavedAction.syncedSceneLabel(): String = when (sourceScene) {
    null, QuickerPanelActionsProtocol.GLOBAL_SCENE -> "全局"

    QuickerPanelActionsProtocol.COMMON_SCENE -> "通用"
    else -> "Quicker"
}

private data class MutableActionListSection(
    val title: String,
    val actions: MutableList<SavedAction>,
)

private fun SavedAction.matchesQuery(query: String): Boolean = query.isEmpty() ||
    label.contains(query, ignoreCase = true) ||
    actionTarget.contains(query, ignoreCase = true) ||
    sourceGroup?.contains(query, ignoreCase = true) == true ||
    sourceScene?.contains(query, ignoreCase = true) == true ||
    (quickerActionId != null && syncedSceneLabel().contains(query, ignoreCase = true))

private const val MANUAL_SECTION_TITLE = "手动添加"
private const val UNGROUPED_SECTION_TITLE = "未分组"
private const val MANUAL_SECTION_KEY = "system:manual"
