package com.example.azantest3

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// ✅ Shared extension for global access
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
