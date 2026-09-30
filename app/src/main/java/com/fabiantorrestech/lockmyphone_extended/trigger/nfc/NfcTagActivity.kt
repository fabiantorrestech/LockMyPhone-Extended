package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import kotlinx.coroutines.launch

/**
 * Opened by Android when a tag written by this app is tapped while the app isn't in front. It
 * has no screen: it hands the tag's ID to [NfcTagHandler] and closes right away. It must be
 * exported for Android to deliver tags, so it can't end a lock (see [NfcTagHandler.onTagTapped]).
 */
class NfcTagActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nfcIntent = intent?.takeIf { it.action == NfcAdapter.ACTION_NDEF_DISCOVERED }
        val id = nfcIntent?.getByteArrayExtra(NfcAdapter.EXTRA_ID)
        if (id != null) {
            val code = NfcTagIo.codeIn(
                IntentCompat.getParcelableArrayExtra(nfcIntent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                    ?.filterIsInstance<NdefMessage>()?.toTypedArray()
            )
            val container = LockApp.container(this)
            val appContext = applicationContext
            // The app scope outlives this activity, which must finish before it's shown.
            container.appScope.launch {
                val message = container.nfcTagHandler.onTagTapped(NfcRules.uidHex(id), code, readInApp = false)
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
