package com.smarttools.netguard.ui.profiles

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.ItemProfileBinding
import com.smarttools.netguard.databinding.ItemSubscriptionHeaderBinding
import com.smarttools.netguard.model.ServerProfile
import com.smarttools.netguard.model.Subscription
import com.smarttools.netguard.util.TrafficFormatter
import java.util.Collections

/**
 * Adapter for the Servers list. Shows two row types:
 *  - HEADER: subscription metadata (name + traffic + clickable web/support icons + announce)
 *  - PROFILE: a single server profile (existing item_profile.xml)
 *
 * Headers are inserted before the first profile of each subscription. Profiles
 * with subscriptionId=0 (manually-added) are shown without a header.
 *
 * Caller passes a flat profile list AND a Map<subId, Subscription>; the adapter
 * builds the mixed list internally.
 */
class ProfileAdapter(
    private val onItemClick: (ServerProfile) -> Unit,
    private val onItemLongClick: (ServerProfile) -> Unit,
    private val onPingClick: (ServerProfile) -> Unit,
    private val onFavoriteClick: (ServerProfile) -> Unit
) : ListAdapter<ProfileAdapter.Item, RecyclerView.ViewHolder>(DIFF) {

    /**
     * Like RelativeSizeSpan but also lifts the glyph's baseline so the larger
     * character stays vertically centered with the surrounding (smaller) text.
     * Plain RelativeSizeSpan keeps the baseline → enlarged glyph drops below.
     */
    class CenteredSizeSpan(private val scale: Float) :
        android.text.style.MetricAffectingSpan() {
        override fun updateMeasureState(p: android.text.TextPaint) = updateDrawState(p)
        override fun updateDrawState(p: android.text.TextPaint) {
            val origMetrics = p.fontMetrics
            val origCenter = (origMetrics.ascent + origMetrics.descent) / 2f
            p.textSize = p.textSize * scale
            val newMetrics = p.fontMetrics
            val newCenter = (newMetrics.ascent + newMetrics.descent) / 2f
            p.baselineShift += (origCenter - newCenter).toInt()
        }
    }

    sealed class Item {
        abstract val stableId: String
        data class Header(val sub: Subscription) : Item() {
            override val stableId = "h-${sub.id}"
        }
        data class Profile(val profile: ServerProfile) : Item() {
            override val stableId = "p-${profile.id}"
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PROFILE = 1

        private val DIFF = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(a: Item, b: Item) = a.stableId == b.stableId
            override fun areContentsTheSame(a: Item, b: Item) = a == b
        }
    }

    /** Mutable list for drag-reorder; mirror of ListAdapter's currentList. */
    private val mutableList = mutableListOf<Item>()
    private val listLock = Any()

    fun setData(profiles: List<ServerProfile>, subsById: Map<Long, Subscription>) {
        val items = mutableListOf<Item>()
        var prevSubId: Long = -1L
        for (p in profiles) {
            if (p.subscriptionId > 0 && p.subscriptionId != prevSubId) {
                subsById[p.subscriptionId]?.let { items.add(Item.Header(it)) }
            }
            items.add(Item.Profile(p))
            prevSubId = p.subscriptionId
        }
        synchronized(listLock) {
            mutableList.clear()
            mutableList.addAll(items)
            super.submitList(mutableList.toList())
        }
    }

    fun moveItem(from: Int, to: Int): Boolean {
        synchronized(listLock) {
            if (from < 0 || to < 0 || from >= mutableList.size || to >= mutableList.size) return false
            // Disallow moving headers and disallow moving across subscription boundaries.
            val src = mutableList[from] as? Item.Profile ?: return false
            val dst = mutableList[to]
            if (dst is Item.Header) return false
            if (dst is Item.Profile && dst.profile.subscriptionId != src.profile.subscriptionId) return false
            Collections.swap(mutableList, from, to)
            notifyItemMoved(from, to)
            return true
        }
    }

    /** For drag-to-reorder save: returns profiles in current visual order. */
    fun getReorderedList(): List<ServerProfile> = synchronized(listLock) {
        mutableList.mapNotNull { (it as? Item.Profile)?.profile }
    }

    fun itemAt(position: Int): Item? = synchronized(listLock) {
        mutableList.getOrNull(position)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.Header -> TYPE_HEADER
        is Item.Profile -> TYPE_PROFILE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(ItemSubscriptionHeaderBinding.inflate(inflater, parent, false))
            else -> ProfileVH(ItemProfileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as HeaderVH).bind(item.sub)
            is Item.Profile -> (holder as ProfileVH).bind(item.profile)
        }
    }

    // ---------- ViewHolders ----------

    inner class HeaderVH(private val b: ItemSubscriptionHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(sub: Subscription) {
            b.tvSubName.text = sub.name
            b.tvSubTraffic.text = formatTraffic(sub.usedBytes, sub.totalBytes)

            // Pick a stable color from group palette by subscription id
            val colorIdx = (sub.id % SubscriptionGroupDecoration.GROUP_COLORS.size).toInt()
            val color = SubscriptionGroupDecoration.GROUP_COLORS[colorIdx]
            b.tvSubName.setTextColor(color)

            // Progress: layered drawable (track + clipped fill). Tint both
            // layers via theme attrs, set fill level (0..10000) by fraction.
            tintAndFillCapsule(sub.usedBytes, sub.totalBytes)

            b.ivSubWeb.visibility = if (sub.webPageUrl.isNotBlank()) View.VISIBLE else View.GONE
            b.ivSubWeb.setOnClickListener { openUrl(sub.webPageUrl) }

            b.ivSubSupport.visibility = if (sub.supportUrl.isNotBlank()) View.VISIBLE else View.GONE
            b.ivSubSupport.setOnClickListener { openUrl(sub.supportUrl) }

            if (sub.announce.isNotBlank()) {
                b.tvSubAnnounce.visibility = View.VISIBLE
                b.tvSubAnnounce.text = sub.announce
            } else {
                b.tvSubAnnounce.visibility = View.GONE
            }
        }

        private fun tintAndFillCapsule(usedBytes: Long, totalBytes: Long) {
            val ctx = b.root.context
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(R.attr.capsuleTrackColor, tv, true)
            val trackColor = tv.data
            ctx.theme.resolveAttribute(R.attr.capsuleProgressColor, tv, true)
            val fillColor = tv.data

            // mutate() so tint applied here doesn't bleed to other ViewHolders
            // sharing the same drawable instance.
            val ld = b.tvSubTraffic.background.mutate() as android.graphics.drawable.LayerDrawable
            ld.findDrawableByLayerId(R.id.capsule_track)?.setTint(trackColor)
            val fillLayer = ld.findDrawableByLayerId(R.id.capsule_fill)
            fillLayer?.setTint(fillColor)
            b.tvSubTraffic.background = ld

            val fraction = if (totalBytes > 0)
                (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
            else 0.0
            fillLayer?.level = (fraction * 10000).toInt().coerceIn(0, 10000)
        }

        private fun openUrl(url: String) {
            if (url.isBlank()) return
            try {
                val ctx = b.root.context
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            } catch (_: Exception) { /* no browser / malformed URL — silent */ }
        }

        private fun formatTraffic(used: Long, total: Long): CharSequence {
            val usedStr = TrafficFormatter.formatBytes(used)
            if (total > 0) return "$usedStr / ${TrafficFormatter.formatBytes(total)}"
            // For "X / ∞" enlarge the infinity glyph slightly AND shift its
            // baseline so it stays vertically centered with the digits next to
            // it (RelativeSizeSpan alone would drop the larger glyph below the
            // baseline of the smaller text).
            val full = "$usedStr / \u221E"
            val span = android.text.SpannableString(full)
            val infIdx = full.length - 1
            span.setSpan(
                CenteredSizeSpan(1.4f),
                infIdx, full.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return span
        }
    }

    inner class ProfileVH(val binding: ItemProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: ServerProfile) {
            val b = binding
            val ctx = b.root.context

            b.tvName.text = profile.name.ifEmpty { "${profile.address}:${profile.port}" }
            b.tvAddress.text = "${profile.address}:${profile.port}"
            b.tvProtocol.text = profile.protocol.value.uppercase()

            when {
                profile.lastPingMs < 0 -> {
                    b.tvPing.text = "-"
                    b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                }
                profile.lastPingMs < 200 -> {
                    b.tvPing.text = "${profile.lastPingMs}ms"
                    b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_good))
                }
                profile.lastPingMs < 500 -> {
                    b.tvPing.text = "${profile.lastPingMs}ms"
                    b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_medium))
                }
                else -> {
                    b.tvPing.text = "${profile.lastPingMs}ms"
                    b.tvPing.setTextColor(ContextCompat.getColor(ctx, R.color.ping_bad))
                }
            }

            // Theme-tied attrs (defined in themes.xml) — keep cards readable on
            // both light and dark themes, plus distinct selection highlight.
            val attr = if (profile.isSelected) R.attr.cardSelectedBackground
                       else R.attr.cardNormalBackground
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            b.root.setCardBackgroundColor(tv.data)

            b.ivFavorite.setImageResource(
                if (profile.isFavorite) R.drawable.ic_star_filled
                else R.drawable.ic_star_outline
            )
            b.ivFavorite.setOnClickListener { onFavoriteClick(profile) }

            b.root.setOnClickListener { onItemClick(profile) }
            b.root.setOnLongClickListener {
                onItemLongClick(profile)
                true
            }
            b.tvPing.setOnClickListener { onPingClick(profile) }
        }
    }
}
