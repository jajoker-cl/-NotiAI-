package com.donotnotify.donotnotify.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.donotnotify.donotnotify.R
import com.donotnotify.donotnotify.SimpleNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryNotificationDetailsDialog(
    notification: SimpleNotification,
    isActionAvailable: Boolean,
    onDismiss: () -> Unit,
    onTriggerAction: () -> Unit,
    onCreateRule: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val scrollState = rememberScrollState()

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    stringResource(R.string.notification_details),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                DetailRow(stringResource(R.string.label_app), notification.appLabel ?: notification.packageName.orEmpty())
                DetailRow(stringResource(R.string.label_title), notification.title.orEmpty())
                DetailRow(stringResource(R.string.label_text), notification.text.orEmpty())
                DetailRow(stringResource(R.string.label_time), dateFormat.format(Date(notification.timestamp)))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = onTriggerAction,
                        enabled = isActionAvailable,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(stringResource(R.string.open))
                    }
                    Button(
                        onClick = onCreateRule,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(stringResource(R.string.create_rule))
                    }
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(label: String, value: String) {
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.toast_copied_to_clipboard)
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(0.25f))
        Text(
            text = value,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(label, value)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                    }
                )
        )
    }
}
