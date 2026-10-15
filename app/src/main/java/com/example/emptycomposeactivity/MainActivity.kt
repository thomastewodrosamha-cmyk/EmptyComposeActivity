package com.example.emptycomposeactivity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.TimeUnit

enum class SchedulePattern { MWF, TTS }
enum class SessionState { PENDING, DONE, HOLIDAY }

data class ClassSession(
    val sessionNumber: Int,
    var date: LocalDate,
    var state: SessionState = SessionState.PENDING
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ReminderApp()
                }
            }
        }
    }
}

@Composable
fun ReminderApp() {
    var pattern by remember { mutableStateOf(SchedulePattern.MWF) }
    var sessions by remember { mutableStateOf(generateSchedule(LocalDate.now(), pattern)) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("The Maraki Reminder", style = MaterialTheme.typography.headlineMedium)
        Text("By Thom • 24-Session Tracker", style = MaterialTheme.typography.bodyMedium)

        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    pattern = SchedulePattern.MWF
                    sessions = generateSchedule(LocalDate.now(), SchedulePattern.MWF)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pattern == SchedulePattern.MWF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("MWF")
            }
            Button(
                onClick = {
                    pattern = SchedulePattern.TTS
                    sessions = generateSchedule(LocalDate.now(), SchedulePattern.TTS)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (pattern == SchedulePattern.TTS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("TTS")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(sessions) { index, session ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Session ${session.sessionNumber}: ${session.date}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            when (session.sessionNumber) {
                                6 -> Text("Feedback Form 1", color = MaterialTheme.colorScheme.error)
                                12 -> Text("Midterm Exam + Feedback 2", color = MaterialTheme.colorScheme.error)
                                22 -> Text("Feedback Form 3", color = MaterialTheme.colorScheme.error)
                                24 -> Text("Final Exam", color = MaterialTheme.colorScheme.error)
                            }
                            if (session.state == SessionState.HOLIDAY) {
                                Text("Holiday Skipped", style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    val updated = sessions.toMutableList()
                                    updated[index] = session.copy(
                                        state = if (session.state == SessionState.DONE) SessionState.PENDING else SessionState.DONE
                                    )
                                    sessions = updated
                                }
                            ) {
                                Text(if (session.state == SessionState.DONE) "Undo" else "Done")
                            }

                            if (session.state != SessionState.HOLIDAY) {
                                Button(
                                    onClick = {
                                        sessions = shiftHolidays(sessions, index, pattern)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Holiday")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun generateSchedule(start: LocalDate, pattern: SchedulePattern): List<ClassSession> {
    val allowed = when (pattern) {
        SchedulePattern.MWF -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        SchedulePattern.TTS -> setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
    }
    val list = mutableListOf<ClassSession>()
    var cursor = start
    var count = 1
    while (count <= 24) {
        if (allowed.contains(cursor.dayOfWeek)) {
            list.add(ClassSession(count, cursor))
            count++
        }
        cursor = cursor.plusDays(1)
    }
    return list
}

fun shiftHolidays(current: List<ClassSession>, holidayIdx: Int, pattern: SchedulePattern): List<ClassSession> {
    val allowed = when (pattern) {
        SchedulePattern.MWF -> setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        SchedulePattern.TTS -> setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
    }
    val updated = current.toMutableList()
    updated[holidayIdx] = updated[holidayIdx].copy(state = SessionState.HOLIDAY)

    var nextDate = updated[holidayIdx].date.plusDays(1)
    for (i in (holidayIdx + 1) until updated.size) {
        while (!allowed.contains(nextDate.dayOfWeek)) {
            nextDate = nextDate.plusDays(1)
        }
        updated[i] = updated[i].copy(date = nextDate)
        nextDate = nextDate.plusDays(1)
    }
    return updated
}

class ReminderBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("TASK_ID", 1L)
        val title = intent.getStringExtra("TITLE") ?: "Class Ended"
        val message = intent.getStringExtra("MESSAGE") ?: "Check homework & log recap."
        val url = intent.getStringExtra("URL")

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "maraki_core"

        val channel = NotificationChannel(channelId, "Maraki Alerts", NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)

        val doneIntent = Intent(context, TaskDoneReceiver::class.java).apply {
            putExtra("TASK_ID", taskId)
        }
        val donePI = PendingIntent.getBroadcast(
            context, taskId.toInt(), doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.checkbox_on_background, "Mark Done", donePI)

        if (!url.isNullOrEmpty()) {
            val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val linkPI = PendingIntent.getActivity(
                context, (taskId + 500).toInt(), linkIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_view, "Open Form", linkPI)
        }

        manager.notify(taskId.toInt(), builder.build())

        val workData = workDataOf("TASK_ID" to taskId, "TITLE" to title, "MESSAGE" to message, "URL" to url)
        val retry = OneTimeWorkRequestBuilder<ReAlertWorker>()
            .setInitialDelay(3, TimeUnit.HOURS)
            .setInputData(workData)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("WORK_$taskId", ExistingWorkPolicy.REPLACE, retry)
    }
}

class ReAlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("TASK_ID", 1L)
        val title = inputData.getString("TITLE") ?: "Task Overdue"
        val message = inputData.getString("MESSAGE") ?: "Pending completion."
        val url = inputData.getString("URL")

        val intent = Intent(applicationContext, ReminderBroadcastReceiver::class.java).apply {
            putExtra("TASK_ID", taskId)
            putExtra("TITLE", "[OVERDUE] $title")
            putExtra("MESSAGE", message)
            putExtra("URL", url)
        }
        applicationContext.sendBroadcast(intent)
        return Result.success()
    }
}

class TaskDoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra("TASK_ID", 1L)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(taskId.toInt())
        WorkManager.getInstance(context).cancelUniqueWork("WORK_$taskId")
    }
}
