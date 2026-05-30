package cn.liukebin.GostX

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {
    val store = mutableMapOf<String, Any?>()

    override fun contains(key: String) = store.containsKey(key)
    override fun getAll(): MutableMap<String, *> = store
    override fun getString(key: String?, defValue: String?) = store[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = store[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String?, defValue: Int) = store[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = store[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = store[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = store[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = Editor()

    inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearPending = false

        override fun putString(key: String?, value: String?) = this.also { if (key != null) pending[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = this.also { if (key != null) pending[key] = value }
        override fun putInt(key: String?, value: Int) = this.also { if (key != null) pending[key] = value }
        override fun putLong(key: String?, value: Long) = this.also { if (key != null) pending[key] = value }
        override fun putFloat(key: String?, value: Float) = this.also { if (key != null) pending[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this.also { if (key != null) pending[key] = values }
        override fun remove(key: String?) = this.also { if (key != null) removals.add(key) }
        override fun clear() = this.also { clearPending = true }
        override fun commit(): Boolean { flush(); return true }
        override fun apply() { flush() }

        private fun flush() {
            if (clearPending) {
                store.clear()
            }
            removals.forEach { store.remove(it) }
            store.putAll(pending)
        }
    }
}
