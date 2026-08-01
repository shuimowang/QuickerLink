package app.quickerlink.data

import java.util.UUID

data class ActionParameterChoice(
    val label: String,
    val value: String,
)

data class SavedAction(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val actionTarget: String,
    val parameter: String = "",
    val parameterChoices: List<ActionParameterChoice> = emptyList(),
    val confirmBeforeRun: Boolean = false,
    val quickerActionId: String? = null,
    val sourceGroup: String? = null,
    val sourceScene: String? = null,
    val icon: String? = null,
)

data class StoredConnection(
    val ipAddress: String = "",
    val port: Int = 668,
    val rememberPassword: Boolean = false,
    val password: String = "",
    val requiresPassword: Boolean = false,
    val serviceActionId: String? = null,
)

data class FeatureSettings(
    val backgroundConnectionEnabled: Boolean = DEFAULT_BACKGROUND_CONNECTION_ENABLED,
    val receiptCueEnabled: Boolean = DEFAULT_RECEIPT_CUE_ENABLED,
)

const val DEFAULT_BACKGROUND_CONNECTION_ENABLED = true
const val DEFAULT_RECEIPT_CUE_ENABLED = true
