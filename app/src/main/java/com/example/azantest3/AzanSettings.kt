package com.example.azantest3

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

object AzanSettings {

    /**
     * Reads azan enabled status synchronously using runBlocking.
     * Safe to use inside BroadcastReceivers and Services.
     */
    fun isAzanEnabledBlocking(context: Context): Boolean = runBlocking {
        context.dataStore.data
            .map { prefs -> prefs[SettingsDataStore.AZAN_ENABLED_KEY] ?: true }
            .first()
    }
}
