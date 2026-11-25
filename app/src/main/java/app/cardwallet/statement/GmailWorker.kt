package app.cardwallet.statement

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.cardwallet.MainActivity
import app.cardwallet.WalletDb
import app.cardwallet.log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Daily Gmail sync in the background. Gets a read-only token without asking; if Google wants a sign-in, it says so in a notification. */
class GmailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val dao = WalletDb.get(applicationContext).dao()
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GmailSync.SCOPE))).build()
        val auth = runCatching { Tasks.await(Identity.getAuthorizationClient(applicationContext).authorize(request), 60, TimeUnit.SECONDS) }
            .getOrElse { dao.log("Daily Gmail sync: couldn't reach Google ($it)"); return Result.retry() }
        val token = auth.accessToken
        if (auth.hasResolution() || token == null) {
            dao.log("Daily Gmail sync: Google wants you to sign in again")
            notifySignIn(applicationContext)
            return Result.success()
        }
        return runCatching { GmailSync.sync(applicationContext, dao, token, LockedPdfs.loadProfile(applicationContext)) }
            .fold({ Result.success() }, { dao.log("Daily Gmail sync failed: $it"); Result.retry() })
    }

    companion object {
        private const val WORK = "gmail_daily_sync"
        private const val CHANNEL = "gmail"

        /** Runs once a day when there's internet. Safe to call often: an existing schedule is kept. */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<GmailWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
        }

        private fun notifySignIn(context: Context) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Gmail sync", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Sign in to keep Gmail sync on")
                .setContentText("Google wants you to sign in again. Open Card Wallet → More → Sync now.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1, note)
        }
    }
}
