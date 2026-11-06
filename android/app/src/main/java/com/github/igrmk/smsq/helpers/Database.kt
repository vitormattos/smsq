package com.github.igrmk.smsq.helpers

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.github.igrmk.smsq.BuildConfig
import com.github.igrmk.smsq.entities.Sms

// Preserve the original on-device database and its pending SMS across updates.
// Use Android's built-in SQLite APIs instead of the discontinued Anko library.
class DbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "database", null, BuildConfig.VERSION_CODE) {

    companion object {
        const val TABLE_SMS = "sms"
        const val COLUMN_SMS_ID = "id"
        const val COLUMN_SMS_TEXT = "text"
        const val COLUMN_SMS_SENDER = "sender"
        const val COLUMN_SMS_CONTACT_NAME = "contact_name"
        const val COLUMN_SMS_CARRIER = "carrier"
        const val COLUMN_SMS_SIM = "sim"
        const val COLUMN_SMS_TIMESTAMP = "timestamp"
        const val COLUMN_SMS_OFFSET = "offset"

        @Volatile
        private var instance: DbHelper? = null

        @Synchronized
        fun getInstance(ctx: Context): DbHelper {
            if (instance == null) {
                instance = DbHelper(ctx.applicationContext)
            }
            return instance!!
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sms (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT, sender TEXT, " +
                "contact_name TEXT NOT NULL DEFAULT '', " +
                "carrier TEXT, sim TEXT, timestamp INTEGER, offset INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 17) {
            db.execSQL("ALTER TABLE sms ADD COLUMN contact_name TEXT NOT NULL DEFAULT ''")
        }
    }
}

val Context.database: DbHelper
    get() = DbHelper.getInstance(applicationContext)

fun Context.storeSms(sms: Sms) {
    val values = ContentValues().apply {
        put(DbHelper.COLUMN_SMS_TEXT, sms.text)
        put(DbHelper.COLUMN_SMS_SENDER, sms.sender)
        put(DbHelper.COLUMN_SMS_CONTACT_NAME, sms.contactName)
        put(DbHelper.COLUMN_SMS_CARRIER, sms.carrier)
        put(DbHelper.COLUMN_SMS_SIM, sms.sim)
        put(DbHelper.COLUMN_SMS_TIMESTAMP, sms.timestamp)
        put(DbHelper.COLUMN_SMS_OFFSET, sms.offset)
    }
    database.writableDatabase.insertOrThrow(DbHelper.TABLE_SMS, null, values)
}

fun Context.allSmses(): List<Sms> {
    val messages = mutableListOf<Sms>()
    val db = database.readableDatabase
    db.query(
        DbHelper.TABLE_SMS,
        arrayOf(
            DbHelper.COLUMN_SMS_ID,
            DbHelper.COLUMN_SMS_TEXT,
            DbHelper.COLUMN_SMS_SENDER,
            DbHelper.COLUMN_SMS_CONTACT_NAME,
            DbHelper.COLUMN_SMS_CARRIER,
            DbHelper.COLUMN_SMS_SIM,
            DbHelper.COLUMN_SMS_TIMESTAMP,
            DbHelper.COLUMN_SMS_OFFSET
        ),
        null, null, null, null, DbHelper.COLUMN_SMS_TIMESTAMP + " ASC"
    ).use { cursor ->
        val id = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_ID)
        val text = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_TEXT)
        val sender = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_SENDER)
        val contactName = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_CONTACT_NAME)
        val carrier = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_CARRIER)
        val sim = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_SIM)
        val timestamp = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_TIMESTAMP)
        val offset = cursor.getColumnIndexOrThrow(DbHelper.COLUMN_SMS_OFFSET)
        while (cursor.moveToNext()) {
            messages.add(Sms().apply {
                this.id = cursor.getInt(id)
                this.text = cursor.getString(text) ?: ""
                this.sender = cursor.getString(sender) ?: ""
                this.contactName = cursor.getString(contactName) ?: ""
                this.carrier = cursor.getString(carrier) ?: ""
                this.sim = cursor.getString(sim) ?: ""
                this.timestamp = cursor.getLong(timestamp)
                this.offset = cursor.getInt(offset)
            })
        }
    }
    return messages
}

fun Context.deleteSms(id: Int) {
    database.writableDatabase.delete(
        DbHelper.TABLE_SMS, DbHelper.COLUMN_SMS_ID + "=?", arrayOf(id.toString())
    )
}

fun Context.deleteAllSmses() {
    database.writableDatabase.delete(DbHelper.TABLE_SMS, null, null)
}
