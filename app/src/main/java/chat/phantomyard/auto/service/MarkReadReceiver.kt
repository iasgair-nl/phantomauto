package chat.phantomyard.auto.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

private const val EXTRA_CONVERSATION_ID = "conversation_id"

/**
 * Handles the invisible mark-as-read action Android Auto requires on messaging
 * notifications (Action.SEMANTIC_ACTION_MARK_AS_READ, setShowsUserInterface(false)).
 * Android Auto/the system may fire this without any user-visible UI, including
 * automatically after reading a message aloud.
 */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        PhantomAutoService.instance?.markConversationRead(conversationId)
    }

    companion object {
        fun buildIntent(context: Context, conversationId: String): Intent {
            return Intent(context, MarkReadReceiver::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
        }
    }
}
