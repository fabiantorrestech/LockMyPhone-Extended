package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.ui.LockChooserDialog
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme
import kotlinx.coroutines.launch

/**
 * Opened by Android when a tag written by this app is tapped while the app isn't in front. It's
 * see-through: it hands the tag to [NfcTagHandler], shows a toast, and closes, unless the tag is
 * linked to several locks, when it asks which one to start. It must be exported for Android to
 * deliver tags, so it can't end a lock (see [NfcTagHandler.onTagTapped]).
 */
class NfcTagActivity : ComponentActivity() {

    private val chooser = mutableStateOf<TapResult.ChooseLock?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = LockApp.container(this)
        setContent {
            val themeMode by container.settingsStore.themeMode.collectAsState()
            LockMyPhoneExtendedTheme(themeMode) {
                chooser.value?.let { choose ->
                    LockChooserDialog(
                        tagName = choose.tag.name,
                        choices = choose.choices,
                        onPick = { choice ->
                            lifecycleScope.launch {
                                showAndFinish(container.nfcTagHandler.startLock(choose.tag, choice.lockId))
                            }
                        },
                        onDismiss = ::finish,
                    )
                }
            }
        }
        // A tap is handled once, not again after a rotation.
        if (savedInstanceState != null) return
        val tap = tapFrom(intent)
        if (tap == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            when (val result = container.nfcTagHandler.onTagTapped(tap.uid, tap.code, readInApp = false)) {
                is TapResult.Message -> showAndFinish(result.text)
                is TapResult.ChooseLock -> chooser.value = result
            }
        }
    }

    private fun showAndFinish(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    /** A tag's ID and code, as Android delivered them. */
    data class Tap(val uid: String, val code: String?)

    companion object {
        /**
         * The tag in an intent from Android's NFC service, or null if there's none. Also used by
         * the main screen, which Android opens instead when it doesn't route a tag here.
         */
        fun tapFrom(intent: Intent?): Tap? {
            val id = intent?.getByteArrayExtra(NfcAdapter.EXTRA_ID) ?: return null
            val messages = IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                ?.filterIsInstance<NdefMessage>()?.toTypedArray()
            return Tap(NfcRules.uidHex(id), NfcTagIo.codeIn(messages))
        }
    }
}
