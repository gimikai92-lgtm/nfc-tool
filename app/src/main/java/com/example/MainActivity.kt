package com.example

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import java.nio.charset.Charset

data class NfcTagData(
    val id: String,
    val techList: List<String>,
    val records: List<NfcRecordData>
)

data class NfcRecordData(
    val type: String,
    val payload: String
)

enum class NfcStatus {
    NOT_SUPPORTED,
    DISABLED,
    WAITING,
    READING,
    ERROR
}

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    
    private var nfcStatus by mutableStateOf(NfcStatus.WAITING)
    private var nfcTagData by mutableStateOf<NfcTagData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        
        if (nfcAdapter == null) {
            nfcStatus = NfcStatus.NOT_SUPPORTED
        } else if (nfcAdapter?.isEnabled == false) {
            nfcStatus = NfcStatus.DISABLED
        }
        
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)

        setContent {
            MyApplicationTheme {
                NfcApp(status = nfcStatus, tagData = nfcTagData)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter != null) {
            if (nfcAdapter?.isEnabled == false) {
                nfcStatus = NfcStatus.DISABLED
            } else {
                nfcStatus = NfcStatus.WAITING
                
                val ndef = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                    try {
                        addDataType("*/*")
                    } catch (e: IntentFilter.MalformedMimeTypeException) {
                        throw RuntimeException("fail", e)
                    }
                }
                
                val intentFiltersArray = arrayOf(ndef, IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED), IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))
                val techListsArray = arrayOf(arrayOf(Ndef::class.java.name))
                
                nfcAdapter?.enableForegroundDispatch(this, pendingIntent, intentFiltersArray, techListsArray)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (nfcAdapter != null) {
            nfcAdapter?.disableForegroundDispatch(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED == action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == action ||
            NfcAdapter.ACTION_TAG_DISCOVERED == action) {
            
            nfcStatus = NfcStatus.READING
            
            val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            
            tag?.let {
                val tagId = it.id.joinToString(":") { byte -> "%02X".format(byte) }
                val techList = it.techList.map { tech -> tech.substringAfterLast('.') }
                
                val parsedRecords = mutableListOf<NfcRecordData>()
                
                val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                }
                
                if (rawMsgs != null) {
                    for (rawMsg in rawMsgs) {
                        val msg = rawMsg as NdefMessage
                        for (record in msg.records) {
                            parsedRecords.add(parseRecord(record))
                        }
                    }
                } else {
                    val ndef = Ndef.get(tag)
                    if (ndef != null) {
                        parsedRecords.add(NfcRecordData("Info", "NDEF formatted tag, but no messages. Max size: ${ndef.maxSize} bytes"))
                    } else {
                        parsedRecords.add(NfcRecordData("Info", "Tag is not NDEF formatted."))
                    }
                }
                
                nfcTagData = NfcTagData(tagId, techList, parsedRecords)
                nfcStatus = NfcStatus.WAITING
            } ?: run {
                nfcStatus = NfcStatus.ERROR
            }
        }
    }
    
    private fun parseRecord(record: NdefRecord): NfcRecordData {
        val payload = record.payload
        val type = String(record.type, Charsets.UTF_8)
        
        return when (record.tnf) {
            NdefRecord.TNF_WELL_KNOWN -> {
                if (type == "T") {
                    if (payload.isNotEmpty()) {
                        val languageCodeLength = (payload[0].toInt() and 0x3F)
                        val text = String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, Charset.forName(if ((payload[0].toInt() and 0x80) == 0) "UTF-8" else "UTF-16"))
                        NfcRecordData("Text", text)
                    } else {
                         NfcRecordData("Text", "Empty")
                    }
                } else if (type == "U") {
                    if (payload.isNotEmpty()) {
                        val prefix = when(payload[0].toInt()) {
                            0x01 -> "http://www."
                            0x02 -> "https://www."
                            0x03 -> "http://"
                            0x04 -> "https://"
                            0x05 -> "tel:"
                            0x06 -> "mailto:"
                            else -> ""
                        }
                        val uri = String(payload, 1, payload.size - 1, Charsets.UTF_8)
                        NfcRecordData("URI", "$prefix$uri")
                    } else {
                        NfcRecordData("URI", "Empty")
                    }
                } else {
                    NfcRecordData("Well Known ($type)", String(payload, Charsets.UTF_8))
                }
            }
            NdefRecord.TNF_MIME_MEDIA -> {
                NfcRecordData("MIME Media ($type)", String(payload, Charsets.UTF_8))
            }
            else -> {
                 NfcRecordData("Other (TNF: ${record.tnf})", "Payload size: ${payload.size} bytes")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcApp(status: NfcStatus, tagData: NfcTagData?) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC Tools") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(status)
            
            if (tagData != null) {
                TagDataCard(tagData)
            }
        }
    }
}

@Composable
fun StatusCard(status: NfcStatus) {
    val (icon, text, color) = when (status) {
        NfcStatus.NOT_SUPPORTED -> Triple(Icons.Default.Warning, "NFC is not supported on this device.", MaterialTheme.colorScheme.error)
        NfcStatus.DISABLED -> Triple(Icons.Default.Warning, "NFC is disabled. Please enable it in Settings.", MaterialTheme.colorScheme.error)
        NfcStatus.WAITING -> Triple(Icons.Default.Nfc, "Ready to scan. Tap an NFC tag against the back of your device.", MaterialTheme.colorScheme.primary)
        NfcStatus.READING -> Triple(Icons.Default.Nfc, "Reading tag...", MaterialTheme.colorScheme.secondary)
        NfcStatus.ERROR -> Triple(Icons.Default.Warning, "Error reading tag. Please try again.", MaterialTheme.colorScheme.error)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = color
            )
        }
    }
}

@Composable
fun TagDataCard(data: NfcTagData) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Tag Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            HorizontalDivider()
            
            InfoRow("Tag ID", data.id)
            InfoRow("Technologies", data.techList.joinToString(", "))
            
            if (data.records.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "NDEF Records",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                HorizontalDivider()
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(data.records) { record ->
                        RecordItem(record)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun RecordItem(record: NfcRecordData) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = record.type,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.payload,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
