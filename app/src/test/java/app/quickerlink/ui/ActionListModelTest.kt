package app.quickerlink.ui

import app.quickerlink.connection.QuickerPanelActionsProtocol
import app.quickerlink.data.SavedAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionListModelTest {
    @Test
    fun `groups global common and manual actions while preserving first seen order`() {
        val actions = listOf(
            synced("全局一", "常用"),
            synced("全局未分组", null),
            synced("通用一", "默认", QuickerPanelActionsProtocol.COMMON_SCENE),
            synced("全局二", "常用"),
            SavedAction(label = "手工动作", actionTarget = "目标"),
        )

        val sections = buildActionListSections(actions, query = "")

        assertEquals(
            listOf("全局 · 常用", "全局 · 未分组", "通用 · 默认", "手动添加"),
            sections.map(ActionListSection::title),
        )
        assertEquals(listOf("全局一", "全局二"), sections.first().actions.map(SavedAction::label))
    }

    @Test
    fun `keeps same named groups separate across scenes`() {
        val actions = listOf(
            synced("全局动作", "默认"),
            synced("通用动作", "默认", QuickerPanelActionsProtocol.COMMON_SCENE),
        )

        val sections = buildActionListSections(actions, query = "")

        assertEquals(listOf("全局 · 默认", "通用 · 默认"), sections.map(ActionListSection::title))
        assertEquals(2, sections.map(ActionListSection::key).distinct().size)
    }

    @Test
    fun `searches names groups scenes and action targets case insensitively`() {
        val actions = listOf(
            synced("打开项目", "开发"),
            synced("启动浏览器", "常用", QuickerPanelActionsProtocol.COMMON_SCENE),
            SavedAction(label = "自定义", actionTarget = "Run Report"),
        )

        assertEquals(listOf("打开项目"), labels(buildActionListSections(actions, "开发")))
        assertEquals(listOf("启动浏览器"), labels(buildActionListSections(actions, "通用")))
        assertEquals(listOf("启动浏览器"), labels(buildActionListSections(actions, "common")))
        assertEquals(listOf("自定义"), labels(buildActionListSections(actions, "report")))
    }

    @Test
    fun `blank and unmatched queries have stable results`() {
        val actions = listOf(synced("动作", "常用"))

        assertEquals(listOf("动作"), labels(buildActionListSections(actions, "   ")))
        assertEquals(emptyList<String>(), labels(buildActionListSections(actions, "不存在")))
    }

    @Test
    fun `legacy synced actions without a scene are displayed as global`() {
        val actions = listOf(synced("旧动作", "默认", scene = null))

        val sections = buildActionListSections(actions, query = "")

        assertEquals(listOf("全局 · 默认"), sections.map(ActionListSection::title))
    }

    @Test
    fun `reserved display names do not merge Quicker groups with system sections`() {
        val actions = listOf(
            synced("同步动作", "手动添加"),
            SavedAction(label = "手工动作", actionTarget = "目标"),
        )

        val sections = buildActionListSections(actions, query = "")

        assertEquals(listOf("全局 · 手动添加", "手动添加"), sections.map(ActionListSection::title))
        assertEquals(2, sections.map(ActionListSection::key).distinct().size)
    }

    @Test
    fun `removed selected section falls back to first navigation item`() {
        val sections = buildActionListSections(
            listOf(
                synced("全局动作", "常用"),
                synced("通用动作", "默认", QuickerPanelActionsProtocol.COMMON_SCENE),
            ),
            query = "",
        )

        assertEquals(1, resolveActionSectionIndex(sections, sections.last().key))
        assertEquals(0, resolveActionSectionIndex(sections.dropLast(1), sections.last().key))
    }

    @Test
    fun `search is scoped to selected navigation section`() {
        val actions = listOf(
            synced("截图", "常用"),
            synced("截图设置", "工具", QuickerPanelActionsProtocol.COMMON_SCENE),
        )
        val sections = buildActionListSections(actions, query = "")

        assertEquals(
            listOf("截图"),
            visibleActionsForSection(actions, sections.first().key, "截图").map(SavedAction::label),
        )
        assertEquals(
            emptyList<String>(),
            visibleActionsForSection(actions, sections.first().key, "设置").map(SavedAction::label),
        )
    }

    private fun synced(
        label: String,
        group: String?,
        scene: String? = QuickerPanelActionsProtocol.GLOBAL_SCENE,
    ): SavedAction = SavedAction(
        label = label,
        actionTarget = "11111111-1111-4111-8111-${label.hashCode().toUInt().toString().padStart(12, '0').takeLast(12)}",
        quickerActionId = "synced:$label",
        sourceGroup = group,
        sourceScene = scene,
    )

    private fun labels(sections: List<ActionListSection>): List<String> =
        sections.flatMap(ActionListSection::actions).map(SavedAction::label)
}
