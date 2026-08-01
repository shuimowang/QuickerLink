package app.quickerlink.connection

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.quickerlink.data.AppPreferences
import app.quickerlink.data.PreferenceWriteResult
import app.quickerlink.notification.MobileNotificationPublisher
import app.quickerlink.notification.ReceiptCuePlayer
import app.quickerlink.notification.ReceiptCueOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface QuickerConnectionRuntimeEvent {
    data class TextReceived(
        val text: String,
        val source: String,
    ) : QuickerConnectionRuntimeEvent

    data class NotificationReceived(
        val bodyLength: Int,
        val published: Boolean,
    ) : QuickerConnectionRuntimeEvent

    data class FileOffered(
        val name: String,
    ) : QuickerConnectionRuntimeEvent

    data class CommandRejected(
        val summary: String,
    ) : QuickerConnectionRuntimeEvent
}

data class QuickerReceivedText(
    val text: String,
    val source: String,
    val sequence: Long,
)

internal data class QuickerIncomingFileOffer(
    val descriptor: QuickerTransferDescriptor,
    val connection: QuickerConnectionBinding,
    val actionId: String,
)

class QuickerConnectionRuntime(context: Context) {
    val manager = QuickerConnectionManager()

    private val applicationContext = context.applicationContext
    private val preferences = AppPreferences(applicationContext)
    private val clipboardManager =
        applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settingsLock = Any()
    private val mutableBackgroundConnectionEnabled = MutableStateFlow(
        preferences.loadFeatureSettings().backgroundConnectionEnabled,
    )

    val backgroundConnectionEnabled: StateFlow<Boolean> =
        mutableBackgroundConnectionEnabled.asStateFlow()

    private val mutableEvents = MutableSharedFlow<QuickerConnectionRuntimeEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<QuickerConnectionRuntimeEvent> = mutableEvents.asSharedFlow()

    private val mutableIncomingFileOffer = MutableStateFlow<QuickerIncomingFileOffer?>(null)
    internal val incomingFileOffer: StateFlow<QuickerIncomingFileOffer?> =
        mutableIncomingFileOffer.asStateFlow()

    private val mutableReceivedText = MutableStateFlow<QuickerReceivedText?>(null)
    val receivedText: StateFlow<QuickerReceivedText?> = mutableReceivedText.asStateFlow()

    private var receivedTextSequence = 0L

    @Volatile
    private var companionActionId = preferences.loadConnection().serviceActionId
        ?: QuickerPanelActionsProtocol.COMPANION_SHARED_ACTION_ID

    @Volatile
    private var appInForeground = false

    init {
        scope.launch {
            manager.commands.collect(::handleIncomingCommand)
        }
    }

    internal fun setAppInForeground(value: Boolean) {
        appInForeground = value
    }

    internal fun isAppInForeground(): Boolean = appInForeground

    internal fun setCompanionActionId(actionId: String) {
        require(actionId.isNotBlank()) { "Quicker Link 动作 ID 不能为空" }
        companionActionId = actionId
    }

    fun shouldRetainConnection(): Boolean = shouldRetainConnection(
        appInForeground = appInForeground,
        backgroundConnectionEnabled = mutableBackgroundConnectionEnabled.value,
    )

    fun setBackgroundConnectionEnabled(enabled: Boolean): PreferenceWriteResult = synchronized(settingsLock) {
        if (mutableBackgroundConnectionEnabled.value == enabled) return PreferenceWriteResult.Success

        val current = preferences.loadFeatureSettings()
        val result = preferences.saveFeatureSettings(current.copy(backgroundConnectionEnabled = enabled))
        when (result) {
            PreferenceWriteResult.Success -> mutableBackgroundConnectionEnabled.value = enabled
            is PreferenceWriteResult.Failure -> Unit
        }
        result
    }

    internal fun clearIncomingFileOffer(expectedId: String): QuickerIncomingFileOffer? {
        val current = mutableIncomingFileOffer.value ?: return null
        if (current.descriptor.id != expectedId) return null
        mutableIncomingFileOffer.value = null
        return current
    }

    fun clearIncomingFileOffer() {
        mutableIncomingFileOffer.value = null
    }

    private fun handleIncomingCommand(incomingCommand: QuickerIncomingCommand) {
        if (!manager.isCommandCurrent(incomingCommand)) return
        when (incomingCommand.message.operation) {
            "copy" -> handleLegacyText(incomingCommand)
            QuickerMobilePushProtocol.OPERATION -> handleMobilePush(incomingCommand)
            else -> manager.replyToCommand(incomingCommand, false, "unsupported_operation")
        }
    }

    private fun handleLegacyText(incomingCommand: QuickerIncomingCommand) {
        val text = normalizeIncomingDesktopText(
            QuickerProtocol.displayData(incomingCommand.message.data).orEmpty(),
        )
        if (!isValidIncomingText(text)) {
            manager.replyToCommand(incomingCommand, false, "text_too_large")
            mutableEvents.tryEmit(
                QuickerConnectionRuntimeEvent.CommandRejected("Quicker 发来的文本过大，已拒绝"),
            )
            return
        }
        if (!writePhoneClipboard("Quicker", text)) {
            manager.replyToCommand(incomingCommand, false, "clipboard_unavailable")
            return
        }
        if (!manager.replyToCommand(incomingCommand, true, "ok")) return
        publishReceivedText(text, "Quicker")
    }

    private fun handleMobilePush(incomingCommand: QuickerIncomingCommand) {
        val push = runCatching { QuickerMobilePushProtocol.parse(incomingCommand.message.data) }
            .getOrElse {
                manager.replyToCommand(incomingCommand, false, "invalid_push")
                mutableEvents.tryEmit(
                    QuickerConnectionRuntimeEvent.CommandRejected("收到格式无效的电脑推送，已拒绝"),
                )
                return
            }
        when (push) {
            is QuickerMobilePush.Text -> {
                val text = normalizeIncomingDesktopText(push.text)
                if (!isValidIncomingText(text)) {
                    manager.replyToCommand(incomingCommand, false, "invalid_text")
                    return
                }
                val copiedToClipboard = writePhoneClipboard("Quicker Link", text)
                if (!manager.replyToCommand(
                    incomingCommand,
                    true,
                    if (copiedToClipboard) "ok" else "received",
                )) return
                publishReceivedText(text, "电脑", copiedToClipboard)
            }

            is QuickerMobilePush.Notification -> {
                val published = MobileNotificationPublisher.publish(
                    applicationContext,
                    push.title,
                    push.body,
                )
                val replied = manager.replyToCommand(
                    incomingCommand,
                    published,
                    if (published) "ok" else "notification_permission_required",
                )
                if (!replied) return
                mutableEvents.tryEmit(
                    QuickerConnectionRuntimeEvent.NotificationReceived(
                        bodyLength = push.body.length,
                        published = published,
                    ),
                )
                ReceiptCuePlayer.play(
                    applicationContext,
                    ReceiptCueOutcome.NOTIFICATION_ACCEPTED,
                )
            }

            is QuickerMobilePush.FileOffer -> {
                if (mutableIncomingFileOffer.value != null) {
                    manager.replyToCommand(incomingCommand, false, "busy")
                    return
                }
                val connection = manager.currentReadyConnectionBinding()
                if (connection == null || !manager.isCommandCurrent(incomingCommand)) {
                    manager.replyToCommand(incomingCommand, false, "connection_changed")
                    return
                }
                mutableIncomingFileOffer.value = QuickerIncomingFileOffer(
                    descriptor = push.descriptor,
                    connection = connection,
                    actionId = companionActionId,
                )
                if (!manager.replyToCommand(incomingCommand, true, "offered")) {
                    clearIncomingFileOffer(push.descriptor.id)
                    return
                }
                if (!appInForeground) {
                    MobileNotificationPublisher.publish(
                        applicationContext,
                        "电脑发来文件",
                        "打开 Quicker Link 接收 ${push.descriptor.name}",
                    )
                }
                mutableEvents.tryEmit(
                    QuickerConnectionRuntimeEvent.FileOffered(push.descriptor.name),
                )
                ReceiptCuePlayer.play(
                    applicationContext,
                    ReceiptCueOutcome.FILE_OFFER_ACCEPTED,
                )
            }
        }
    }

    private fun isValidIncomingText(text: String): Boolean =
        text.isNotBlank() &&
            text.length <= QuickerToolboxProtocol.MAX_CLIPBOARD_CHARS &&
            text.toByteArray(Charsets.UTF_8).size <= MAX_INCOMING_TEXT_BYTES

    private fun writePhoneClipboard(label: String, text: String): Boolean {
        return runCatching {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        }.isSuccess
    }

    private fun publishReceivedText(
        text: String,
        source: String,
        copiedToClipboard: Boolean = true,
    ) {
        receivedTextSequence = if (receivedTextSequence == Long.MAX_VALUE) 1L else receivedTextSequence + 1L
        mutableReceivedText.value = QuickerReceivedText(text, source, receivedTextSequence)
        if (!appInForeground) {
            MobileNotificationPublisher.publish(
                applicationContext,
                "收到电脑文本",
                receivedTextNotificationBody(copiedToClipboard),
            )
        }
        mutableEvents.tryEmit(QuickerConnectionRuntimeEvent.TextReceived(text, source))
        ReceiptCuePlayer.play(applicationContext, ReceiptCueOutcome.TEXT_ACCEPTED)
    }

    private companion object {
        const val MAX_INCOMING_TEXT_BYTES = 48 * 1024
    }
}

internal fun shouldRetainConnection(
    appInForeground: Boolean,
    backgroundConnectionEnabled: Boolean,
): Boolean = appInForeground || backgroundConnectionEnabled

internal fun normalizeIncomingDesktopText(text: String): String =
    if (text.startsWith(DESKTOP_TEXT_PREFIX, ignoreCase = true)) {
        text.substring(DESKTOP_TEXT_PREFIX.length)
    } else {
        text
    }

private const val DESKTOP_TEXT_PREFIX = "sendText:"

internal fun receivedTextNotificationBody(copiedToClipboard: Boolean): String =
    if (copiedToClipboard) {
        "已复制到手机剪贴板"
    } else {
        "已保存到 Quicker Link，打开 App 查看"
    }
