package com.smarttools.netguard.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

object GeoLookup {

    data class LatLon(val lat: Double, val lon: Double)

    private val ipCache = GeoResultCache<LatLon>()
    private val tunnelCache = GeoResultCache<LatLon>()

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
    private fun token(name: String, value: String) = Regex("(?<![\\p{L}\\p{N}])" +
        Regex.escape(value) + "(?![\\p{L}\\p{N}])").containsMatchIn(name)

    fun countryCodeFromName(name: String): String? {
        val upper = name.uppercase(java.util.Locale.ROOT)
        val points = name.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            if (points[i] in 0x1F1E6..0x1F1FF && points[i+1] in 0x1F1E6..0x1F1FF) {
                val code = "${('A'.code + points[i] - 0x1F1E6).toChar()}${('A'.code + points[i+1] - 0x1F1E6).toChar()}"
                if (code in COUNTRY_COORDS) return code
            }
        }
        val codes = Regex("(?<![\\p{L}\\p{N}])([A-Z]{2})(?:[0-9]{1,3})?(?![\\p{L}\\p{N}])")
        codes.findAll(upper).forEach { if (it.groupValues[1] in COUNTRY_COORDS) return it.groupValues[1] }
        val airports = mapOf("HEL" to "FI", "FRA" to "DE", "AMS" to "NL", "LON" to "GB", "NYC" to "US", "TYO" to "JP")
        airports.forEach { (word,code) -> if(token(upper,word)) return code }
        CITY_TO_CODE.forEach { (city,code) -> if(token(upper,city)) return code }
        NAME_TO_CODE.forEach { (country,code) -> if(token(upper,country)) return code }
        return null
    }

    /**
     * Lookup server location by IP address via ipwho.is (HTTPS only).
     * Caches results. Returns null on failure.
     */
    fun fromIp(ip: String): LatLon? = ipCache.lookup(ip) {
        val resolved = try { java.net.InetAddress.getByName(ip.trim().removeSurrounding("[", "]")) }
            catch (_: Exception) { return@lookup null }
        if (resolved.isAnyLocalAddress || resolved.isLoopbackAddress || resolved.isSiteLocalAddress || resolved.isLinkLocalAddress)
            return@lookup null
        val address = resolved.hostAddress ?: return@lookup null
        tryIpWhoIs(address) ?: tryIpApi(address)
    }

    /** Resolve the actual exit, rather than the CDN/meeting platform's location. */
    internal fun fromTunnel(proxy: com.smarttools.netguard.core.CredentialManager.SpeedProxy): LatLon? =
        tunnelCache.lookup("${proxy.generation}:${proxy.endpoint.port}") {
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy.NO_PROXY)
                .socketFactory(TunnelSpeedSocketFactory(proxy.endpoint))
                .dns(object : okhttp3.Dns {
                    override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByAddress(hostname, byteArrayOf(127,0,0,1)))
                })
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
            try {
                for (url in listOf("https://ipwho.is/?fields=success,latitude,longitude", "https://ipapi.co/json/")) {
                    val result = runCatching {
                        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                            if (!response.isSuccessful) return@use null
                            val body = response.body ?: return@use null
                            val source = body.source()
                            if (source.request(65537)) return@use null
                            val obj = JSONObject(source.readUtf8())
                            if (obj.optBoolean("error",false) || obj.has("success") && !obj.optBoolean("success")) null else coordinates(obj)
                        }
                    }.getOrNull()
                    if (result != null && com.smarttools.netguard.core.CredentialManager.isCurrent(proxy)) return@lookup result
                }
                null
            } finally {client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()}
        }

    private fun coordinates(obj: JSONObject): LatLon? {
        val lat = obj.optDouble("latitude",Double.NaN); val lon = obj.optDouble("longitude",Double.NaN)
        return if(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat,lon) else null
    }

    private fun tryIpApi(ip: String): LatLon? {
        val conn = URL("https://ipapi.co/$ip/json/").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout=3000;conn.readTimeout=3000
            conn.inputStream.bufferedReader().use {reader ->
                val chars=CharArray(65537);var n=0
                while(n<chars.size) {val read=reader.read(chars,n,chars.size-n);if(read<0)break;n+=read}
                if(n>=chars.size)return null
                val obj=JSONObject(String(chars,0,n))
                if(obj.optBoolean("error",false))null else coordinates(obj)
            }
        }catch(_:Exception){null}finally{conn.disconnect()}
    }

    /**
     * 2-letter ISO country code (e.g. "RU") for an IP or hostname via
     * ipwho.is, or null on failure. Blocking — call from a background
     * dispatcher. Used to tailor the Reality SNI list to where the server
     * physically sits: RF-site SNIs (vk.com, music.yandex.ru) only pass DPI
     * when the VPS is in Russia; on a foreign VPS they get mangled.
     */
    fun countryFromIp(ip: String): String? {
        val resolved = if (ip.any { it.isLetter() }) {
            try {
                java.net.InetAddress.getByName(ip).hostAddress ?: return null
            } catch (e: Exception) {
                Log.w("GeoLookup", "resolve $ip failed: ${e.message}")
                return null
            }
        } else ip
        return try {
            val conn = URL("https://ipwho.is/$resolved?fields=success,country_code")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val obj = JSONObject(json)
            if (obj.optBoolean("success", false))
                obj.optString("country_code").takeIf { it.isNotEmpty() }
            else null
        } catch (e: Exception) {
            Log.w("GeoLookup", "country lookup failed for $ip: ${e.message}")
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
                coordinates(obj)
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
    // Application context only (see init: context.applicationContext) — lives
    // for the whole process, so this is not the Activity/View leak lint warns
    // about. Suppress the false positive rather than thread ctx through every call.
    @Volatile
    @SuppressLint("StaticFieldLeak")
    private var appCtx: Context? = null

    private const val REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

    /**
     * Initialize with Application context. Loads persisted user location.
     * Cache is stored in EncryptedSharedPreferences — coordinates are PII and
     * a privileged co-resident process / root could otherwise read them in
     * plaintext from /data/data/<pkg>/shared_prefs/.
     * One-time migration from the legacy plaintext "geo_cache" prefs runs on
     * first init.
     */
    fun init(context: Context) {
        appCtx = context.applicationContext
        val prefs = encryptedPrefs(appCtx!!)
        migrateLegacyGeoCache(appCtx!!, prefs)
        val lat = prefs.getFloat("user_lat", Float.MIN_VALUE).toDouble()
        val lon = prefs.getFloat("user_lon", Float.MIN_VALUE).toDouble()
        cachedTimestamp = prefs.getLong("user_geo_ts", 0L)
        if (lat != Float.MIN_VALUE.toDouble()) {
            cachedUserLocation = LatLon(lat, lon)
            Log.d("GeoLookup", "Loaded cached user location: $lat, $lon (age: ${(System.currentTimeMillis() - cachedTimestamp) / 3_600_000}h)")
        }
    }

    private fun encryptedPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            "geo_cache_enc",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateLegacyGeoCache(ctx: Context, target: SharedPreferences) {
        val legacy = ctx.getSharedPreferences("geo_cache", Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val lat = legacy.getFloat("user_lat", Float.MIN_VALUE)
        val lon = legacy.getFloat("user_lon", Float.MIN_VALUE)
        val ts = legacy.getLong("user_geo_ts", 0L)
        if (lat != Float.MIN_VALUE) {
            target.edit()
                .putFloat("user_lat", lat)
                .putFloat("user_lon", lon)
                .putLong("user_geo_ts", ts)
                .apply()
        }
        // Wipe plaintext copy
        legacy.edit().clear().apply()
        // Best-effort: also drop the file from disk so the plaintext blob is gone
        try {
            ctx.deleteSharedPreferences("geo_cache")
        } catch (_: Throwable) { /* API 24+ only, harmless on older */ }
        Log.i("GeoLookup", "Migrated geo_cache to EncryptedSharedPreferences")
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

        // HTTPS only — see fromIp() comment about HTTP fallback removal.
        val result = tryIpWhoIsSelf()
        if (result != null) {
            cachedUserLocation = result
            cachedTimestamp = now
            appCtx?.let { ctx ->
                encryptedPrefs(ctx).edit()
                    .putFloat("user_lat", result.lat.toFloat())
                    .putFloat("user_lon", result.lon.toFloat())
                    .putLong("user_geo_ts", now)
                    .apply()
            }
            Log.d("GeoLookup", "Fetched user location: ${result.lat}, ${result.lon}")
        }
        return result ?: cachedUserLocation
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
                coordinates(obj)
            } else null
        } catch (e: Exception) { null }
    }
}
