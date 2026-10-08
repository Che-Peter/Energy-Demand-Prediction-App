package com.prediction.app

import kotlinx.browser.window
import org.w3c.dom.Navigator

// Externals
external interface Coordinates {
    val latitude: Double
    val longitude: Double
}

external interface GeoPosition {
    val coords: Coordinates
}

external interface PositionError {
    val code: Int
    val message: String
}

external interface Geolocation {
    fun getCurrentPosition(
        success: (GeoPosition) -> Unit,
        error: (PositionError) -> Unit = definedExternally
    )
}

inline val Navigator.geolocation: Geolocation
    get() = asDynamic().geolocation

// Result class
data class LocationResult(
    val isOk: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val errorMessage: String? = null
)


// Function with callback returning LocationResult
fun requestLocation(callback: (LocationResult) -> Unit) {
    val geo = window.navigator.geolocation
    geo.getCurrentPosition({ pos ->
        val lat = pos.coords.latitude
        val lon = pos.coords.longitude
        callback(LocationResult(true, lat, lon))
    }, { err ->
        callback(LocationResult(false, errorMessage = err.message))
    })
}
