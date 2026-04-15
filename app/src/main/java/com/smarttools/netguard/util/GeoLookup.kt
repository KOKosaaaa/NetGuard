package com.smarttools.netguard.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

object GeoLookup {

    data class LatLon(val lat: Double, val lon: Double)

    private val ipCache = ConcurrentHashMap<String, LatLon>()
    private val negativeLookups = ConcurrentHashMap.newKeySet<String>()

    private val COUNTRY_COORDS = mapOf(
        "US" to LatLon(39.8, -98.6),
        "GB" to LatLon(51.5, -0.1),
        "UK" to LatLon(51.5, -0.1),
        "DE" to LatLon(51.2, 10.4),
        "FR" to LatLon(46.6, 2.2),
        "NL" to LatLon(52.1, 5.3),
        "RU" to LatLon(55.8, 37.6),
        "JP" to LatLon(36.2, 138.3),
        "SG" to LatLon(1.4, 103.8),
        "AU" to LatLon(-25.3, 133.8),
        "CA" to LatLon(56.1, -106.3),
        "BR" to LatLon(-14.2, -51.9),
        "IN" to LatLon(20.6, 78.9),
        "KR" to LatLon(35.9, 127.8),
        "TR" to LatLon(39.0, 35.2),
        "SE" to LatLon(60.1, 18.6),
        "FI" to LatLon(61.9, 25.7),
        "CH" to LatLon(46.8, 8.2),
        "PL" to LatLon(51.9, 19.1),
        "UA" to LatLon(48.4, 31.2),
        "IT" to LatLon(41.9, 12.5),
        "ES" to LatLon(40.5, -3.7),
        "HK" to LatLon(22.3, 114.2),
        "TW" to LatLon(23.7, 120.9),
        "IE" to LatLon(53.1, -7.7),
        "AT" to LatLon(47.5, 14.6),
        "CZ" to LatLon(49.8, 15.5),
        "RO" to LatLon(45.9, 25.0),
        "NO" to LatLon(60.5, 8.5),
        "DK" to LatLon(56.3, 9.5),
        "IS" to LatLon(64.1, -18.0),
        "LV" to LatLon(56.9, 24.1),
        "BG" to LatLon(42.7, 25.5),
        "LU" to LatLon(49.8, 6.1),
        "MX" to LatLon(23.6, -102.5),
        "AR" to LatLon(-38.4, -63.6),
        "ZA" to LatLon(-30.6, 22.9),
        "AE" to LatLon(23.4, 53.8),
        "IL" to LatLon(31.0, 34.9),
        "KZ" to LatLon(48.0, 68.0),
    )

    private val CITY_TO_CODE = mapOf(
        "NEW YORK" to "US", "LOS ANGELES" to "US", "CHICAGO" to "US",
        "MIAMI" to "US", "DALLAS" to "US", "SEATTLE" to "US", "ATLANTA" to "US",
        "SAN JOSE" to "US", "WASHINGTON" to "US", "SILICON" to "US",
        "LONDON" to "GB", "MANCHESTER" to "GB",
        "FRANKFURT" to "DE", "BERLIN" to "DE", "MUNICH" to "DE", "DUSSELDORF" to "DE",
        "PARIS" to "FR", "MARSEILLE" to "FR",
        "AMSTERDAM" to "NL", "ROTTERDAM" to "NL",
        "MOSCOW" to "RU", "SAINT PETERSBURG" to "RU", "SPB" to "RU",
        "TOKYO" to "JP", "OSAKA" to "JP",
        "SINGAPORE" to "SG",
        "SYDNEY" to "AU", "MELBOURNE" to "AU",
        "TORONTO" to "CA", "MONTREAL" to "CA", "VANCOUVER" to "CA",
        "SAO PAULO" to "BR",
        "MUMBAI" to "IN", "DELHI" to "IN", "BANGALORE" to "IN",
        "SEOUL" to "KR",
        "ISTANBUL" to "TR",
        "STOCKHOLM" to "SE",
        "HELSINKI" to "FI",
        "ZURICH" to "CH", "GENEVA" to "CH",
        "WARSAW" to "PL",
        "KYIV" to "UA", "KIEV" to "UA",
        "ROME" to "IT", "MILAN" to "IT",
        "MADRID" to "ES", "BARCELONA" to "ES",
        "HONG KONG" to "HK",
        "TAIPEI" to "TW",
        "DUBLIN" to "IE",
        "VIENNA" to "AT",
        "PRAGUE" to "CZ",
        "BUCHAREST" to "RO",
        "OSLO" to "NO",
        "COPENHAGEN" to "DK",
        "REYKJAVIK" to "IS",
        "RIGA" to "LV",
        "SOFIA" to "BG",
        "LUXEMBOURG" to "LU",
        "DUBAI" to "AE",
        "TEL AVIV" to "IL",
        // Russian city names
        "МОСКВА" to "RU", "САНКТ-ПЕТЕРБУРГ" to "RU", "ПЕТЕРБУРГ" to "RU",
        "НЬЮ-ЙОРК" to "US", "ЛОС-АНДЖЕЛЕС" to "US",
        "ЛОНДОН" to "GB",
        "ФРАНКФУРТ" to "DE", "БЕРЛИН" to "DE",
        "ПАРИЖ" to "FR",
        "АМСТЕРДАМ" to "NL",
        "ТОКИО" to "JP",
        "СТАМБУЛ" to "TR",
        "СТОКГОЛЬМ" to "SE",
        "ХЕЛЬСИНКИ" to "FI",
        "ВАРШАВА" to "PL",
        "КИЕВ" to "UA",
        "ВЕНА" to "AT",
        "ПРАГА" to "CZ",
        "МАДРИД" to "ES",
        "РИМ" to "IT", "МИЛАН" to "IT",
        "ДУБЛИН" to "IE",
        "СИДНЕЙ" to "AU",
        "ТОРОНТО" to "CA",
        "СЕУЛ" to "KR",
        "МУМБАИ" to "IN",
        "ДУБАЙ" to "AE",
    )

    private val NAME_TO_CODE = mapOf(
        // English
        "UNITED STATES" to "US", "AMERICA" to "US", "USA" to "US",
        "UNITED KINGDOM" to "GB", "BRITAIN" to "GB", "ENGLAND" to "GB",
        "GERMANY" to "DE", "DEUTSCHLAND" to "DE",
        "FRANCE" to "FR",
        "NETHERLANDS" to "NL", "HOLLAND" to "NL",
        "RUSSIA" to "RU",
        "JAPAN" to "JP",
        "SINGAPORE" to "SG",
        "AUSTRALIA" to "AU",
        "CANADA" to "CA",
        "BRAZIL" to "BR",
        "INDIA" to "IN",
        "KOREA" to "KR", "SOUTH KOREA" to "KR",
        "TURKEY" to "TR", "TURKIYE" to "TR",
        "SWEDEN" to "SE",
        "FINLAND" to "FI",
        "SWITZERLAND" to "CH",
        "POLAND" to "PL",
        "UKRAINE" to "UA",
        "ITALY" to "IT",
        "SPAIN" to "ES",
        "IRELAND" to "IE",
        "AUSTRIA" to "AT",
        "NORWAY" to "NO",
        "DENMARK" to "DK",
        "ICELAND" to "IS",
        "MEXICO" to "MX",
        // Russian
        "США" to "US", "АМЕРИКА" to "US",
        "ВЕЛИКОБРИТАНИЯ" to "GB", "АНГЛИЯ" to "GB",
        "ГЕРМАНИЯ" to "DE",
        "ФРАНЦИЯ" to "FR",
        "НИДЕРЛАНДЫ" to "NL", "ГОЛЛАНДИЯ" to "NL",
        "РОССИЯ" to "RU",
        "ЯПОНИЯ" to "JP",
        "СИНГАПУР" to "SG",
        "АВСТРАЛИЯ" to "AU",
        "КАНАДА" to "CA",
        "БРАЗИЛИЯ" to "BR",
        "ИНДИЯ" to "IN",
        "КОРЕЯ" to "KR", "ЮЖНАЯ КОРЕЯ" to "KR",
        "ТУРЦИЯ" to "TR",
        "ШВЕЦИЯ" to "SE",
        "ФИНЛЯНДИЯ" to "FI",
        "ШВЕЙЦАРИЯ" to "CH",
        "ПОЛЬША" to "PL",
        "УКРАИНА" to "UA",
        "ИТАЛИЯ" to "IT",
        "ИСПАНИЯ" to "ES",
        "ИРЛАНДИЯ" to "IE",
        "АВСТРИЯ" to "AT",
        "НОРВЕГИЯ" to "NO",
        "ДАНИЯ" to "DK",
        "ИСЛАНДИЯ" to "IS",
        "МЕКСИКА" to "MX",
        "ИЗРАИЛЬ" to "IL",
        "ОАЭ" to "AE", "ЭМИРАТЫ" to "AE",
        "ГОНКОНГ" to "HK",
        "ТАЙВАНЬ" to "TW",
        "ЧЕХИЯ" to "CZ",
        "РУМЫНИЯ" to "RO",
        "БОЛГАРИЯ" to "BG",
        "ЛАТВИЯ" to "LV",
        "ЛЮКСЕМБУРГ" to "LU",
        "КАЗАХСТАН" to "KZ",
    )

    fun fromProfileName(name: String): LatLon? {
        val code = countryCodeFromName(name) ?: return null
        return COUNTRY_COORDS[code]
    }

    /**
     * Extract 2-letter country code from profile name.
     * Returns null if country cannot be determined.
     */
    fun countryCodeFromName(name: String): String? {
        val upper = name.uppercase()
        // Try 2-letter code at start: "US-NewYork", "DE Frankfurt", "[NL]"
        for (code in COUNTRY_COORDS.keys) {
            if (upper.startsWith("$code-") || upper.startsWith("$code ") ||
                upper.startsWith("[$code]") || upper == code) {
                return code
            }
        }
        // Try city names
        for ((city, code) in CITY_TO_CODE) {
            if (upper.contains(city)) return code
        }
        // Try country names
        for ((countryName, code) in NAME_TO_CODE) {
            if (upper.contains(countryName)) return code
        }
        return null
    }

    /**
     * Lookup server location by IP address.
     * Tries ip-api.com first, then ipwho.is as fallback.
     * Caches results. Returns null on failure.
     */
    fun fromIp(ip: String): LatLon? {
        ipCache[ip]?.let { return it }
        if (negativeLookups.contains(ip)) return null
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")) return null

        // If it's a hostname (not an IP), resolve it first
        val resolvedIp = if (ip.any { it.isLetter() }) {
            try {
                java.net.InetAddress.getByName(ip).hostAddress ?: ip
            } catch (e: Exception) {
                Log.w("GeoLookup", "Failed to resolve hostname $ip: ${e.message}")
                ip
            }
        } else ip

        // Check cache for resolved IP too
        if (resolvedIp != ip) {
            ipCache[resolvedIp]?.let {
                ipCache[ip] = it
                return it
            }
        }

        // ipwho.is first (HTTPS), ip-api.com fallback (HTTP — only works if cleartext allowed)
        val result = tryIpWhoIs(resolvedIp) ?: tryIpApi(resolvedIp)
        if (result != null) {
            ipCache[ip] = result
            if (resolvedIp != ip) ipCache[resolvedIp] = result
        } else {
            negativeLookups.add(ip)
        }
        return result
    }

    private fun tryIpApi(ip: String): LatLon? {
        return try {
            val conn = URL("http://ip-api.com/json/$ip?fields=status,lat,lon").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(json)
            if (obj.optString("status") == "success") {
                LatLon(obj.getDouble("lat"), obj.getDouble("lon"))
            } else null
        } catch (e: Exception) {
            Log.w("GeoLookup", "ip-api.com failed for $ip: ${e.message}")
            null
        }
    }

    private fun tryIpWhoIs(ip: String): LatLon? {
        return try {
            val conn = URL("https://ipwho.is/$ip?fields=success,latitude,longitude").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false)) {
                LatLon(obj.getDouble("latitude"), obj.getDouble("longitude"))
            } else null
        } catch (e: Exception) {
            Log.w("GeoLookup", "ipwho.is failed for $ip: ${e.message}")
            null
        }
    }

    @Volatile
    private var cachedUserLocation: LatLon? = null
    @Volatile
    private var cachedTimestamp: Long = 0L
    @Volatile
    private var appCtx: Context? = null

    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    /**
     * Initialize with Application context. Loads persisted user location.
     */
    fun init(context: Context) {
        appCtx = context.applicationContext
        val prefs = appCtx!!.getSharedPreferences("geo_cache", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("user_lat", Float.MIN_VALUE).toDouble()
        val lon = prefs.getFloat("user_lon", Float.MIN_VALUE).toDouble()
        cachedTimestamp = prefs.getLong("user_geo_ts", 0L)
        if (lat != Float.MIN_VALUE.toDouble()) {
            cachedUserLocation = LatLon(lat, lon)
            Log.d("GeoLookup", "Loaded cached user location: $lat, $lon (age: ${(System.currentTimeMillis() - cachedTimestamp) / 3_600_000}h)")
        }
    }

    /**
     * Get user's location. Returns cached IP geolocation result,
     * or falls back to timezone-based estimate.
     */
    fun getUserLocation(): LatLon {
        cachedUserLocation?.let { return it }
        val tz = TimeZone.getDefault()
        val offsetHours = tz.rawOffset / 3_600_000.0
        val lon = offsetHours * 15.0
        return LatLon(50.0, lon)
    }

    /**
     * Fetch user location via IP geolocation (call from IO thread).
     * Persists the result for future sessions. Re-fetches if older than 7 days.
     */
    fun fetchUserLocation(): LatLon? {
        val now = System.currentTimeMillis()
        val stale = now - cachedTimestamp > REFRESH_INTERVAL_MS
        if (cachedUserLocation != null && !stale) return cachedUserLocation

        val result = tryIpWhoIsSelf() ?: tryIpApiSelf()
        if (result != null) {
            cachedUserLocation = result
            cachedTimestamp = now
            appCtx?.let { ctx ->
                ctx.getSharedPreferences("geo_cache", Context.MODE_PRIVATE).edit()
                    .putFloat("user_lat", result.lat.toFloat())
                    .putFloat("user_lon", result.lon.toFloat())
                    .putLong("user_geo_ts", now)
                    .apply()
            }
            Log.d("GeoLookup", "Fetched user location: ${result.lat}, ${result.lon}")
        }
        return result ?: cachedUserLocation
    }

    private fun tryIpApiSelf(): LatLon? {
        return try {
            val conn = URL("http://ip-api.com/json/?fields=status,lat,lon").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(json)
            if (obj.optString("status") == "success") {
                LatLon(obj.getDouble("lat"), obj.getDouble("lon"))
            } else null
        } catch (e: Exception) { null }
    }

    private fun tryIpWhoIsSelf(): LatLon? {
        return try {
            val conn = URL("https://ipwho.is/?fields=success,latitude,longitude").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false)) {
                LatLon(obj.getDouble("latitude"), obj.getDouble("longitude"))
            } else null
        } catch (e: Exception) { null }
    }
}
