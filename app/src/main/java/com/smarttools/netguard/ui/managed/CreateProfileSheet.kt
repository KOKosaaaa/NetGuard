package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.BottomSheetCreateProfileBinding
import com.smarttools.netguard.util.GeoLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two-step wizard for creating an xray inbound on a managed server.
 *
 * Step 1: protocol picker — VLESS+REALITY (always-on) vs Telemost
 *   (disabled placeholder; agent stub still returns 501).
 * Step 2 (auto-shown for VLESS): SNI preset chips with a "Custom…" tail
 *   and an optional friendly label.
 *
 * On "Create" we call [ManagedServerDetailViewModel.addProfile] with the
 * chosen SNI and dismiss; the parent fragment then observes
 * `lastCreatedUri` to show the "вот ссылка" success card.
 */
class CreateProfileSheet : BottomSheetDialogFragment() {

    private var _b: BottomSheetCreateProfileBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = BottomSheetCreateProfileBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Tapping the Telemost card swaps the wizard mid-flight: we
        // dismiss the VLESS sheet, then show the Telemost one. Sharing
        // a sheet would mean toggling visibility on a lot of views;
        // separate sheets keep each flow simple.
        b.cardVless.setOnClickListener { /* already selected */ }
        b.cardTelemost.setOnClickListener {
            CreateTelemostSheet().show(parentFragmentManager, CreateTelemostSheet.TAG)
            dismiss()
        }

        // Chip → SNI domain. Cloudflare is universal (works in both
        // directions) and stays the default. The others split into Russian
        // and international sites: a Russian SNI only passes RF DPI when the
        // server itself sits in Russia, and an international one is the
        // believable choice on a foreign server (observed: MTS → Aeza HEL
        // with sni=music.yandex.ru → "failed to read client hello").
        val sniByChipId = mapOf(
            R.id.chip_cloudflare to "www.cloudflare.com",
            R.id.chip_microsoft to "www.microsoft.com",
            R.id.chip_apple to "www.apple.com",
            R.id.chip_amazon to "www.amazon.com",
            R.id.chip_github to "github.com",
            R.id.chip_steam to "steamcommunity.com",
            R.id.chip_vk to "vk.com",
            R.id.chip_yamus to "music.yandex.ru",
            R.id.chip_ozon to "ozon.ru",
            R.id.chip_max to "max.ru",
            R.id.chip_rutube to "rutube.ru",
        )
        val rfChips = listOf(
            R.id.chip_vk, R.id.chip_yamus, R.id.chip_ozon, R.id.chip_max, R.id.chip_rutube,
        )
        val intlChips = listOf(
            R.id.chip_microsoft, R.id.chip_apple, R.id.chip_amazon, R.id.chip_github, R.id.chip_steam,
        )
        fun showSniGroup(russian: Boolean) {
            rfChips.forEach {
                b.root.findViewById<View>(it).visibility = if (russian) View.VISIBLE else View.GONE
            }
            intlChips.forEach {
                b.root.findViewById<View>(it).visibility = if (russian) View.GONE else View.VISIBLE
            }
        }

        // Simple mode hides the SNI picker entirely and uses the universal
        // Cloudflare default — picking a masquerade domain is meaningless to a
        // non-technical user. Expert mode shows the picker, pre-filtered to the
        // server's country: RF sites for a Russian server, international
        // otherwise. We default to international while the geo lookup runs.
        val expert = (requireActivity().application as com.smarttools.netguard.App)
            .loadSettings().expertMode
        if (expert) {
            b.blockSni.visibility = View.VISIBLE
            showSniGroup(russian = false)
            vm.serverOrNull?.host?.let { host ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val cc = withContext(Dispatchers.IO) { GeoLookup.countryFromIp(host) }
                    if (_b != null && cc == "RU") showSniGroup(russian = true)
                }
            }
        } else {
            b.blockSni.visibility = View.GONE
        }

        b.cgSni.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull()
            b.tilCustomSni.visibility =
                if (id == R.id.chip_custom) View.VISIBLE else View.GONE
        }

        b.btnCreate.setOnClickListener {
            val sni = if (!expert) {
                "www.cloudflare.com" // Simple mode default
            } else {
                val checkedId = b.cgSni.checkedChipIds.firstOrNull()
                when {
                    checkedId == R.id.chip_custom ->
                        b.etCustomSni.text?.toString()?.trim().orEmpty()
                    checkedId != null -> sniByChipId[checkedId].orEmpty()
                    else -> ""
                }
            }
            if (sni.isEmpty() || sni.contains('/') || sni.contains(' ')) {
                b.tilCustomSni.error = "Введи корректный домен"
                return@setOnClickListener
            }
            val label = b.etLabel.text?.toString()?.trim().orEmpty()
            // port=0 → agent picks (443 for Reality)
            vm.addProfile(label = label, port = 0, serverName = sni)
            dismiss()
        }
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    companion object { const val TAG = "CreateProfileSheet" }
}
