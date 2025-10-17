package com.example.azantest3

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Debug helper class for troubleshooting Azan scheduling issues.
 * Provides comprehensive diagnostics and monitoring capabilities.
 */
class AzanDebugHelper(private val context: Context) {

    companion object {
        private const val TAG = "AzanDebugHelper"
        private val debugFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault())
    }

    /**
     * Performs comprehensive diagnostics of the Azan system
     */
    suspend fun performComprehensiveDiagnostics(): Map<String, Any> {
        Log.i(TAG, "🔍 Starting comprehensive Azan diagnostics...")
        
        val diagnostics = mutableMapOf<String, Any>()
        
        try {
            // System information
            diagnostics["systemInfo"] = getSystemInfo()
            
            // Settings diagnostics
            diagnostics["settings"] = getSettingsDiagnostics()
            
            // Alarm manager diagnostics
            diagnostics["alarmManager"] = getAlarmManagerDiagnostics()
            
            // Storage diagnostics
            diagnostics["storage"] = getStorageDiagnostics()
            
            // Work manager diagnostics
            diagnostics["workManager"] = getWorkManagerDiagnostics()
            
            // Receiver diagnostics
            diagnostics["receivers"] = getReceiverDiagnostics()
            
            // Prayer times diagnostics
            diagnostics["prayerTimes"] = getPrayerTimesDiagnostics()
            
            Log.i(TAG, "✅ Diagnostics completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during diagnostics", e)
            diagnostics["error"] = e.message ?: "Unknown error"
        }
        
        return diagnostics
    }

    /**
     * Gets system information
     */
    private fun getSystemInfo(): Map<String, Any> {
        return try {
            mapOf(
                "currentTime" to debugFormatter.format(Date()),
                "timezone" to TimeZone.getDefault().id,
                "timezoneName" to TimeZone.getDefault().displayName,
                "utcOffset" to TimeZone.getDefault().rawOffset / (1000 * 60 * 60),
                "dstOffset" to TimeZone.getDefault().dstSavings / (1000 * 60 * 60),
                "inDST" to TimeZone.getDefault().inDaylightTime(Date()),
                "androidVersion" to Build.VERSION.SDK_INT,
                "androidRelease" to Build.VERSION.RELEASE,
                "deviceManufacturer" to Build.MANUFACTURER,
                "deviceModel" to Build.MODEL
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets settings diagnostics
     */
    private suspend fun getSettingsDiagnostics(): Map<String, Any> {
        return try {
            val settingsDataStore = SettingsDataStore(context)
            
            mapOf(
                "azanEnabled" to settingsDataStore.azanEnabledFlow.first(),
                "addHourOffset" to settingsDataStore.addHourOffsetFlow.first(),
                "iqamaSettings" to settingsDataStore.iqamaSettingsFlow.first().toString(),
                "azanSettingsBlocking" to AzanSettings.isAzanEnabledBlocking(context)
            )
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets alarm manager diagnostics
     */
    private fun getAlarmManagerDiagnostics(): Map<String, Any> {
        return try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val diagnostics = mutableMapOf<String, Any>(
                "canScheduleExactAlarms" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    alarmManager.canScheduleExactAlarms()
                } else {
                    "Not applicable (API < 31)"
                }
            )
            
            // Check if we can create pending intents for known prayers
            val testPrayers = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            val pendingIntentStatus = mutableMapOf<String, String>()
            
            testPrayers.forEach { prayer ->
                try {
                    val intent = Intent(context, AzanAlarmReceiver::class.java).apply {
                        action = AzanAlarmReceiver.ACTION_PLAY_AZAN
                        putExtra(AzanAlarmReceiver.PRAYER_NAME_EXTRA, prayer)
                    }
                    
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        prayer.hashCode(),
                        intent,
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                    )
                    
                    pendingIntentStatus[prayer] = if (pendingIntent != null) "Active" else "Not scheduled"
                    
                } catch (e: Exception) {
                    pendingIntentStatus[prayer] = "Error: ${e.message}"
                }
            }
            
            diagnostics["pendingIntents"] = pendingIntentStatus
            diagnostics
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets storage diagnostics
     */
    private suspend fun getStorageDiagnostics(): Map<String, Any> {
        return try {
            val stats = AzanAlarmStore.getStorageStats(context)
            val storedPrayers = AzanAlarmStore.getAllStoredPrayerNames(context)
            
            val prayerDetails = mutableMapOf<String, String>()
            storedPrayers.forEach { prayer ->
                val alarmInfo = AzanAlarmStore.getAlarmInfo(context, prayer)
                if (alarmInfo != null) {
                    prayerDetails[prayer] = "RequestCode: ${alarmInfo.requestCode}, Time: ${Date(alarmInfo.prayerTime)}"
                } else {
                    prayerDetails[prayer] = "No alarm info found"
                }
            }
            
            mapOf(
                "statistics" to stats,
                "storedPrayerNames" to storedPrayers.toList(),
                "prayerDetails" to prayerDetails
            )
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets work manager diagnostics
     */
    private suspend fun getWorkManagerDiagnostics(): Map<String, Any> {
        return try {
            val workManager = WorkManager.getInstance(context)
            val schedulerSetup = DailySchedulerSetup()
            
            // Get work by tags
            val dailyWorks = workManager.getWorkInfosByTag("daily_azan").get()
            val immediateWorks = workManager.getWorkInfosByTag("immediate_azan").get()
            val rescheduleWorks = workManager.getWorkInfosByTag("reschedule").get()
            
            val workStatus = schedulerSetup.getWorkStatus(context)
            val nextExecutionTime = schedulerSetup.getNextExecutionTime(context)
            
            mapOf(
                "dailyWorksCount" to dailyWorks.size,
                "immediateWorksCount" to immediateWorks.size,
                "rescheduleWorksCount" to rescheduleWorks.size,
                "dailyWorkStates" to dailyWorks.map { "${it.id}: ${it.state}" },
                "immediateWorkStates" to immediateWorks.map { "${it.id}: ${it.state}" },
                "rescheduleWorkStates" to rescheduleWorks.map { "${it.id}: ${it.state}" },
                "workStatus" to workStatus,
                "nextExecutionTime" to nextExecutionTime
            )
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets receiver diagnostics
     */
    private fun getReceiverDiagnostics(): Map<String, Any> {
        return try {
            val receiverStats = AzanAlarmReceiver.getTrackingStats()
            
            mapOf(
                "receiverTrackingStats" to receiverStats
            )
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Gets prayer times diagnostics
     */
    private suspend fun getPrayerTimesDiagnostics(): Map<String, Any> {
        return try {
            val db = AppDatabase.getDatabase(context)
            val prayerDao = db.prayerTimeDao()
            val allPrayers = prayerDao.getAll()
            
            val currentDate = java.util.Calendar.getInstance()
            val monthFormatter = SimpleDateFormat("MMM", Locale.ENGLISH)
            val currentMonth = monthFormatter.format(currentDate.time)
            val currentDay = currentDate.get(java.util.Calendar.DAY_OF_MONTH)
            
            val todayPrayers = allPrayers.find { 
                it.month_name.equals(currentMonth, ignoreCase = true) && it.day == currentDay 
            }
            
            mapOf(
                "totalPrayerEntries" to allPrayers.size,
                "currentMonth" to currentMonth,
                "currentDay" to currentDay,
                "todayPrayersFound" to (todayPrayers != null),
                "todayPrayerTimes" to if (todayPrayers != null) {
                    mapOf(
                        "fajr" to todayPrayers.fajr,
                        "sunrise" to todayPrayers.sunrise,
                        "dhuhr" to todayPrayers.dhuhr,
                        "asr" to todayPrayers.asr,
                        "maghrib" to todayPrayers.maghrib,
                        "isha" to todayPrayers.isha
                    )
                } else "No prayer times found for today",
                "availableMonths" to allPrayers.map { "${it.month_name}" }.distinct().sorted(),
                "availableDays" to allPrayers.filter { 
                    it.month_name.equals(currentMonth, ignoreCase = true) 
                }.map { it.day }.sorted()
            )
            
        } catch (e: Exception) {
            mapOf("error" to e.message.orEmpty())
        }
    }

    /**
     * Logs comprehensive diagnostics to Logcat
     */
    suspend fun logComprehensiveDiagnostics() {
        val diagnostics = performComprehensiveDiagnostics()
        
        Log.i(TAG, """\n
=================================
    AZAN SYSTEM DIAGNOSTICS
=================================

SYSTEM INFO:
${formatDiagnosticSection(diagnostics["systemInfo"] as? Map<String, Any>)}

SETTINGS:
${formatDiagnosticSection(diagnostics["settings"] as? Map<String, Any>)}

ALARM MANAGER:
${formatDiagnosticSection(diagnostics["alarmManager"] as? Map<String, Any>)}

STORAGE:
${formatDiagnosticSection(diagnostics["storage"] as? Map<String, Any>)}

WORK MANAGER:
${formatDiagnosticSection(diagnostics["workManager"] as? Map<String, Any>)}

RECEIVERS:
${formatDiagnosticSection(diagnostics["receivers"] as? Map<String, Any>)}

PRAYER TIMES:
${formatDiagnosticSection(diagnostics["prayerTimes"] as? Map<String, Any>)}

=================================
""")
    }

    /**
     * Formats a diagnostic section for logging
     */
    private fun formatDiagnosticSection(section: Map<String, Any>?): String {
        if (section == null) return "  No data available\n"
        
        return section.entries.joinToString("\n") { (key, value) ->
            "  $key: $value"
        } + "\n"
    }

    /**
     * Cleanup old receiver tracking data
     */
    fun performCleanup() {
        try {
            AzanAlarmReceiver.cleanupOldEntries()
            Log.d(TAG, "Performed cleanup of old tracking data")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to perform cleanup", e)
        }
    }

    /**
     * Tests alarm scheduling capability
     */
    suspend fun testAlarmScheduling(): Map<String, String> {
        val results = mutableMapOf<String, String>()
        
        try {
            val azanScheduler = AzanScheduler(context)
            val testTime = Date(System.currentTimeMillis() + 60000) // 1 minute in future
            
            // Test if scheduler is healthy
            val isHealthy = azanScheduler.validateSchedulerHealth()
            results["schedulerHealth"] = if (isHealthy) "Healthy" else "Unhealthy"
            
            // Test alarm storage
            AzanAlarmStore.saveAzan(context, "TestPrayer", testTime.time, 99999)
            val retrievedCode = AzanAlarmStore.getRequestCode(context, "TestPrayer")
            results["storageTest"] = if (retrievedCode == 99999) "Success" else "Failed"
            
            // Clean up test data
            AzanAlarmStore.clearAzan(context, "TestPrayer")
            
        } catch (e: Exception) {
            results["error"] = e.message ?: "Unknown error"
        }
        
        return results
    }
}