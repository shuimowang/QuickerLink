package app.quickerlink.data

import java.util.UUID

data class SavedAction(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val actionTarget: String,
    val parameter: String = "",
    val confirmBeforeRun: Boolean = false,
    val quickerActionId: String? = null,
    val sourceGroup: String? = null,
    val sourceScene: String? = null,
)

data class StoredConnection(
    val ipAddress: String = "",
    val port: Int = 668,
    val rememberPassword: Boolean = false,
    val password: String = "",
    val requiresPassword: Boolean = false,
    val serviceActionId: String? = null,
)
