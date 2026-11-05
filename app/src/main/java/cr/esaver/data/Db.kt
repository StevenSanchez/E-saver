/*
 * Persistencia local con SQLite.
 *
 * Tablas:
 *  - sessions: cada carga del carro (kWh, duración, foto, periodo, etc.).
 *  - bills: un recibo por periodo (monto total y kWh facturados).
 *
 * No se usa Room para mantener el proyecto simple y sin generación de código.
 */
package cr.esaver.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cr.esaver.domain.Billing
import java.time.Instant
import java.time.ZoneId

/** Helper de SQLite que crea las tablas e índices de E-saver. */
class EsaverDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                energy_kwh REAL NOT NULL,
                duration_sec INTEGER,
                voltage INTEGER,
                current_a REAL,
                power_kw REAL,
                charger_max_a INTEGER,
                photo_path TEXT,
                notes TEXT NOT NULL DEFAULT '',
                period_key TEXT NOT NULL,
                period_override TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_sessions_period ON sessions(period_key)")
        db.execSQL("CREATE INDEX idx_sessions_timestamp ON sessions(timestamp)")

        db.execSQL(
            """
            CREATE TABLE bills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                period_key TEXT NOT NULL UNIQUE,
                total_amount REAL NOT NULL,
                total_kwh REAL NOT NULL,
                energy_amount REAL,
                tax_amount REAL,
                fire_amount REAL,
                invoice_number TEXT NOT NULL DEFAULT '',
                client_number TEXT NOT NULL DEFAULT '',
                meter_number TEXT NOT NULL DEFAULT '',
                reading_from INTEGER,
                reading_to INTEGER,
                photo_path TEXT,
                notes TEXT NOT NULL DEFAULT '',
                paid_at INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_bills_period ON bills(period_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Primera versión pública. Si cambia el esquema, agregar migraciones aquí.
    }

    companion object {
        private const val DB_NAME = "esaver.db"
        private const val DB_VERSION = 1
    }
}

private fun Cursor.longOrZero(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}
private fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}
private fun Cursor.doubleOrNull(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}
private fun Cursor.stringOrEmpty(column: String): String {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) "" else getString(index)
}
private fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

/**
 * Operaciones de lectura/escritura usadas por el ViewModel.
 * Todas las consultas se hacen fuera del hilo principal (ver AppViewModel).
 */
class EsaverRepository(context: Context) {
    private val helper = EsaverDb(context)

    fun sessions(): List<ChargeSession> =
        helper.readableDatabase.query(
            "sessions", null, null, null, null, null, "timestamp DESC, id DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toSession())
            }
        }

    fun bills(): List<Bill> =
        helper.readableDatabase.query(
            "bills", null, null, null, null, null, "period_key DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toBill())
            }
        }

    fun billForPeriod(periodKey: String): Bill? =
        helper.readableDatabase.query(
            "bills", null, "period_key = ?", arrayOf(periodKey), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toBill() else null }

    fun saveSession(session: ChargeSession): Long {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("timestamp", session.timestamp)
            put("energy_kwh", session.energyKwh)
            put("duration_sec", session.durationSec)
            put("voltage", session.voltage)
            put("current_a", session.currentA)
            put("power_kw", session.powerKw)
            put("charger_max_a", session.chargerMaxA)
            put("photo_path", session.photoPath)
            put("notes", session.notes)
            put("period_key", session.periodKey)
            put("period_override", session.periodOverride)
            put("updated_at", System.currentTimeMillis())
        }
        return if (session.id == 0L) {
            values.put("created_at", System.currentTimeMillis())
            db.insert("sessions", null, values)
        } else {
            db.update("sessions", values, "id = ?", arrayOf(session.id.toString()))
            session.id
        }
    }

    fun deleteSession(id: Long) {
        helper.writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    /**
     * Guarda un recibo. Solo puede existir uno por periodo: si ya existe otro,
     * se actualiza ese y se elimina el duplicado si se estaba editando.
     */
    fun saveBill(bill: Bill): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = billForPeriod(bill.periodKey)
            val targetId = when {
                existing != null && existing.id != bill.id -> existing.id
                bill.id != 0L -> bill.id
                else -> 0L
            }
            if (bill.id != 0L && existing != null && existing.id != bill.id) {
                db.delete("bills", "id = ?", arrayOf(bill.id.toString()))
            }
            val values = ContentValues().apply {
                put("period_key", bill.periodKey)
                put("total_amount", bill.totalAmount)
                put("total_kwh", bill.totalKwh)
                put("energy_amount", bill.energyAmount)
                put("tax_amount", bill.taxAmount)
                put("fire_amount", bill.fireAmount)
                put("invoice_number", bill.invoiceNumber)
                put("client_number", bill.clientNumber)
                put("meter_number", bill.meterNumber)
                put("reading_from", bill.readingFrom)
                put("reading_to", bill.readingTo)
                put("photo_path", bill.photoPath)
                put("notes", bill.notes)
                put("paid_at", bill.paidAt)
                put("updated_at", System.currentTimeMillis())
            }
            val savedId = if (targetId == 0L) {
                values.put("created_at", System.currentTimeMillis())
                db.insert("bills", null, values)
            } else {
                db.update("bills", values, "id = ?", arrayOf(targetId.toString()))
                targetId
            }
            db.setTransactionSuccessful()
            return savedId
        } finally {
            db.endTransaction()
        }
    }

    fun deleteBill(id: Long) {
        helper.writableDatabase.delete("bills", "id = ?", arrayOf(id.toString()))
    }

    /** Recalcula el periodo de todas las cargas que no tienen ajuste manual. */
    fun recalculatePeriods(cycleDay: Int) {
        val db = helper.writableDatabase
        val updates = mutableListOf<Pair<Long, String>>()
        db.query("sessions", arrayOf("id", "timestamp", "period_override"), null, null, null, null, null)
            .use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.isNull(cursor.getColumnIndexOrThrow("period_override"))) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                        val ts = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"))
                        val date = Instant.ofEpochMilli(ts)
                            .atZone(ZoneId.systemDefault()).toLocalDate()
                        updates += id to Billing.periodFor(date, cycleDay).key
                    }
                }
            }
        db.beginTransaction()
        try {
            updates.forEach { (id, key) ->
                val values = ContentValues().apply {
                    put("period_key", key)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("sessions", values, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("sessions", null, null)
            db.delete("bills", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toSession(): ChargeSession = ChargeSession(
        id = longOrZero("id"),
        timestamp = longOrZero("timestamp"),
        energyKwh = getDouble(getColumnIndexOrThrow("energy_kwh")),
        durationSec = intOrNull("duration_sec"),
        voltage = intOrNull("voltage"),
        currentA = doubleOrNull("current_a"),
        powerKw = doubleOrNull("power_kw"),
        chargerMaxA = intOrNull("charger_max_a"),
        photoPath = stringOrNull("photo_path"),
        notes = stringOrEmpty("notes"),
        periodKey = stringOrEmpty("period_key"),
        periodOverride = stringOrNull("period_override"),
        createdAt = longOrZero("created_at"),
        updatedAt = longOrZero("updated_at"),
    )

    private fun Cursor.toBill(): Bill = Bill(
        id = longOrZero("id"),
        periodKey = stringOrEmpty("period_key"),
        totalAmount = getDouble(getColumnIndexOrThrow("total_amount")),
        totalKwh = getDouble(getColumnIndexOrThrow("total_kwh")),
        energyAmount = doubleOrNull("energy_amount"),
        taxAmount = doubleOrNull("tax_amount"),
        fireAmount = doubleOrNull("fire_amount"),
        invoiceNumber = stringOrEmpty("invoice_number"),
        clientNumber = stringOrEmpty("client_number"),
        meterNumber = stringOrEmpty("meter_number"),
        readingFrom = intOrNull("reading_from"),
        readingTo = intOrNull("reading_to"),
        photoPath = stringOrNull("photo_path"),
        notes = stringOrEmpty("notes"),
        paidAt = longOrNull("paid_at"),
        createdAt = longOrZero("created_at"),
        updatedAt = longOrZero("updated_at"),
    )
}
