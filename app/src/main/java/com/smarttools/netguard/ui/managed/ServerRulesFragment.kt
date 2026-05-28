package com.smarttools.netguard.ui.managed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.smarttools.netguard.R
import com.smarttools.netguard.agent.AddBypassOutboundRequest
import com.smarttools.netguard.agent.AddBypassRuleRequest
import com.smarttools.netguard.agent.BypassOutbound
import com.smarttools.netguard.agent.BypassRule
import com.smarttools.netguard.databinding.FragmentServerRulesBinding
import com.smarttools.netguard.databinding.ItemRuleRowBinding
import kotlinx.coroutines.launch

class ServerRulesFragment : Fragment() {

    private var _b: FragmentServerRulesBinding? = null
    private val b get() = _b!!
    private val vm: ManagedServerDetailViewModel by activityViewModels()

    private lateinit var outAdapter: OutboundAdapter
    private lateinit var ruleAdapter: RuleAdapter

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _b = FragmentServerRulesBinding.inflate(i, c, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        outAdapter = OutboundAdapter { o -> confirmDeleteOutbound(o) }
        ruleAdapter = RuleAdapter { r -> confirmDeleteRule(r) }

        b.rvOutbounds.layoutManager = LinearLayoutManager(requireContext())
        b.rvOutbounds.adapter = outAdapter
        b.rvRules.layoutManager = LinearLayoutManager(requireContext())
        b.rvRules.adapter = ruleAdapter

        b.btnAddOutbound.setOnClickListener { showAddOutboundDialog() }
        b.btnAddRule.setOnClickListener { showAddRuleDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.outbounds.collect { list ->
                        outAdapter.submitList(list)
                        b.tvNoOutbounds.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    vm.rules.collect { list ->
                        ruleAdapter.submitList(list)
                        b.tvNoRules.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun showAddOutboundDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etTag = EditText(ctx).apply { hint = getString(R.string.srv_upstream_tag_hint); container.addView(this) }
        val spType = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_dropdown_item, listOf("socks", "http"))
            container.addView(this)
        }
        val etHost = EditText(ctx).apply { hint = getString(R.string.srv_upstream_host_hint); container.addView(this) }
        val etPort = EditText(ctx).apply {
            hint = getString(R.string.srv_upstream_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }
        val etUser = EditText(ctx).apply { hint = getString(R.string.srv_upstream_user_hint); container.addView(this) }
        val etPass = EditText(ctx).apply {
            hint = getString(R.string.srv_upstream_pass_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD or android.text.InputType.TYPE_CLASS_TEXT
            container.addView(this)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_add_upstream)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                vm.addUpstream(
                    AddBypassOutboundRequest(
                        tag = etTag.text.toString().trim(),
                        type = spType.selectedItem as String,
                        host = etHost.text.toString().trim(),
                        port = etPort.text.toString().toIntOrNull() ?: 0,
                        username = etUser.text.toString().trim(),
                        password = etPass.text.toString(),
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddRuleDialog() {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val spKind = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("domain", "cidr", "geosite", "geoip"))
            container.addView(this)
        }
        val etVal = EditText(ctx).apply { hint = getString(R.string.srv_rule_value_hint); container.addView(this) }
        val actions = listOf("direct", "block", "via")
        val spAction = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, actions)
            container.addView(this)
        }
        // Upstream picker — only relevant when action=via.
        val upstreamTags = vm.outbounds.value.map { it.tag }
        val spVia = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx,
                android.R.layout.simple_spinner_dropdown_item,
                if (upstreamTags.isEmpty()) listOf("(no upstreams)") else upstreamTags)
            container.addView(this)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.srv_add_rule)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                val action = spAction.selectedItem as String
                val via = if (action == "via" && upstreamTags.isNotEmpty()) {
                    spVia.selectedItem as String
                } else ""
                vm.addRule(
                    AddBypassRuleRequest(
                        kind = spKind.selectedItem as String,
                        value = etVal.text.toString().trim(),
                        action = action,
                        viaOutboundTag = via,
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteOutbound(o: BypassOutbound) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage("Delete upstream \"${o.tag}\"?")
            .setPositiveButton(android.R.string.ok) { _, _ -> vm.deleteUpstream(o.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteRule(r: BypassRule) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage("Delete rule \"${r.kind} ${r.value} → ${r.action}\"?")
            .setPositiveButton(android.R.string.ok) { _, _ -> vm.deleteRule(r.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() { _b = null; super.onDestroyView() }

    private class OutboundAdapter(
        val onDelete: (BypassOutbound) -> Unit,
    ) : ListAdapter<BypassOutbound, OutboundAdapter.VH>(DIFF) {
        inner class VH(val b: ItemRuleRowBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(ItemRuleRowBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val o = getItem(pos)
            h.b.tvPrimary.text = "${o.tag} (${o.type})"
            h.b.tvSecondary.text = "${o.host}:${o.port}"
            h.b.btnDelete.setOnClickListener { onDelete(o) }
        }
        companion object {
            val DIFF = object : DiffUtil.ItemCallback<BypassOutbound>() {
                override fun areItemsTheSame(a: BypassOutbound, b: BypassOutbound) = a.id == b.id
                override fun areContentsTheSame(a: BypassOutbound, b: BypassOutbound) = a == b
            }
        }
    }

    private class RuleAdapter(
        val onDelete: (BypassRule) -> Unit,
    ) : ListAdapter<BypassRule, RuleAdapter.VH>(DIFF) {
        inner class VH(val b: ItemRuleRowBinding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(p: ViewGroup, vt: Int) =
            VH(ItemRuleRowBinding.inflate(LayoutInflater.from(p.context), p, false))
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = getItem(pos)
            h.b.tvPrimary.text = "${r.kind} ${r.value}"
            h.b.tvSecondary.text = "→ ${r.action}" +
                if (r.action == "via" && r.viaOutboundTag.isNotEmpty()) " (${r.viaOutboundTag})" else ""
            h.b.btnDelete.setOnClickListener { onDelete(r) }
        }
        companion object {
            val DIFF = object : DiffUtil.ItemCallback<BypassRule>() {
                override fun areItemsTheSame(a: BypassRule, b: BypassRule) = a.id == b.id
                override fun areContentsTheSame(a: BypassRule, b: BypassRule) = a == b
            }
        }
    }
}
