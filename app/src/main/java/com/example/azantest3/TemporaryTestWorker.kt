// TemporaryTestWorker.kt
package com.example.azantest3

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TemporaryTestWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TemporaryTestWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "TEMPORARY TEST WORKER IS RUNNING SUCCESSFULLY!") // <<< Can this log appear?
        println("TemporaryTestWorker: Standard output log.") // Also try this
        System.out.println("TemporaryTestWorker: System.out log.") // And this
        return Result.success()
    }
}