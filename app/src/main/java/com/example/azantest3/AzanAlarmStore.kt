package com.example.azantest3

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe storage for Azan alarm information with improved error handling
 * and data validation to prevent duplicate alarms and timing issues.
 */
object AzanAlarmStore {
    private const val PREF_NAME = "azan_alarm_prefs_v2" // Changed version to reset corrupt data
    private const val TAG = "AzanAlarmStore"
    
    // Mutex for thread-safe operations
    private val storageMutex = Mutex()
    
    // Cache to reduce SharedPreferences access
    private val cache = ConcurrentHashMap<String, AlarmInfo>()
    private var cacheInitialized = false
    
    // Data class for alarm information
    data class AlarmInfo(
        val prayerTime: Long,
        val requestCode: Int,
        val scheduledAt: Long = System.currentTimeMillis()
    ) {
        fun isValid(): Boolean {
            return prayerTime > 0 && 
                   requestCode > 0 && 
                   scheduledAt > 0 && 
                   prayerTime > scheduledAt // Prayer time should be in future when scheduled
        }
    }

    /**
     * Saves Azan alarm information with validation and thread safety
     */
    suspend fun saveAzan(
        context: Context, 
        prayerName: String, 
        prayerTime: Long, 
        requestCode: Int
    ) = storageMutex.withLock {
        
        if (prayerName.isBlank()) {
            Log.e(TAG, "Cannot save Azan: prayer name is blank")
            return@withLock
        }
        
        if (prayerTime <= 0) {
            Log.e(TAG, "Cannot save Azan for $prayerName: invalid prayer time $prayerTime")
            return@withLock
        }
        
        if (requestCode <= 0) {
            Log.e(TAG, "Cannot save Azan for $prayerName: invalid request code $requestCode")
            return@withLock
        }

        try {
            val prefs = getSharedPreferences(context)
            val alarmInfo = AlarmInfo(prayerTime, requestCode)
            
            if (!alarmInfo.isValid()) {
                Log.e(TAG, "Invalid alarm info for $prayerName: $alarmInfo")
                return@withLock
            }

            prefs.edit {
                putLong(getPrayerTimeKey(prayerName), prayerTime)
                putInt(getPrayerCodeKey(prayerName), requestCode)
                putLong(getPrayerScheduledKey(prayerName), alarmInfo.scheduledAt)
                putInt("version", 2) // Version tracking for future migrations
            }
            
            // Update cache
            cache[prayerName] = alarmInfo
            
            Log.d(TAG, "✅ Saved Azan info for $prayerName: requestCode=$requestCode, time=${java.util.Date(prayerTime)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to save Azan for $prayerName", e)
        }
    }

    /**
     * Gets the request code for a specific prayer with validation
     */
    suspend fun getRequestCode(context: Context, prayerName: String): Int? = storageMutex.withLock {
        
        if (prayerName.isBlank()) {
            Log.w(TAG, "Cannot get request code: prayer name is blank")
            return@withLock null
        }

        try {
            // Check cache first
            if (cacheInitialized && cache.containsKey(prayerName)) {
                val alarmInfo = cache[prayerName]
                if (alarmInfo?.isValid() == true) {
                    return@withLock alarmInfo.requestCode
                }
            }

            // Fallback to SharedPreferences
            val prefs = getSharedPreferences(context)
            val codeKey = getPrayerCodeKey(prayerName)
            
            return@withLock if (prefs.contains(codeKey)) {
                val requestCode = prefs.getInt(codeKey, -1)
                if (requestCode > 0) {
                    Log.d(TAG, "Found request code $requestCode for $prayerName")
                    requestCode
                } else {
                    Log.w(TAG, "Invalid request code for $prayerName: $requestCode")
                    null
                }
            } else {
                Log.d(TAG, "No request code found for $prayerName")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get request code for $prayerName", e)
            null
        }
    }

    /**
     * Gets complete alarm information for a prayer
     */
    suspend fun getAlarmInfo(context: Context, prayerName: String): AlarmInfo? = storageMutex.withLock {
        
        if (prayerName.isBlank()) return@withLock null

        try {
            // Check cache first
            if (cacheInitialized && cache.containsKey(prayerName)) {
                val alarmInfo = cache[prayerName]
                if (alarmInfo?.isValid() == true) {
                    return@withLock alarmInfo
                }
            }

            // Load from SharedPreferences
            val prefs = getSharedPreferences(context)
            val timeKey = getPrayerTimeKey(prayerName)
            val codeKey = getPrayerCodeKey(prayerName)
            val scheduledKey = getPrayerScheduledKey(prayerName)

            if (prefs.contains(timeKey) && prefs.contains(codeKey)) {
                val prayerTime = prefs.getLong(timeKey, -1L)
                val requestCode = prefs.getInt(codeKey, -1)
                val scheduledAt = prefs.getLong(scheduledKey, System.currentTimeMillis())
                
                if (prayerTime > 0 && requestCode > 0) {
                    val alarmInfo = AlarmInfo(prayerTime, requestCode, scheduledAt)
                    
                    if (alarmInfo.isValid()) {
                        cache[prayerName] = alarmInfo
                        return@withLock alarmInfo
                    } else {
                        Log.w(TAG, "Invalid stored alarm info for $prayerName: $alarmInfo")
                    }
                }
            }
            
            return@withLock null
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get alarm info for $prayerName", e)
            null
        }
    }

    /**
     * Clears Azan alarm information with validation
     */
    suspend fun clearAzan(context: Context, prayerName: String) = storageMutex.withLock {
        
        if (prayerName.isBlank()) {
            Log.w(TAG, "Cannot clear Azan: prayer name is blank")
            return@withLock
        }

        try {
            val prefs = getSharedPreferences(context)
            
            prefs.edit {
                remove(getPrayerTimeKey(prayerName))
                remove(getPrayerCodeKey(prayerName))
                remove(getPrayerScheduledKey(prayerName))
            }
            
            // Remove from cache
            cache.remove(prayerName)
            
            Log.d(TAG, "🗑️ Cleared Azan info for $prayerName")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear Azan for $prayerName", e)
        }
    }

    /**
     * Gets all stored prayer names with validation
     */
    suspend fun getAllStoredPrayerNames(context: Context): Set<String> = storageMutex.withLock {
        
        try {
            initializeCacheIfNeeded(context)
            
            val prefs = getSharedPreferences(context)
            val prayerNames = prefs.all.keys
                .filter { it.startsWith("prayer_") && it.endsWith("_code") }
                .map { it.removePrefix("prayer_").removeSuffix("_code") }
                .filter { it.isNotBlank() }
                .toSet()
                
            Log.d(TAG, "Found ${prayerNames.size} stored prayer names: ${prayerNames.joinToString()}")
            
            // Validate each stored prayer and remove invalid ones
            val validPrayerNames = mutableSetOf<String>()
            prayerNames.forEach { prayerName ->
                val alarmInfo = getAlarmInfoFromPrefs(prefs, prayerName)
                if (alarmInfo?.isValid() == true) {
                    validPrayerNames.add(prayerName)
                    cache[prayerName] = alarmInfo
                } else {
                    Log.w(TAG, "Removing invalid alarm info for $prayerName")
                    clearAzanFromPrefs(prefs, prayerName)
                }
            }
            
            return@withLock validPrayerNames
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get stored prayer names", e)
            emptySet()
        }
    }

    /**
     * Clears all stored Azan information (for debugging/cleanup)
     */
    suspend fun clearAllAzans(context: Context) = storageMutex.withLock {
        
        try {
            val prefs = getSharedPreferences(context)
            val allKeys = prefs.all.keys.filter { 
                it.startsWith("prayer_") || it == "version" 
            }
            
            prefs.edit {
                allKeys.forEach { key -> remove(key) }
            }
            
            cache.clear()
            cacheInitialized = false
            
            Log.i(TAG, "🗑️ Cleared all Azan information (${allKeys.size} keys)")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to clear all Azans", e)
        }
    }

    /**
     * Gets statistics about stored alarms
     */
    suspend fun getStorageStats(context: Context): Map<String, Any> = storageMutex.withLock {
        
        try {
            val prefs = getSharedPreferences(context)
            val allKeys = prefs.all.keys
            val prayerKeys = allKeys.filter { it.startsWith("prayer_") }
            val validAlarms = getAllStoredPrayerNames(context)
            
            return@withLock mapOf(
                "totalKeys" to allKeys.size,
                "prayerKeys" to prayerKeys.size,
                "validAlarms" to validAlarms.size,
                "cacheSize" to cache.size,
                "version" to prefs.getInt("version", 1)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get storage stats", e)
            emptyMap()
        }
    }

    // Private helper methods
    
    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    private fun getPrayerTimeKey(prayerName: String) = "prayer_${prayerName}_time"
    private fun getPrayerCodeKey(prayerName: String) = "prayer_${prayerName}_code"
    private fun getPrayerScheduledKey(prayerName: String) = "prayer_${prayerName}_scheduled"
    
    private fun initializeCacheIfNeeded(context: Context) {
        if (!cacheInitialized) {
            cache.clear()
            cacheInitialized = true
        }
    }
    
    private fun getAlarmInfoFromPrefs(prefs: SharedPreferences, prayerName: String): AlarmInfo? {
        return try {
            val timeKey = getPrayerTimeKey(prayerName)
            val codeKey = getPrayerCodeKey(prayerName)
            val scheduledKey = getPrayerScheduledKey(prayerName)
            
            if (prefs.contains(timeKey) && prefs.contains(codeKey)) {
                val prayerTime = prefs.getLong(timeKey, -1L)
                val requestCode = prefs.getInt(codeKey, -1)
                val scheduledAt = prefs.getLong(scheduledKey, System.currentTimeMillis())
                
                if (prayerTime > 0 && requestCode > 0) {
                    AlarmInfo(prayerTime, requestCode, scheduledAt)
                } else null
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get alarm info from prefs for $prayerName", e)
            null
        }
    }
    
    private fun clearAzanFromPrefs(prefs: SharedPreferences, prayerName: String) {
        prefs.edit {
            remove(getPrayerTimeKey(prayerName))
            remove(getPrayerCodeKey(prayerName))
            remove(getPrayerScheduledKey(prayerName))
        }
    }
}