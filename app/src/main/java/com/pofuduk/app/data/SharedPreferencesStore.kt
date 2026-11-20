package com.pofuduk.app.data

import android.content.Context
import androidx.core.content.edit
import com.pofuduk.core.store.KeyValueStore

/** On-device preferences (never backed up — see data_extraction_rules.xml). */
class SharedPreferencesStore(context: Context, name: String = "pofuduk") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
}
