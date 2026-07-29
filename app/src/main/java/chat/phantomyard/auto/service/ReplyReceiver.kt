package chat.phantomyard.auto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

private const val EXTRA_CONVERSATION_ID = "conversation_id"

/** Handles the RemoteInput reply action on a message notification (including Android
 * Auto's voice reply). Forwards the typed/spoken text to the running PhantomAutoService. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText = results.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
        if (replyText.isNullOrEmpty()) return
        PhantomAutoService.instance?.sendReplyFromNotification(conversationId, replyText)
    }

    companion object {
        const val KEY_REPLY_TEXT = "key_reply_text"

        fun buildIntent(context: Context, conversationId: String): Intent {
            return Intent(context, ReplyReceiver::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
    }
}
