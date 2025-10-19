// Create a new Kotlin file, e.g., MyApplication.kt
package com.example.azantest3 // Your app's package

import android.app.Application
import android.util.Log



class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Application onCreate: Scheduling daily rescheduler.")
        // Call your function here
        scheduleDailyRescheduler(applicationContext)
    }
}