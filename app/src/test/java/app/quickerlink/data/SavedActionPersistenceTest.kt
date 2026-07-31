package app.quickerlink.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedActionPersistenceTest {
    @Test
    fun olderSavedActionsDefaultMissingParameterChoicesToEmptyList() {
        val actions = decodeSavedActions(
            """
                [{
                  "id":"legacy",
                  "label":"旧动作",
                  "actionTarget":"Legacy Action",
                  "parameter":"payload",
                  "confirmBeforeRun":true
                }]
            """.trimIndent(),
        )

        assertEquals(1, actions.size)
        assertTrue(actions.single().parameterChoices.isEmpty())
        assertEquals("payload", actions.single().parameter)
        assertTrue(actions.single().confirmBeforeRun)
    }

    @Test
    fun savedParameterChoicesRoundTripThroughGsonShape() {
        val actions = decodeSavedActions(
            """
                [{
                  "id":"synced",
                  "label":"录制工具",
                  "actionTarget":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                  "parameter":"action_ffmpeg",
                  "parameterChoices":[
                    {"label":"设置","value":"action_settings"},
                    {"label":"录制","value":"action_ffmpeg"}
                  ],
                  "confirmBeforeRun":false,
                  "quickerActionId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                }]
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                ActionParameterChoice("设置", "action_settings"),
                ActionParameterChoice("录制", "action_ffmpeg"),
            ),
            actions.single().parameterChoices,
        )
    }
}
