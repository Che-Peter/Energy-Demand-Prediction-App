package com.prediction.app

import androidx.compose.runtime.NoLiveLiterals
import kotlinx.datetime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.toLocalDateTime
import firebase.auth.getAuth
import firebase.database.getDatabase
import firebase.util.awaitPromise
import kotlinx.datetime.TimeZone
import firebase.auth.signInWithEmailAndPassword
import firebase.database.get
import firebase.database.off
import firebase.database.onValue
import firebase.database.orderByChild
import firebase.database.query
import firebase.database.ref
import firebase.database.remove
import firebase.database.set
import kotlinx.browser.window
import kotlin.js.Date

// --- User state ---
private val _userState = MutableStateFlow<UserEntity?>(null)
val userState: StateFlow<UserEntity?> = _userState

fun updateUser(updatedUser: UserEntity) { _userState.value = updatedUser }
fun getUser(): UserEntity? = _userState.value

private val _hasNewNotification = MutableStateFlow(false)
fun markNotificationsRead() { _hasNewNotification.value = false }

private val _isUploadingMedia = MutableStateFlow(false)
val isUploadingMedia: StateFlow<Boolean> = _isUploadingMedia

fun setUploading(uploading: Boolean) {
    _isUploadingMedia.value = uploading
}

private val _currentScreen = MutableStateFlow<Screen>(Screen.Loading)

// --- Account state ---
private val _account = MutableStateFlow<AccountEntity?>(null)
val accountState: StateFlow<AccountEntity?> = _account

fun updateAccountData(newAccountData: AccountEntity) { _account.value = newAccountData }
fun getAccountData(): AccountEntity? = _account.value

private val _errorMessage = MutableStateFlow<String?>(null)
val errorMessage: StateFlow<String?> = _errorMessage

private val _isLoading = MutableStateFlow(false)
val isLoading: StateFlow<Boolean> = _isLoading

// --- Earthing result state (single item) ---
private val _earthingResult = MutableStateFlow(EarthingReading()) // all fields have defaults
val earthingResult: StateFlow<EarthingReading> = _earthingResult

fun setEarthingResult(result: EarthingReading) {
    _earthingResult.value = result
}

// Flag to indicate new data arrival
val _hasNewEarthingResult = MutableStateFlow(false)


@NoLiveLiterals
fun loadEarthingResults(
    account: AccountEntity,
    repository: FirebaseAndIndexedDBRepository
) {
    MainScope().launch {
        while (true) {
            try {
                // Build ThingSpeak URL
                val url = "https://api.thingspeak.com/channels/3412539/feeds/last.json?api_key=X4N1SN9XUD1IAQM9"
                val response = window.fetch(url).await()
                if (!response.ok) {
                    continue
                }
                val json = response.json().await()

                val node = json.asDynamic()

                val updated = EarthingReading(
                    timestamp = (node?.created_at as? String)?.let { Date.parse(it) / 1000 }?.toLong()
                        ?: js("Date.now()") as Long,
                    voltage = (node?.field1 as? String)?.toFloatOrNull() ?: 0f,
                    current = (node?.field2 as? String)?.toFloatOrNull() ?: 0f,
                    active_power = (node?.field3 as? String)?.toFloatOrNull() ?: 0f,
                    energy_consumption = (node?.field4 as? String)?.toFloatOrNull() ?: 0f,
                    power_factor = (node?.field5 as? String)?.toFloatOrNull() ?: 0f,
                    temperature = (node?.field6 as? String)?.toFloatOrNull() ?: 0f,
                    humidity = (node?.field7 as? String)?.toFloatOrNull() ?: 0f,
                    frequency = (node?.field8 as? String)?.toFloatOrNull() ?: 0f,
                    demand_kWh = ((node?.field1 as? String)?.toFloatOrNull() ?: 0f) * ((node?.field2 as? String)?.toFloatOrNull() ?: 0f),
                    // Copy account attributes
                    building_type = account.wifiName,
                    occupancy_level = account.future_Long.toInt(),
                    operational_schedule = account.wifiPass,
                    electricity_tariff = account.activateDate,
                    appliance_category = account.lastDate
                )

                setEarthingResult(updated)
                _hasNewEarthingResult.value = true
            } catch (_: Throwable) {
            }

            // For now, just use a placeholder interval (e.g. 10s) so it compiles
            delay(account.interval*1000)
        }
    }
}
