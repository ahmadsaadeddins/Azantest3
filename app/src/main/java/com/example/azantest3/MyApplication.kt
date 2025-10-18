// Create a new Kotlin file, e.g., MyApplication.kt
package com.example.azantest3 // Your app's package

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Application onCreate: Scheduling daily rescheduler.")
        // Launch a coroutine to call the suspend setup function
        CoroutineScope(Dispatchers.Default).launch {
            try {
                DailySchedulerSetup().setupDailyRescheduler(applicationContext, forceSetup = false)
            } catch (e: Exception) {
                Log.e("MyApplication", "Failed to setup daily rescheduler", e)
            }
        }
    }
}
