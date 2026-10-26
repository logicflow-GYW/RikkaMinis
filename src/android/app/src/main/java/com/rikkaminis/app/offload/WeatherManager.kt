package com.rikkaminis.app.offload

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches weather data from the Open-Meteo public API (no API key required).
 * Returns the raw Open-Meteo JSON (current conditions, 48-hour hourly forecast,
 * 10-day daily forecast); presentation is the caller's job — the sandbox
 * `android-weather` handler slices it into its structured envelope.
 *
 * [debt-1026 #5] There is deliberately ONE exit. This object used to also render
 * a human-readable report (`fetchWeather` → `formatWeatherResponse`) for the
 * legacy positional form, which was a second presentation path in another file
 * with its own duplicate WMO code table — nothing else called it.
 */
object WeatherManager {

    private const val TAG = "WeatherManager"
    private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectionPool(okhttp3.ConnectionPool(5, 30, TimeUnit.SECONDS))
        .build()

    /**
     * Fetch raw Open-Meteo JSON. Returns null on transport error and stores
     * a user-readable reason in [lastError] so the calling handler can
     * surface it via a structured envelope. T63 added — the
     * subcommand-based android-weather handler needs raw JSON to slice
     * into current/hourly/daily.
     */
    @Volatile var lastError: String = ""
        private set

    fun fetchWeatherJson(latitude: Double, longitude: Double): JSONObject? {
        val url = "$BASE_URL?" +
            "latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
            "weather_code,wind_speed_10m,wind_direction_10m" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,sunrise,sunset" +
            "&forecast_hours=48" +
            "&forecast_days=10" +
            "&timezone=auto"

        val request = Request.Builder().url(url).get().build()
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) {
                lastError = "Open-Meteo API returned ${response.code}: ${body ?: "no body"}"
                null
            } else {
                lastError = ""
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch failed", e)
            lastError = e.message ?: "unknown network error"
            null
        }
    }

    /**
     * WMO Weather interpretation codes to human-readable descriptions.
     * https://open-meteo.com/en/docs#weathervariables
     *
     * [debt-1026 #5] `internal`, not duplicated: the sandbox `android-weather`
     * handler builds its JSON `conditions` fields from this same table. A second
     * copy in the handler is how the two paths could drift apart (the handler's
     * copy carried the comment "Duplicated from WeatherManager").
     */
    internal fun weatherCodeToDescription(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Foggy"
        48 -> "Depositing rime fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Dense drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Dense freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Slight snow"
        73 -> "Moderate snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Slight rain showers"
        81 -> "Moderate rain showers"
        82 -> "Violent rain showers"
        85 -> "Slight snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96 -> "Thunderstorm with slight hail"
        99 -> "Thunderstorm with heavy hail"
        else -> "Unknown (code $code)"
    }
}
