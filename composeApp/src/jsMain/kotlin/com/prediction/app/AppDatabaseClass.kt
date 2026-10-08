package com.prediction.app

data class UserEntity(
    val uuid: String = "",
    val name: String = "",
    val email: String = "",
    val ownedProducts: List<String> = listOf(),
    val isLoggedIn: Boolean = false,
    val loggedIntoProduct: String = "",
    val lastAccessedDate: String = "",
    val lastLoginRole: String = "",
    val creationDate: String = "",
    val language: String = "English"
)

data class SignInResult(
    val user: firebase.auth.User? = null,
    val error: String? = null
)

data class AccountEntity(
    var sn: Long = 0L,
    var productId: Int = 0,
    var theId: String = "",
    var decryptedProductId: String = "",
    var primaryUuid: String = "",
    var address: String = "",
    var longitude: String = "",
    var latitude: String = "",
    // Reused: building_type
    var wifiName: String = "",            // building_type
    // Reused: operational_schedule
    var wifiPass: String = "",            // operational_schedule
    // Reused: electricity_tariff
    var creationDate: String = "",        // electricity_tariff
    var activateDate: String = "",
    var active: Boolean = false,
    // Reused: appliance_category
    var lastDate: String = "",            // appliance_category
    var lastUpdated: Long = 0L,
    var High_resistance: Float = 0f,
    var Low_resistance: Float = 0f,
    var interval: Long = 0,
    var added_resistance: Float = 0f,
    var future_float: Float = 0f,
    // Reused: occupancy_level
    var future_int: Int = 0,              // occupancy_level
    var future_Long: Long = 0
)

data class EarthingReading(
    val timestamp: Long = 0L,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val active_power: Float = 0f,
    val energy_consumption: Float = 0f,
    val power_factor: Float = 0f,
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val frequency: Float = 0f,
    val light_intensity: Float = 0f,
    val building_type: String = "",
    val occupancy_level: Int = 0,
    val operational_schedule: String = "",
    val electricity_tariff: String = "",
    val appliance_category: String = "",
    val demand_kWh: Float = 0f
)

data class liveData(
    val timestamp: Long = 0L,
    val voltage: Float = 0f,
    val current: Float = 0f,
    val active_power: Float = 0f,
    val energy_consumption: Float = 0f,
    val power_factor: Float = 0f,
    val temperature: Float = 0f,
    val humidity: Float = 0f,
    val frequency: Float = 0f,
    val light_intensity: Float = 0f,
    val building_type: String = "",
    val occupancy_level: Int = 0,
    val operational_schedule: String = "",
    val electricity_tariff: String = "",
    val appliance_category: String = "",
    val demand_kWh: Float = 0f
)
