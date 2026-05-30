package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.smarttools.netguard.R
import com.smarttools.netguard.databinding.BottomSheetCreateProfileBinding

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

        // Simple mode hides the SNI picker entirely and uses a safe default
        // (Cloudflare works in both directions). Choosing a masquerade domain
        // is meaningless to a non-technical user.
        val expert = (requireActivity().application as com.smarttools.netguard.App)
            .loadSettings().expertMode
        b.blockSni.visibility = if (expert) View.VISIBLE else View.GONE

        // Chip → SNI mapping. The "custom" chip toggles the EditText
        // beneath the group; everything else hides it.
        // International SNIs at the top — they work in BOTH directions
        // (RF→EU server, EU→RF server). Russian SNIs work only when the
        // VPS itself sits in Russia; on a foreign VPS, RF carriers' DPI
        // can mangle TLS ClientHello when SNI looks like a domestic site
        // routed abroad (observed empirically: MTS → Aeza HEL with
        // sni=music.yandex.ru produces "failed to read client hello").
        val sniByChipId = mapOf(
            R.id.chip_cloudflare to "www.cloudflare.com",
            R.id.chip_microsoft to "www.microsoft.com",
            R.id.chip_apple to "www.apple.com",
            R.id.chip_amazon to "www.amazon.com",
            R.id.chip_github to "github.com",
            R.id.chip_vk to "vk.com",
            R.id.chip_yamus to "music.yandex.ru",
            R.id.chip_ozon to "ozon.ru",
        )
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
