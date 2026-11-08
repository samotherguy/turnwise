package com.rollingrecorder

import android.content.Context

/** User settings, persisted so they survive process death and reboots. */
class Settings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val notes = context.applicationContext
        .getSharedPreferences("turnwise_notes", Context.MODE_PRIVATE)

    /** Length of the rolling window in hours (UI offers 1, 2, 4, 6 and 10). */
    var windowHours: Int
        get() = prefs.getInt("window_hours", 2)
        set(value) {
            prefs.edit().putInt("window_hours", value).apply()
        }

    /**
     * What the user wants: true after Start, false after Stop.
     * The service only ever runs (or comes back after being killed) while this is true.
     */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean("enabled", value).commit()
        }

    /** Base address of the Turnwise server, e.g. https://host.tailnet.ts.net:8446 */
    var turnwiseUrl: String
        get() = prefs.getString("turnwise_url", DEFAULT_TURNWISE_URL) ?: DEFAULT_TURNWISE_URL
        set(value) {
            prefs.edit().putString("turnwise_url", value).apply()
        }

    /** The waker phone's HTTPS address, e.g. https://waker.tailnet.ts.net:8491 */
    var wakerUrl: String
        get() = prefs.getString("waker_url", DEFAULT_WAKER_URL) ?: DEFAULT_WAKER_URL
        set(value) {
            prefs.edit().putString("waker_url", value).apply()
        }

    /** Where Listen records: "phone" (this device's mic) or "cardputer" (the Cardputer on the network). */
    /** "box" (today's path) or "phone" (everything on the device, nothing leaves it). */
    var transcribeOn: String
        get() = prefs.getString("transcribe_on", "box") ?: "box"
        set(value) { prefs.edit().putString("transcribe_on", value).apply() }

    /** "auto" (1.7B when memory allows), "light" (always the 0.6B) or "better" (always the 1.7B). */
    var asrQuality: String
        get() = prefs.getString("asr_quality", "auto") ?: "auto"
        set(value) { prefs.edit().putString("asr_quality", value).apply() }

    /** Where model files come from; blank means the box's /models/. */
    var modelsUrl: String
        get() = prefs.getString("models_url", "") ?: ""
        set(value) { prefs.edit().putString("models_url", value.trim().trimEnd('/')).apply() }

    /** Keep recording through calls via the accessibility route (experimental). */
    var callCapture: Boolean
        get() = prefs.getBoolean("call_capture", false)
        set(value) { prefs.edit().putBoolean("call_capture", value).apply() }

    var listenSource: String
        get() = prefs.getString("listen_source", "phone") ?: "phone"
        set(value) { prefs.edit().putString("listen_source", value).apply() }

    /** The Cardputer's base URL, e.g. http://192.168.1.18; blank = find it over mDNS. */
    var cardputerUrl: String
        get() = prefs.getString("cardputer_url", "") ?: ""
        set(value) { prefs.edit().putString("cardputer_url", value.trim().trimEnd('/')).apply() }

    /** Turnwise note id for a saved clip, keyed by its MediaStore id. */
    fun noteId(mediaId: Long): String? = notes.getString(mediaId.toString(), null)
    fun setNoteId(mediaId: Long, noteId: String) {
        notes.edit().putString(mediaId.toString(), noteId).apply()
    }

    val windowMs: Long get() = windowHours * 3_600_000L

    /** Start of a meeting being recorded from the buffer (wall-clock ms), or 0. */
    var recordMark: Long
        get() = prefs.getLong("record_mark", 0L)
        set(value) {
            prefs.edit().putLong("record_mark", value).apply()
        }

    /** Notes list grouping: "date" or "folder". */
    var notesView: String
        get() = prefs.getString("notes_view", "date") ?: "date"
        set(value) {
            prefs.edit().putString("notes_view", value).apply()
        }

    companion object {
        const val DEFAULT_TURNWISE_URL = BuildConfig.TURNWISE_URL   // from local.properties at build time
        const val DEFAULT_WAKER_URL = BuildConfig.WAKER_URL
    }
}
