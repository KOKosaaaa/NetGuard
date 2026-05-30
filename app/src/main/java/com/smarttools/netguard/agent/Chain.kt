package com.smarttools.netguard.agent

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per multi-hop chain the user has created through the wizard.
 * The chain itself isn't a deploy unit on any server — it's a virtual
 * grouping of the per-server xray inbounds that were wired up together
 * via chain_to. We persist it so the "Delete chain" action can tear
 * down every hop in one go, and so the user can review what they made.
 *
 * `entryProfileUri` is the vless URI the orchestrator imported into the
 * regular Servers tab. We keep it for two reasons: the Routes list can
 * show "→ ..." to remind the user which subscription line corresponds
 * to this chain, and we use it on delete to drop the matching
 * ServerProfile row without relying on label collisions.
 */
@Entity(tableName = "chains")
data class ChainEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val createdAt: Long,
    val entryProfileUri: String,
)

/**
 * One row per hop in a chain, ordered by [position] (0 = entry, last =
 * exit). The cascade ON DELETE keeps the table coherent if the parent
 * chain is removed via [com.smarttools.netguard.agent.ChainRepository.delete].
 *
 * We snapshot [serverName] at create-time so the Routes list can still
 * render a sensible label even after the user renames or removes the
 * underlying managed server.
 */
@Entity(
    tableName = "chain_hops",
    foreignKeys = [
        ForeignKey(
            entity = ChainEntity::class,
            parentColumns = ["id"],
            childColumns = ["chainId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("chainId")],
)
data class ChainHopEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chainId: Long,
    val serverId: Long,
    val serverName: String,
    val inboundId: String,
    val position: Int,
)

/** Aggregate type used by the Routes-list query — chain + its hops in order. */
data class ChainWithHops(
    val chain: ChainEntity,
    val hops: List<ChainHopEntity>,
)
