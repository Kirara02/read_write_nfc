package com.kirara.nfcreader2

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.*
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kirara.nfcreader2.ui.theme.NFCReader2Theme
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.nio.charset.Charset
import kotlin.experimental.and


class MainActivity : ComponentActivity() {

    lateinit var writeTagFilters: Array<IntentFilter>
    var nfcAdapter: NfcAdapter? = null
    var pendingIntent: PendingIntent? = null
    var writeMode = false
    var myTag: Tag? = null
    var messageState = mutableStateListOf("")
    var nfcContent by mutableStateOf("")
    var tagId by mutableStateOf("")

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NFCReader2Theme {
                MainActivityScreen(
                    messages = messageState,
                    onMessageChange = { index, message -> messageState[index] = message },
                    onAddMessageClicked = { messageState.add("") },
                    onRemoveMessageClicked = { index -> messageState.removeAt(index) },
                    onWriteClicked = { onWriteButtonClicked(messageState) },
                    nfcContent = nfcContent,
                    tagId = tagId
                )
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            // Stop here, we definitely need NFC
            Toast.makeText(this, "This device doesn't support NFC.", Toast.LENGTH_LONG).show()
            finish()
        }

        //For when the activity is launched by the intent-filter for android.nfc.action.NDEF_DISCOVERE
        readFromIntent(intent)
        pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        val tagDetected = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        tagDetected.addCategory(Intent.CATEGORY_DEFAULT)
        writeTagFilters = arrayOf(tagDetected)
    }

    /******************************************************************************
     * Read From NFC Tag
     ****************************************************************************/
    private fun readFromIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_TAG_DISCOVERED == action || NfcAdapter.ACTION_TECH_DISCOVERED == action || NfcAdapter.ACTION_NDEF_DISCOVERED == action) {
            myTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            }else{
                intent.getParcelableExtra<Parcelable>(NfcAdapter.EXTRA_TAG) as Tag?
            }
            val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            } else {
                intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
            }
            val msgs = mutableListOf<NdefMessage>()
            if (rawMsgs != null) {
                for (i in rawMsgs.indices) {
                    msgs.add(i, rawMsgs[i] as NdefMessage)
                }
                buildTagViews(msgs.toTypedArray())
            }
            val uid = myTag!!.id.joinToString("") { String.format("%02X", it) }
            tagId = "TAG ID: $uid"
        }
    }

    private fun buildTagViews(msgs: Array<NdefMessage>) {
        if (msgs.isEmpty()) return
        val texts = mutableListOf<String>()
        for (msg in msgs) {
            for (record in msg.records) {
                val payload = record.payload
                val textEncoding: Charset = if ((payload[0] and 128.toByte()).toInt() == 0) Charsets.UTF_8 else Charsets.UTF_16
                val languageCodeLength: Int = (payload[0] and 51).toInt()
                try {
                    val text = String(
                        payload,
                        languageCodeLength + 1,
                        payload.size - languageCodeLength - 1,
                        textEncoding
                    )
                    texts.add(text)
                } catch (e: UnsupportedEncodingException) {
                    Log.e("UnsupportedEncoding", e.toString())
                }
            }
        }
        nfcContent = texts.joinToString(separator = "\n")
    }

    /******************************************************************************
     * Write to NFC Tag
     ****************************************************************************/
    @Throws(IOException::class, FormatException::class)
    private fun write(messages: List<String>, tag: Tag?) {
        val records = messages.map { createRecord(it) }.toTypedArray()
        val message = NdefMessage(records)
        // Get an instance of Ndef for the tag.
        val ndef = Ndef.get(tag)
        // Enable I/O
        ndef.connect()
        // Write the message
        if(ndef.isConnected){
            ndef.writeNdefMessage(message)
            // Close the connection
            ndef.close()
        }else{
            throw IOException("NFC tag is not connected.")
        }

    }

    @Throws(UnsupportedEncodingException::class)
    private fun createRecord(text: String): NdefRecord {
        val lang = "en"
        val textBytes = text.toByteArray()
        val langBytes = lang.toByteArray(charset("US-ASCII"))
        val langLength = langBytes.size
        val textLength = textBytes.size
        val payload = ByteArray(1 + langLength + textLength)

        // set status byte (see NDEF spec for actual bits)
        payload[0] = langLength.toByte()

        // copy langbytes and textbytes into payload
        System.arraycopy(langBytes, 0, payload, 1, langLength)
        System.arraycopy(textBytes, 0, payload, 1 + langLength, textLength)
        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }

    /**
     * For reading the NFC when the app is already launched
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("TAG", "NFC TAP WHILE ACTIVE, data: ${NfcAdapter.EXTRA_TAG}")
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            TODO("VERSION.SDK_INT < TIRAMISU")
        }
        if (tag != null) {
            Log.d("TAG", "TAG IS NOT NULL")
        }
        setIntent(intent)
        readFromIntent(intent)
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            myTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            }else{
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
        }
    }

    public override fun onPause() {
        super.onPause()
        WriteModeOff()
    }

    public override fun onResume() {
        super.onResume()
        WriteModeOn()
    }

    /******************************************************************************
     * Enable Write and foreground dispatch to prevent intent-filter to launch the app again
     ****************************************************************************/
    private fun WriteModeOn() {
        writeMode = true
        nfcAdapter!!.enableForegroundDispatch(this, pendingIntent, writeTagFilters, null)
    }

    /******************************************************************************
     * Disable Write and foreground dispatch to allow intent-filter to launch the app
     ****************************************************************************/
    private fun WriteModeOff() {
        writeMode = false
        nfcAdapter!!.disableForegroundDispatch(this)
    }

    private fun onWriteButtonClicked(messages: List<String>) {
        try {
            if (myTag == null) {
                Toast.makeText(this, ERROR_DETECTED, Toast.LENGTH_LONG).show()
            } else {
                write(messages, myTag)
                Toast.makeText(this, WRITE_SUCCESS, Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            Toast.makeText(this, WRITE_ERROR, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } catch (e: FormatException) {
            Toast.makeText(this, WRITE_ERROR, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Security Exception: " + e.message, Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    companion object {
        const val ERROR_DETECTED = "No NFC tag detected!"
        const val WRITE_SUCCESS = "Text written to the NFC tag successfully!"
        const val WRITE_ERROR = "Error during writing, is the NFC tag close enough to your device?"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityScreen(
    messages: List<String>,
    onMessageChange: (Int, String) -> Unit,
    onAddMessageClicked: () -> Unit,
    onRemoveMessageClicked: (Int) -> Unit,
    onWriteClicked: () -> Unit,
    nfcContent: String,
    tagId: String
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Write messages:")

            Spacer(modifier = Modifier.height(16.dp))

            messages.forEachIndexed { index, message ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextField(
                        value = message,
                        onValueChange = { onMessageChange(index, it) },
                        label = { Text("Message ${index + 1}") },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onRemoveMessageClicked(index) }
                    ) {
                        Text("Remove")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Button(
                onClick = onAddMessageClicked
            ) {
                Text("Add Message")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onWriteClicked
            ) {
                Text("Write")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = tagId,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "Message read from NFC Tag:\n$nfcContent",
                modifier = Modifier.fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewMainActivityScreen() {
    NFCReader2Theme {
        MainActivityScreen(
            messages = listOf(""),
            onMessageChange = { _, _ -> },
            onAddMessageClicked = {},
            onRemoveMessageClicked = {},
            onWriteClicked = {},
            nfcContent = "",
            tagId = ""
        )
    }
}
