package com.prediction.app

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.browser.window
import org.w3c.dom.events.Event
import firebase.database.getDatabase
import firebase.util.awaitPromise
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.compose.runtime.NoLiveLiterals
import firebase.auth.getAuth
import firebase.auth.sendPasswordResetEmail
import firebase.auth.signInWithEmailAndPassword
import firebase.database.get
import kotlinx.datetime.Clock
import firebase.database.ref
import firebase.database.remove
import firebase.database.set
import kotlinx.coroutines.await
import org.w3c.files.Blob
import org.w3c.files.FileReader
import kotlin.js.Promise
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Headers
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Date

private val client = HttpClient()

// Open the app with ?api=http://localhost:8000 to test against a backend running locally
val PREDICTION_API: String = (
    js("new URLSearchParams(window.location.search).get('api')") as String?
) ?: "https://energy-demand-prediction-api-production.up.railway.app"
private const val THINGSPEAK_CHANNEL = "3412539"
private const val THINGSPEAK_KEY = "X4N1SN9XUD1IAQM9"

class SecurityException(message: String, cause: Throwable? = null) : Exception(message, cause)
external interface IDBDatabase {
    fun createObjectStore(name: String, options: dynamic = definedExternally): dynamic
}
external interface IDBOpenDBRequest {
    val result: dynamic
    val error: dynamic
}

@NoLiveLiterals
class IndexedDBHelper {
    var db: IDBDatabase? = null

    suspend fun init(): IDBDatabase = suspendCancellableCoroutine { cont ->
        val request = window.asDynamic().indexedDB.open("myDatabase", 1) // bumped version to 2
        request.onupgradeneeded = { event: Event ->
            val db = (event.target as IDBOpenDBRequest).result as IDBDatabase

            // ✅ UserEntity keyed by uuid
            val userStore = db.createObjectStore("UserEntity", js("{ keyPath: 'uuid' }"))
            userStore.createIndex("email", "email", js("{ unique: true }"))

            // ✅ AccountEntity keyed by productId
            val accountStore = db.createObjectStore("AccountEntity", js("{ keyPath: 'productId' }"))
            accountStore.createIndex("primaryUuid", "primaryUuid", js("{ unique: false }"))

            // ✅ OfflineNotification keyed by composite (accountId,id,timestamp)
            val notificationStore = db.createObjectStore(
                "OfflineNotification",
                js("{ keyPath: ['accountId','id','timestamp'] }")
            )
            notificationStore.createIndex("accountId", "accountId", js("{ unique: false }"))

            // ✅ ForecastRecords keyed by timestamp (unique)
            val forecastStore = db.createObjectStore("ForecastRecords", js("{ keyPath: 'timestamp' }"))
            forecastStore.createIndex("timestamp", "timestamp", js("{ unique: true }"))
        }

        request.onsuccess = { event: Event ->
            val db = (event.target as IDBOpenDBRequest).result as IDBDatabase
            this.db = db
            cont.resume(db)
        }
        request.onerror = { event: Event ->
            val error = (event.target as IDBOpenDBRequest).error
            cont.cancel(IllegalStateException("Error opening IndexedDB: $error"))
        }
    }
}



class FirebaseAndIndexedDBRepository {
    private val database = getDatabase()
    suspend fun init() {
        dbHelper.init() // wait until IndexedDB is ready
    }

    private val dbHelper = IndexedDBHelper()

    private fun AccountEntity.toContentValues(): dynamic {
        val obj = js("{}")
        obj.sn = sn
        obj.productId = productId
        obj.theId = theId
        obj.decryptedProductId = decryptedProductId
        obj.primaryUuid = primaryUuid
        obj.address = address
        obj.longitude = longitude
        obj.latitude = latitude
        obj.wifiName = wifiName
        obj.wifiPass = wifiPass
        obj.creationDate = creationDate
        obj.activateDate = activateDate
        obj.active = active
        obj.lastDate = lastDate
        obj.lastUpdated = lastUpdated
        obj.High_resistance = High_resistance
        obj.Low_resistance = Low_resistance
        obj.interval = interval
        obj.added_resistance = added_resistance
        obj.future_float = future_float
        obj.future_int = future_int
        obj.future_Long = future_Long
        return obj
    }

    @NoLiveLiterals
    suspend fun fetchAccountFromFirebase(decryptedProductId: String): AccountEntity? {
        val db = getDatabase()
        val accountRef = ref(db, "products/$decryptedProductId/account")
        return try {
            val snapshot = withTimeoutOrNull(5000L) {
                get(accountRef).awaitPromise()
            } ?: return null
            if (!snapshot.exists()) return null
            val obj = snapshot.`val`()!!.asDynamic()
            AccountEntity(
                sn = (obj.sn as? Number)?.toLong() ?: 0L,
                productId = (obj.productId as? Number)?.toInt() ?: 0,
                theId = obj.theId as? String ?: "",
                decryptedProductId = obj.decryptedProductId as? String ?: "",
                primaryUuid = obj.primaryUuid as? String ?: "",
                address = obj.address as? String ?: "",
                longitude = obj.longitude as? String ?: "",
                latitude = obj.latitude as? String ?: "",
                wifiName = obj.wifiName as? String ?: "",
                wifiPass = obj.wifiPass as? String ?: "",
                creationDate = obj.creationDate as? String ?: "",
                activateDate = obj.activateDate as? String ?: "",
                active = obj.active as? Boolean ?: false,
                lastDate = obj.lastDate as? String ?: "",
                lastUpdated = (obj.lastUpdated as? Number)?.toLong() ?: 0L,
                High_resistance = (obj.High_resistance as? Number)?.toFloat() ?: 0f,
                Low_resistance = (obj.Low_resistance as? Number)?.toFloat() ?: 0f,
                interval = (obj.interval as? Number)?.toLong() ?: 0L,
                added_resistance = (obj.added_resistance as? Number)?.toFloat() ?: 0f,
                future_float = (obj.future_float as? Number)?.toFloat() ?: 0f,
                future_int = (obj.future_int as? Number)?.toInt() ?: 0,
                future_Long = (obj.future_Long as? Number)?.toLong() ?: 0L
            )
        } catch (e: Exception) {
            if (e.message?.contains("Permission denied", ignoreCase = true) == true) {
                throw SecurityException("Permission denied for product: $decryptedProductId", e)
            }
            null
        }
    }

    @NoLiveLiterals
    fun saveAccountToSQLite(account: AccountEntity) {
        val db = dbHelper.db ?: return
        val tx = db.asDynamic().transaction("AccountEntity", "readwrite")
        val store = tx.objectStore("AccountEntity")
        val request = store.get(account.productId)

        request.onsuccess = { _: dynamic ->
            val existing = request.result
            val obj = account.toContentValues()
            // Force numeric fields into JS Number (Double)
            obj.sn = account.sn.toDouble()
            obj.lastUpdated = account.lastUpdated.toDouble()
            obj.interval = account.interval.toDouble()
            obj.future_Long = account.future_Long.toDouble()
            obj.High_resistance = account.High_resistance.toDouble()
            obj.Low_resistance = account.Low_resistance.toDouble()
            obj.added_resistance = account.added_resistance.toDouble()
            obj.future_float = account.future_float.toDouble()

            if (existing != null) {
                try {
                    val existingAccount = AccountEntity(
                        sn = (existing.sn as? Number)?.toLong() ?: 0L,
                        productId = (existing.productId as? Number)?.toInt() ?: 0,
                        theId = existing.theId as? String ?: "",
                        decryptedProductId = existing.decryptedProductId as? String ?: "",
                        primaryUuid = existing.primaryUuid as? String ?: "",
                        address = existing.address as? String ?: "",
                        longitude = existing.longitude as? String ?: "",
                        latitude = existing.latitude as? String ?: "",
                        wifiName = existing.wifiName as? String ?: "",
                        wifiPass = existing.wifiPass as? String ?: "",
                        creationDate = existing.creationDate as? String ?: "",
                        activateDate = existing.activateDate as? String ?: "",
                        active = existing.active as? Boolean ?: false,
                        lastDate = existing.lastDate as? String ?: "",
                        lastUpdated = (existing.lastUpdated as? Number)?.toLong() ?: 0L,
                        High_resistance = (existing.High_resistance as? Number)?.toFloat() ?: 0f,
                        Low_resistance = (existing.Low_resistance as? Number)?.toFloat() ?: 0f,
                        interval = (existing.interval as? Number)?.toLong() ?: 0L,
                        added_resistance = (existing.added_resistance as? Number)?.toFloat() ?: 0f,
                        future_float = (existing.future_float as? Number)?.toFloat() ?: 0f,
                        future_int = (existing.future_int as? Number)?.toInt() ?: 0,
                        future_Long = (existing.future_Long as? Number)?.toLong() ?: 0L
                    )
                    if (account != existingAccount) {
                        store.put(obj)
                    } else {

                    }
                } catch (e: Exception) {
                    store.put(obj)
                }
            } else {
                store.put(obj)
            }
        }

        request.onerror = { _: dynamic -> console.error("❌ IndexedDB get error") }
        tx.oncomplete = { _: dynamic -> console.log("✅ Transaction complete") }
        tx.onerror = { _: dynamic -> console.error("❌ Transaction error") }
    }

    @NoLiveLiterals
    suspend fun syncDataFromFirebase(
        firebaseAccount: AccountEntity? = null,
    ) {
        var account = firebaseAccount
        if (account == null) {
            // Try to get decrypted productId from local state (SQLite or StateFlow)
            val localAccount = getAccountData()
            val decryptedId = localAccount?.decryptedProductId
            if (decryptedId != null) {
                account = fetchAccountFromFirebase(decryptedId)
                if (account == null) {
                    return
                }
            } else {
                return
            }
        }
        val today = js("new Date()") as Date
        val yesterday = Date(today.getTime() - 24L * 60 * 60 * 1000)
        val yesterdayStr = yesterday.toISOString().substring(0, 10) // "YYYY-MM-DD"
        val currentYear = yesterdayStr.substring(0, 4)
        var lastDate = account.lastDate
        if (lastDate.isEmpty()) {
            lastDate = account.creationDate
        }
        val lastYear = if (lastDate.isNotEmpty()) lastDate.substring(0, 4) else null
        if (lastDate < yesterdayStr) {
        }
    }

    @NoLiveLiterals
    suspend fun saveAccountToFirebase(account: AccountEntity): Boolean {
        if (!NetworkStatus.isNetworkAvailable.value) return false
        return try {
            withTimeout(5_000L) {
                val sanitizedAccount = account.copy(
                    lastUpdated = if (account.lastUpdated == 0L) 1L else account.lastUpdated,
                    sn = if (account.sn == 0L) 1L else account.sn
                )
                val db = getDatabase()
                val accountRef = ref(db, "products/${account.decryptedProductId}/account")
                val obj: dynamic = js("{}")
                obj.sn = sanitizedAccount.sn.toDouble()
                obj.productId = sanitizedAccount.productId
                obj.theId = sanitizedAccount.theId
                obj.decryptedProductId = sanitizedAccount.decryptedProductId
                obj.primaryUuid = sanitizedAccount.primaryUuid
                obj.address = sanitizedAccount.address
                obj.longitude = sanitizedAccount.longitude
                obj.latitude = sanitizedAccount.latitude
                obj.wifiName = sanitizedAccount.wifiName
                obj.wifiPass = sanitizedAccount.wifiPass
                obj.creationDate = sanitizedAccount.creationDate
                obj.activateDate = sanitizedAccount.activateDate
                obj.active = sanitizedAccount.active
                obj.lastDate = sanitizedAccount.lastDate
                obj.lastUpdated = sanitizedAccount.lastUpdated.toDouble()
                obj.High_resistance = sanitizedAccount.High_resistance
                obj.Low_resistance = sanitizedAccount.Low_resistance
                obj.interval = sanitizedAccount.interval.toDouble()
                obj.added_resistance = sanitizedAccount.added_resistance
                obj.future_float = sanitizedAccount.future_float
                obj.future_int = sanitizedAccount.future_int
                obj.future_Long = sanitizedAccount.future_Long.toDouble()
                set(accountRef, obj).awaitPromise()
                true
            }
        } catch (_: TimeoutCancellationException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    @NoLiveLiterals
    suspend fun getAccountsFromSQLite(): List<AccountEntity> {
        val db = dbHelper.db ?: run {
            return emptyList()
        }
        return try {
            val tx = db.asDynamic().transaction("AccountEntity", "readonly")
            val store = tx.objectStore("AccountEntity")
            val request = store.getAll()
            suspendCoroutine<List<AccountEntity>> { continuation ->
                request.onsuccess = { _: dynamic ->
                    val results = request.result.unsafeCast<Array<dynamic>>()
                    val accounts = results.mapNotNull { obj ->
                        try {
                            AccountEntity(
                                sn = (obj.sn as? Number)?.toLong() ?: 0L,
                                productId = (obj.productId as? Number)?.toInt() ?: 0,
                                theId = obj.theId as? String ?: "",
                                decryptedProductId = obj.decryptedProductId as? String ?: "",
                                primaryUuid = obj.primaryUuid as? String ?: "",
                                address = obj.address as? String ?: "",
                                longitude = obj.longitude as? String ?: "",
                                latitude = obj.latitude as? String ?: "",
                                wifiName = obj.wifiName as? String ?: "",
                                wifiPass = obj.wifiPass as? String ?: "",
                                creationDate = obj.creationDate as? String ?: "",
                                activateDate = obj.activateDate as? String ?: "",
                                active = obj.active as? Boolean ?: false,
                                lastDate = obj.lastDate as? String ?: "",
                                lastUpdated = (obj.lastUpdated as? Number)?.toLong() ?: 0L,
                                High_resistance = (obj.High_resistance as? Number)?.toFloat() ?: 0f,
                                Low_resistance = (obj.Low_resistance as? Number)?.toFloat() ?: 0f,
                                interval = (obj.interval as? Number)?.toLong() ?: 0L,
                                added_resistance = (obj.added_resistance as? Number)?.toFloat()
                                    ?: 0f,
                                future_float = (obj.future_float as? Number)?.toFloat() ?: 0f,
                                future_int = (obj.future_int as? Number)?.toInt() ?: 0,
                                future_Long = (obj.future_Long as? Number)?.toLong() ?: 0L
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    continuation.resume(accounts)
                }
                request.onerror = { _: dynamic ->
                    continuation.resume(emptyList())
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @NoLiveLiterals
    suspend fun signInUser(
        email: String,
        password: String
    ): SignInResult {

        return try {
            withTimeout(30_000L) {
                val auth = getAuth()

                val credential =
                    signInWithEmailAndPassword(auth, email, password)
                        .awaitPromise()

                SignInResult(
                    user = credential.user,
                    error = null
                )
            }

        } catch (e: TimeoutCancellationException) {

            //consol..error("Sign-in timed out:", e)

            SignInResult(
                user = null,
                error = "Sign-in timed out"
            )

        } catch (t: Throwable) {

            //consol..error("Sign-in failed:", t)

            SignInResult(
                user = null,
                error = t.message ?: "Unknown login error"
            )
        }
    }


    @NoLiveLiterals
    suspend fun sendPasswordResetEmail(email: String): Boolean {
        return try {
            val auth = getAuth() // default app
            sendPasswordResetEmail(auth, email).awaitPromise()
            true
        } catch (_: Exception) {
            false
        }
    }

    @NoLiveLiterals
    suspend fun getConfigValue(key: String): String? {
        return try {
            val db = getDatabase()
            val configRef = ref(db, "config/$key")
            val snapshot = get(configRef).awaitPromise()
            if (snapshot.exists()) {
                snapshot.`val`()?.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @NoLiveLiterals
    suspend fun checkIfProductIsAlreadySignedUp(productId: String): Boolean {
        return try {
            val db = getDatabase()
            val deviceRef = ref(db, "devices/$productId")

            val snapshot = get(deviceRef).awaitPromise() // modular get(ref)

            snapshot.child("uuid").exists() &&
                    snapshot.child("uuid").`val`().toString().isNotBlank()
        } catch (_: Exception) {
            false
        }
    }


    @NoLiveLiterals
    suspend fun saveUserToSQLite(user: UserEntity) {
        val db = dbHelper.db ?: run {
            console.error("❌ IndexedDB not initialized")
            return
        }

        val tx = db.asDynamic().transaction("UserEntity", "readwrite")
        val store = tx.objectStore("UserEntity")

        val obj = js("{}")
        obj.uuid = user.uuid
        obj.name = user.name
        obj.email = user.email
        obj.isLoggedIn = if (user.isLoggedIn) 1 else 0
        obj.loggedIntoProduct = user.loggedIntoProduct
        obj.lastLoginRole = user.lastLoginRole
        obj.creationDate = user.creationDate
        obj.lastAccessedDate = user.lastAccessedDate
        obj.language = user.language
        obj.ownedProducts = user.ownedProducts.toTypedArray()

        val request = store.get(user.uuid)

        suspendCancellableCoroutine<Unit> { continuation ->
            request.onsuccess = { _: dynamic ->
                console.log("✅ saveUserToSQLite: existing record found, updating:", obj)
                store.put(obj)
                if (continuation.isActive) continuation.resume(Unit)
            }

            request.onerror = { _: dynamic ->
                console.error("❌ saveUserToSQLite: IndexedDB get error:", request.error)
                if (continuation.isActive) continuation.resume(Unit)
            }

            tx.oncomplete = { _: dynamic ->
                console.log("✅ saveUserToSQLite: transaction complete for uuid:", user.uuid)
            }
            tx.onerror = { _: dynamic ->
                console.error("❌ saveUserToSQLite: transaction error:", tx.error)
            }
        }
    }

    @NoLiveLiterals
    suspend fun getUserFromSQLite(): UserEntity? {
        val db = dbHelper.db ?: run {
            console.error("❌ getUserFromSQLite: IndexedDB not initialized")
            return null
        }

        return try {
            val tx = db.asDynamic().transaction("UserEntity", "readonly")
            val store = tx.objectStore("UserEntity")
            val request = store.openCursor()

            suspendCancellableCoroutine<UserEntity?> { continuation ->
                request.onsuccess = { _: dynamic ->
                    val cursor = request.result
                    if (cursor != null) {
                        val obj = cursor.value.unsafeCast<dynamic>()
                        console.log("✅ getUserFromSQLite: raw object from IndexedDB:", obj)

                        val ownedProductsList = mutableListOf<String>()
                        val jsArray = obj.ownedProducts as? Array<String>
                        jsArray?.forEach { ownedProductsList.add(it) }

                        val user = UserEntity(
                            uuid = obj.uuid as? String ?: "",
                            name = obj.name as? String ?: "",
                            email = obj.email as? String ?: "",
                            isLoggedIn = (obj.isLoggedIn as? Number)?.toInt() == 1,
                            loggedIntoProduct = obj.loggedIntoProduct as? String ?: "",
                            lastLoginRole = obj.lastLoginRole as? String ?: "",
                            creationDate = obj.creationDate as? String ?: "",
                            lastAccessedDate = obj.lastAccessedDate as? String ?: "",
                            language = obj.language as? String ?: "English",
                            ownedProducts = ownedProductsList
                        )

                        console.log("✅ getUserFromSQLite: parsed UserEntity:", user)
                        if (continuation.isActive) continuation.resume(user)
                    } else {
                        console.warn("⚠️ getUserFromSQLite: no user found in IndexedDB")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }

                request.onerror = { _: dynamic ->
                    console.error("❌ getUserFromSQLite: IndexedDB get error:", request.error)
                    if (continuation.isActive) continuation.resume(null)
                }

                tx.oncomplete = { _: dynamic ->
                    console.log("✅ getUserFromSQLite: transaction complete")
                }
                tx.onerror = { _: dynamic ->
                    console.error("❌ getUserFromSQLite: transaction error:", tx.error)
                }
            }
        } catch (e: Exception) {
            console.error("❌ getUserFromSQLite: exception fetching user:", e)
            null
        }
    }


    @NoLiveLiterals
    suspend fun deleteAllUsersFromSQLite() {
        val db = dbHelper.db ?: run {
            //consol..error("IndexedDB not initialized")
            return
        }
        try {
            val tx = db.asDynamic().transaction("UserEntity", "readwrite")
            val store = tx.objectStore("UserEntity")

            val request = store.clear()

            suspendCancellableCoroutine<Unit> { continuation ->
                request.onsuccess = { _: dynamic ->
                    if (continuation.isActive) continuation.resume(Unit)
                }
                request.onerror = { _: dynamic ->
                    //consol..error("Error clearing UserEntity store: ${request.error}")
                    if (continuation.isActive) continuation.resume(Unit)
                }

                tx.oncomplete = { _: dynamic ->
                    //console.log("All users deleted successfully from IndexedDB")
                }

                tx.onerror = { _: dynamic ->
                    //consol..error("Transaction error clearing users: ${tx.error}")
                }
            }
        } catch (_: Exception) {
            //consol..error("Exception deleting users from IndexedDB")
        }
    }

    @NoLiveLiterals
    suspend fun saveUserToRTDB(user: UserEntity): Boolean {
        if (!NetworkStatus.isNetworkAvailable.value) return false
        return try {
            withTimeout(5_000L) {
                val db = getDatabase() // modular API instance
                val userRef = ref(db, "users/${user.uuid}")

                // Build a dynamic object for RTDB
                val obj = js("{}").unsafeCast<dynamic>()
                obj.uuid = user.uuid
                obj.name = user.name
                obj.email = user.email
                obj.isLoggedIn = user.isLoggedIn
                obj.loggedIntoProduct = user.loggedIntoProduct
                obj.lastLoginRole = user.lastLoginRole
                obj.creationDate = user.creationDate
                obj.lastAccessedDate = user.lastAccessedDate
                obj.language = user.language
                // ✅ ownedProducts is a List<String>, so store as array
                obj.ownedProducts = user.ownedProducts.toTypedArray()
                // Save to RTDB
                set(userRef, obj).awaitPromise()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @NoLiveLiterals
    suspend fun getUserFromRTDB(uuid: String): UserEntity? {
        if (!NetworkStatus.isNetworkAvailable.value) return null
        return try {
            withTimeout(5_000L) {
                val db = getDatabase()
                val userRef = ref(db, "users/$uuid")
                val snapshot = get(userRef).awaitPromise()
                val data = snapshot.`val`().unsafeCast<dynamic>() ?: return@withTimeout null

                // Convert ownedProducts back into Kotlin List<String>
                val ownedProductsList = mutableListOf<String>()
                val jsArray = data.ownedProducts as? Array<String>
                jsArray?.forEach { ownedProductsList.add(it) }

                UserEntity(
                    uuid = data.uuid as? String ?: "",
                    name = data.name as? String ?: "",
                    email = data.email as? String ?: "",
                    ownedProducts = ownedProductsList,
                    isLoggedIn = data.isLoggedIn as? Boolean ?: false,
                    loggedIntoProduct = data.loggedIntoProduct as? String ?: "",
                    lastLoginRole = data.lastLoginRole as? String ?: "",
                    creationDate = data.creationDate as? String ?: "",
                    lastAccessedDate = data.lastAccessedDate as? String ?: "",
                    language = data.language as? String ?: "English"
                )
            }
        } catch (_: Exception) {
            null
        }
    }


    // ThingSpeak returns at most 8000 entries per request
    private val THINGSPEAK_PAGE = 8000

    private fun jsonHeaders(): Headers {
        val headers = Headers()
        headers.append("Content-Type", "application/json")
        return headers
    }

    // ThingSpeak expects "YYYY-MM-DD HH:NN:SS" in UTC
    private fun thingSpeakTime(ms: Long): String {
        val iso = Date(ms.toDouble()).toISOString().substring(0, 19).replace("T", " ")
        return js("encodeURIComponent")(iso) as String
    }

    private fun feedToLiveData(item: dynamic, account: AccountEntity): liveData? {
        val createdAt = item.created_at as? String ?: return null
        val timestamp = (Date.parse(createdAt) / 1000.0).toLong()

        val voltageVal = item.field1?.toString()?.toFloatOrNull() ?: 0f
        val currentVal = item.field2?.toString()?.toFloatOrNull() ?: 0f
        val pfVal = item.field5?.toString()?.toFloatOrNull() ?: 0f

        return liveData(
            timestamp = timestamp,
            voltage = voltageVal,
            current = currentVal,
            active_power = voltageVal * currentVal * pfVal,
            energy_consumption = 0f,
            power_factor = pfVal,
            temperature = item.field6?.toString()?.toFloatOrNull() ?: 0f,
            humidity = item.field7?.toString()?.toFloatOrNull() ?: 0f,
            frequency = item.field8?.toString()?.toFloatOrNull() ?: 0f,
            demand_kWh = voltageVal * currentVal,
            building_type = account.wifiName,
            occupancy_level = account.future_int,
            operational_schedule = account.wifiPass,
            electricity_tariff = account.creationDate,
            appliance_category = account.lastDate
        )
    }

    /**
     * Fetches every ThingSpeak entry between startMs and endMs (inclusive),
     * paging backwards when a window holds more than one page.
     * Returns null when a request fails, so callers never mistake a failure for "no data".
     */
    @NoLiveLiterals
    private suspend fun fetchThingSpeak(account: AccountEntity, startMs: Long, endMs: Long): List<liveData>? {
        val byTimestamp = mutableMapOf<Long, liveData>()
        var windowEnd = endMs

        while (windowEnd >= startMs) {
            val url = "https://api.thingspeak.com/channels/$THINGSPEAK_CHANNEL/feeds.json" +
                    "?api_key=$THINGSPEAK_KEY" +
                    "&results=$THINGSPEAK_PAGE" +
                    "&start=${thingSpeakTime(startMs)}" +
                    "&end=${thingSpeakTime(windowEnd)}"

            val response = window.fetch(url).await()
            if (!response.ok) {
                println("ThingSpeak request failed: ${response.status}")
                return null
            }

            val json: dynamic = response.json().await()
            val feeds = (json.feeds ?: emptyArray<dynamic>()).unsafeCast<Array<dynamic>>()

            var oldest = Long.MAX_VALUE
            feeds.forEach { item ->
                val data = feedToLiveData(item, account) ?: return@forEach
                byTimestamp[data.timestamp] = data
                if (data.timestamp < oldest) oldest = data.timestamp
            }

            // A short page means we have everything in the window
            if (feeds.size < THINGSPEAK_PAGE || oldest == Long.MAX_VALUE) break

            val nextEnd = oldest * 1000L - 1000L
            if (nextEnd >= windowEnd) break
            windowEnd = nextEnd
        }

        return byTimestamp.values.sortedBy { it.timestamp }
    }

    @NoLiveLiterals
    private suspend fun postToBackend(path: String): Pair<Boolean, dynamic> {
        val response = window.fetch(
            "$PREDICTION_API$path",
            RequestInit(method = "POST", headers = jsonHeaders())
        ).await()
        val text = response.text().await()
        val json: dynamic = try { JSON.parse<dynamic>(text) } catch (_: Throwable) { null }
        return response.ok to json
    }

    /**
     * Asks the server to pull new ThingSpeak readings. The server only fetches readings
     * newer than the last one it stored, retrains if any arrived and refreshes the forecasts.
     * Called whenever data is about to be displayed; nothing is pulled automatically.
     */
    @NoLiveLiterals
    suspend fun syncNewData(account: AccountEntity): Boolean {
        return try {
            val (ok, json) = postToBackend("/sync")
            if (ok && json?.added != null) println("Server sync: ${json.added} new readings")
            ok
        } catch (e: Throwable) {
            println("Sync error: ${e.message}")
            false
        }
    }

    /**
     * Refresh all: deletes everything on the server, then lets it reload every ThingSpeak
     * reading and retrain. Returns null on success, or an error message.
     */
    @NoLiveLiterals
    suspend fun refreshAll(account: AccountEntity, onProgress: (String) -> Unit): String? {
        return try {
            onProgress("Deleting server data…")
            val (resetOk, _) = postToBackend("/reset")
            if (!resetOk) return "Reset failed"
            clearForecastRecords()

            onProgress("Server is reloading all ThingSpeak readings and retraining…")
            val (ok, json) = postToBackend("/sync")
            if (!ok) return "Reload failed: ${json?.error ?: "server error"}"
            if (json.skipped != null) return "Server was busy syncing, try again in a minute"

            val trainError = json.train?.skipped as? String
            if (trainError != null) return "Loaded ${json.added} readings but did not train: $trainError"

            onProgress("Done: ${json.added} readings loaded, models retrained, ${json.train?.forecasts ?: 0} forecasts made")
            null
        } catch (e: Throwable) {
            e.message ?: "Unknown error"
        }
    }

    /**
     * Stored forecasts with timestamps in [startMs, endMs], from the server.
     * Cached in IndexedDB so the graph still shows them when the server is unreachable.
     */
    @NoLiveLiterals
    suspend fun getForecasts(startMs: Double, endMs: Double): List<ForecastRecord> {
        val startSec = (startMs / 1000).toLong()
        val endSec = (endMs / 1000).toLong()
        try {
            val response = window.fetch("$PREDICTION_API/forecasts?start=$startSec&end=$endSec").await()
            if (response.ok) {
                val json: dynamic = response.json().await()
                val rows = json.forecasts.unsafeCast<Array<dynamic>>()
                val records = rows.map { row ->
                    ForecastRecord(
                        sn = 0,
                        timestamp = (row.timestamp as Number).toInt(),
                        predXgb = (row.pred_xgb as Number).toDouble(),
                        predRf = (row.pred_rf as Number).toDouble()
                    )
                }.sortedBy { it.timestamp }
                saveForecastRecords(records)
                return records
            }
            println("Forecast request failed: ${response.status}")
        } catch (e: Throwable) {
            println("Forecast request error: ${e.message}")
        }
        return getForecastRecords()
            .filter { it.timestamp.toLong() in startSec..endSec }
            .sortedBy { it.timestamp }
    }

    /** One-line summary of how accurate the models were on recent unseen data. */
    @NoLiveLiterals
    suspend fun getModelSummary(): String {
        return try {
            val response = window.fetch("$PREDICTION_API/status").await()
            if (!response.ok) return "Model status unavailable (${response.status})"
            val json: dynamic = response.json().await()
            if (json.model_trained != true) return "Model not trained yet (${json.rows} readings stored)"
            val m = json.metrics
            val trainedAt = Date((json.trained_at as Number).toDouble() * 1000).toLocaleString("en-GB")
            if (m == null) {
                "Trained $trainedAt on ${json.rows} readings. Not enough data yet to measure accuracy."
            } else {
                "Trained $trainedAt on ${json.rows} readings. Average error on recent unseen data " +
                        "(mean demand ${m.mean_demand}): XGBoost ${m.mae_xgb}, RandomForest ${m.mae_rf}, " +
                        "daily profile alone ${m.mae_profile_only}"
            }
        } catch (e: Throwable) {
            "Model status unavailable: ${e.message}"
        }
    }

    /** Returns the readings in [startTime, endTime] (ms) for display, after syncing new data. */
    @NoLiveLiterals
    suspend fun getLiveData(
        account: AccountEntity,
        startTime: Long,
        endTime: Long
    ): List<liveData> {
        syncNewData(account)
        return try {
            fetchThingSpeak(account, startTime, endTime) ?: emptyList()
        } catch (e: Throwable) {
            println("ThingSpeak Error: ${e.message}")
            emptyList()
        }
    }
    @NoLiveLiterals
    suspend fun saveForecastRecord(record: ForecastRecord) {
        val db = dbHelper.db ?: run {
            console.error("❌ saveForecastRecord: IndexedDB not initialized")
            return
        }

        val tx = db.asDynamic().transaction("ForecastRecords", "readwrite")
        val store = tx.objectStore("ForecastRecords")

        val obj = js("{}")
        obj.timestamp = record.timestamp
        obj.predXgb = record.predXgb
        obj.predRf = record.predRf

        val request = store.get(record.timestamp)

        suspendCancellableCoroutine<Unit> { continuation ->
            request.onsuccess = { _: dynamic ->
                console.log("✅ saveForecastRecord: inserting/updating record:", obj)
                store.put(obj)
                if (continuation.isActive) continuation.resume(Unit)
            }

            request.onerror = { _: dynamic ->
                console.error("❌ saveForecastRecord: IndexedDB get error:", request.error)
                if (continuation.isActive) continuation.resume(Unit)
            }

            tx.oncomplete = { _: dynamic ->
                console.log("✅ saveForecastRecord: transaction complete for timestamp:", record.timestamp)
            }
            tx.onerror = { _: dynamic ->
                console.error("❌ saveForecastRecord: transaction error:", tx.error)
            }
        }
    }

    /** Writes many forecasts in a single IndexedDB transaction (replacing same timestamps). */
    @NoLiveLiterals
    suspend fun saveForecastRecords(records: List<ForecastRecord>) {
        if (records.isEmpty()) return
        val db = dbHelper.db ?: return
        suspendCancellableCoroutine<Unit> { continuation ->
            val tx = db.asDynamic().transaction("ForecastRecords", "readwrite")
            val store = tx.objectStore("ForecastRecords")
            records.forEach { rec ->
                val obj = js("{}")
                obj.timestamp = rec.timestamp
                obj.predXgb = rec.predXgb
                obj.predRf = rec.predRf
                store.put(obj)
            }
            tx.oncomplete = { _: dynamic -> if (continuation.isActive) continuation.resume(Unit) }
            tx.onerror = { _: dynamic ->
                console.error("❌ saveForecastRecords: transaction error:", tx.error)
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    @NoLiveLiterals
    suspend fun clearForecastRecords() {
        val db = dbHelper.db ?: return
        suspendCancellableCoroutine<Unit> { continuation ->
            val tx = db.asDynamic().transaction("ForecastRecords", "readwrite")
            tx.objectStore("ForecastRecords").clear()
            tx.oncomplete = { _: dynamic -> if (continuation.isActive) continuation.resume(Unit) }
            tx.onerror = { _: dynamic -> if (continuation.isActive) continuation.resume(Unit) }
        }
    }

    @NoLiveLiterals
    suspend fun getForecastRecords(): List<ForecastRecord> {
        val db = dbHelper.db ?: run {
            console.error("❌ getForecastRecords: IndexedDB not initialized")
            return emptyList()
        }

        return try {
            val tx = db.asDynamic().transaction("ForecastRecords", "readonly")
            val store = tx.objectStore("ForecastRecords")
            val request = store.getAll()   // ✅ fetch all records at once

            suspendCancellableCoroutine<List<ForecastRecord>> { continuation ->
                request.onsuccess = { _: dynamic ->
                    val resultArray = request.result as Array<dynamic>
                    val allRecords = resultArray.map { obj ->
                        ForecastRecord(
                            sn = 0, // not used now
                            timestamp = obj.timestamp as Int,
                            predXgb = obj.predXgb as Double,
                            predRf = obj.predRf as Double
                        )
                    }
                    console.log("✅ getForecastRecords: fetched ${allRecords.size} records")
                    if (continuation.isActive) continuation.resume(allRecords)
                }

                request.onerror = { _: dynamic ->
                    console.error("❌ getForecastRecords: IndexedDB get error:", request.error)
                    if (continuation.isActive) continuation.resume(emptyList())
                }

                tx.oncomplete = { _: dynamic ->
                    console.log("✅ getForecastRecords: transaction complete")
                }
                tx.onerror = { _: dynamic ->
                    console.error("❌ getForecastRecords: transaction error:", tx.error)
                }
            }
        } catch (e: Exception) {
            console.error("❌ getForecastRecords: exception fetching records:", e)
            emptyList()
        }
    }

}


/*
@NoLiveLiterals suspend fun saveMedia(mediaKey: String, blob: Blob, type: Int)
@NoLiveLiterals suspend fun getMedia(mediaKey: String): Blob?
@NoLiveLiterals suspend fun saveImageToIndexedDB(profileTitle: String, blob: Blob)
@NoLiveLiterals fun loadImageFromStorage(key: String): String?
@NoLiveLiterals suspend fun saveMessageToRTDB(decryptedProductId: String, message: AppNotification): Boolean
@NoLiveLiterals suspend fun getUserFromRTDB(uuid: String): UserEntity?
@NoLiveLiterals suspend fun saveUserToRTDB(user: UserEntity): Boolean
@NoLiveLiterals suspend fun deleteAllUsersFromSQLite()
@NoLiveLiterals suspend fun getUserFromSQLite(): UserEntity?
@NoLiveLiterals suspend fun saveUserToSQLite(user: UserEntity)
@NoLiveLiterals suspend fun getAccountByIdFromSQLite(productId: Int): AccountEntity?
@NoLiveLiterals suspend fun getAccountsFromSQLite(): List<AccountEntity>
@NoLiveLiterals suspend fun saveAccountToFirebase(account: AccountEntity): Boolean
@NoLiveLiterals suspend fun syncDataFromFirebase(firebaseAccount: AccountEntity? = null)
@NoLiveLiterals fun saveAccountToSQLite(account: AccountEntity)
@NoLiveLiterals suspend fun fetchAccountFromFirebase(decryptedProductId: String): AccountEntity?
@NoLiveLiterals suspend fun init()
@NoLiveLiterals suspend fun signInUser(email: String, password: String): firebase.auth.User?
@NoLiveLiterals suspend fun sendPasswordResetEmail(email: String): Boolean
@NoLiveLiterals suspend fun checkIfProductIsAlreadySignedUp(productId: String): Boolean
@NoLiveLiterals suspend fun getOfflineNotifications(accountId: Int): List<AppNotification>
@NoLiveLiterals suspend fun saveOrUpdateOfflineNotification(notification: AppNotification)
 */

