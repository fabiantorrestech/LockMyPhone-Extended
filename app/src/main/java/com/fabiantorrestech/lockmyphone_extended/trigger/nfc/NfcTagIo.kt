package com.fabiantorrestech.lockmyphone_extended.trigger.nfc

import android.app.Activity
import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import java.io.IOException
import java.security.SecureRandom

/** What writing a tag did. */
enum class TagWriteResult { WRITTEN, READ_ONLY, TOO_SMALL, NOT_WRITABLE, FAILED }

/** Talking to the NFC hardware: is it there and on, reader mode, and writing our record to a tag. */
object NfcTagIo {

    /**
     * "Write to tag" puts only a link like lockmyphone://tag/<code> on a tag, so Android opens
     * [NfcTagActivity] when it's tapped while the app is closed. On a Pixel 7, tags that also
     * carried an Android Application Record were never routed to that activity (Android only
     * opened the app), whatever the first record was; a link on its own is what other tag apps
     * use successfully there.
     */
    const val URI_SCHEME = "lockmyphone"
    const val URI_HOST = "tag"

    /** The MIME record written before links were used; still read, so those tags keep their code. */
    private const val LEGACY_MIME_TYPE = "application/vnd.fabiantorrestech.lockmyphone.tag"

    private const val TAG = "NfcTagIo"

    // Every tag technology. The NDEF check stays on, so tags can also be written.
    private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NFC_BARCODE

    fun hasNfc(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context) != null

    fun isOn(context: Context): Boolean = NfcAdapter.getDefaultAdapter(context)?.isEnabled == true

    /**
     * While [activity] is in front, every tag held to the phone goes to [onTag] (on a binder
     * thread) instead of to other apps. Call again with null to stop.
     */
    fun setReader(activity: Activity, onTag: ((Tag) -> Unit)?) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            if (onTag == null) {
                adapter.disableReaderMode(activity)
            } else {
                adapter.enableReaderMode(activity, { tag -> onTag(tag) }, READER_FLAGS, null)
            }
        }.onFailure { Log.w(TAG, "Reader mode change failed", it) }
    }

    /** A new random code for a tag: 16 bytes as hex. */
    fun newCode(): String = NfcRules.uidHex(ByteArray(CODE_BYTES).also { SecureRandom().nextBytes(it) })

    /** The code on a tag the reader just saw, or null if the tag doesn't carry our record. */
    fun readCode(tag: Tag): String? = codeInMessage(Ndef.get(tag)?.cachedNdefMessage)

    /** The code in the messages Android read from a tag (e.g. [NfcAdapter.EXTRA_NDEF_MESSAGES]). */
    fun codeIn(messages: Array<NdefMessage>?): String? = messages?.firstNotNullOfOrNull(::codeInMessage)

    private fun codeInMessage(message: NdefMessage?): String? {
        val records = message?.records ?: return null
        records.firstNotNullOfOrNull { record ->
            record.toUri()?.takeIf { it.scheme == URI_SCHEME && it.host == URI_HOST }?.lastPathSegment
        }?.let { return it }
        val legacy = records.firstOrNull {
            it.tnf == NdefRecord.TNF_MIME_MEDIA && String(it.type, Charsets.US_ASCII) == LEGACY_MIME_TYPE
        } ?: return null
        val payload = legacy.payload
        if (payload.size <= 1 || payload[0] != LEGACY_RECORD_VERSION) return null
        return String(payload, 1, payload.size - 1, Charsets.US_ASCII)
    }

    /** What's on a tag, one record per line, for the log: helps tell why a tag isn't routed to the app. */
    fun describe(tag: Tag): String {
        val message = Ndef.get(tag)?.cachedNdefMessage ?: return "no NDEF message (techs ${tag.techList.joinToString()})"
        return message.records.joinToString("; ") { record ->
            "tnf=${record.tnf} type=${String(record.type, Charsets.US_ASCII)} uri=${record.toUri()} mime=${record.toMimeType()}"
        }
    }

    /** Writes our link (with [code]), and nothing else. Blocking: call off the main thread. */
    fun write(tag: Tag, code: String): TagWriteResult {
        val message = NdefMessage(arrayOf(NdefRecord.createUri("$URI_SCHEME://$URI_HOST/$code")))
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.use {
                    it.connect()
                    when {
                        !it.isWritable -> TagWriteResult.READ_ONLY
                        it.maxSize < message.byteArrayLength -> TagWriteResult.TOO_SMALL
                        else -> {
                            it.writeNdefMessage(message)
                            TagWriteResult.WRITTEN
                        }
                    }
                }
            } else {
                val formatable = NdefFormatable.get(tag) ?: return TagWriteResult.NOT_WRITABLE
                formatable.use {
                    it.connect()
                    it.format(message)
                    TagWriteResult.WRITTEN
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Writing the tag failed", e)
            TagWriteResult.FAILED
        } catch (e: FormatException) {
            Log.w(TAG, "Writing the tag failed", e)
            TagWriteResult.FAILED
        }
    }

    /** The legacy MIME record's payload: this byte, then the tag's code. */
    private const val LEGACY_RECORD_VERSION: Byte = 2
    private const val CODE_BYTES = 16
}
