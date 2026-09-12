package su.nepom.budget.banknotifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class BankNotificationListenerService : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        NotificationRepository.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
        if (title == null && text == null) return
        NotificationRepository.append(sbn.packageName, title, text)
    }
}
