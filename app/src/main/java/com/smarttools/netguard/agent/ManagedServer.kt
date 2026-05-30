package com.smarttools.netguard.agent

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per VPS that this Android device controls through netguard-agent.
 *
 * `bearer` is stored in plaintext: Android keeps each app's databases inside
 * `/data/data/<pkg>/databases/` with directory mode 0700, owned by the app's
 * UID, which means only root can read it. That's an acceptable bar for v1;
 * a Keystore-wrapped AES envelope (rotating the wrapping key under the
 * lock-screen) lands in phase 2 hardening — too much UX surface to design
 * before the basic flow ships.
 *
 * `spkiPin` is the sha256 of the agent's SubjectPublicKeyInfo (DER). We
 * pin it (not the cert itself) so we can rotate certs server-side without
 * forcing a re-pair, as long as the private key is preserved.
 */
@Entity(tableName = "managed_servers")
data class ManagedServer(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** User-facing label, e.g. "Home VPN" / "Aeza Frankfurt". */
    val name: String,
    /** Hostname or IPv4. Used only in legacy direct mode (when endpointUrl is empty). */
    val host: String,
    val port: Int = 9443,
    /** Long-lived bearer issued by /v1/auth/pair. */
    val bearer: String,
    /**
     * Hex-encoded sha256 of SubjectPublicKeyInfo. Empty when endpointUrl is
     * set (CF Tunnel mode — TLS terminated at the CF edge, no per-agent
     * cert pinning; bearer is the only auth).
     */
    val spkiPin: String,
    /**
     * Full base URL (e.g. `https://hel-agent.kvpn.online`) when the agent
     * sits behind a Cloudflare Tunnel. Empty string = legacy direct
     * `https://host:port` mode with SPKI pinning.
     */
    val endpointUrl: String = "",
    /** Last agent version we saw in /v1/health or /v1/status. */
    val agentVersion: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = 0L,
    /** Cached snapshot for the list cell — refreshed on background polls. */
    val lastLoadAvg1: Float = 0f,
    val lastMemUsedMb: Int = 0,
    val lastMemTotalMb: Int = 0,
    /**
     * Bearer expiry from /v1/auth/pair. We start nudging the user (or
     * auto-rotating) when within 7 days of this — matches the server's
     * RotateWindow constant.
     */
    val bearerExpiresAt: Long = 0L,
)
