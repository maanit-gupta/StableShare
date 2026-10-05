package com.maanit.stableshare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Demo mode's own DataStore: which transfers a demo started (the "Demo" chip) and whether the
 * Big upload tip was dismissed. Kept out of Room so the transfers schema stays as it is.
 */
class DemoStore(private val store: DataStore<Preferences>) {

    val demoIds: Flow<Set<String>> = store.data.map { it[KEY_DEMO_IDS].orEmpty() }.distinctUntilChanged()

    val tipDismissed: Flow<Boolean> = store.data.map { it[KEY_TIP_DISMISSED] ?: false }.distinctUntilChanged()

    suspend fun tag(id: String) {
        store.edit { it[KEY_DEMO_IDS] = it[KEY_DEMO_IDS].orEmpty() + id }
    }

    /** Forgets [ids] (their transfers were deleted). */
    suspend fun untag(ids: Collection<String>) {
        if (ids.isEmpty()) return
        store.edit { it[KEY_DEMO_IDS] = it[KEY_DEMO_IDS].orEmpty() - ids.toSet() }
    }

    suspend fun dismissTip() {
        store.edit { it[KEY_TIP_DISMISSED] = true }
    }

    private companion object {
        val KEY_DEMO_IDS = stringSetPreferencesKey("demo_transfer_ids")
        val KEY_TIP_DISMISSED = booleanPreferencesKey("demo_big_upload_tip_dismissed")
    }
}
