package com.telnyx.webrtc.sdk.manager

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppDataStore(private val context: Context) {
    val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dataStore")

    val CALL_END_STATUS = booleanPreferencesKey("call_end_status")
    val callEndFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            // No type safety.
            preferences[CALL_END_STATUS] ?: false
        }

    suspend fun changeEndCallStatus(value:Boolean) {
        context.dataStore.edit { settings ->
            settings[CALL_END_STATUS] = value
        }
    }
}