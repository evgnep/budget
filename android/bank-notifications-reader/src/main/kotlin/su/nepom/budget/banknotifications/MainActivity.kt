package su.nepom.budget.banknotifications

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationRepository.init(applicationContext)
        setContent {
            MaterialTheme {
                Surface {
                    NotificationsScreen()
                }
            }
        }
    }
}

@Composable
private fun NotificationsScreen() {
    val text by NotificationRepository.text.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!isNotificationAccessGranted(context)) {
            Text(
                "Доступ к уведомлениям не выдан. Нажмите, чтобы открыть настройки и включить его для этого приложения.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Text("Открыть настройки")
            }
        }

        if (!isIgnoringBatteryOptimizations(context)) {
            Text(
                "Система может останавливать службу для экономии батареи. Разрешите работу без ограничений.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier.padding(bottom = 16.dp),
            ) {
                Text("Отключить оптимизацию батареи")
            }
        }

        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text("Прочитанные уведомления") },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { copyToClipboard(context, text) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Скопировать в буфер")
            }
            OutlinedButton(
                onClick = { NotificationRepository.clear() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Очистить")
            }
        }
    }
}

private fun isNotificationAccessGranted(context: Context): Boolean {
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return enabled?.contains(context.packageName) == true
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Suppress("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Уведомления", text))
}
