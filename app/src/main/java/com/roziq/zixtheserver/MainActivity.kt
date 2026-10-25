package com.roziq.zixtheserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.roziq.zixtheserver.databinding.ActivityMainBinding
import com.roziq.zixtheserver.databinding.DialogServerBinding
import com.roziq.zixtheserver.databinding.ItemServerBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: ServerStore
    private lateinit var adapter: ServerAdapter

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        store = ServerStore(this)
        adapter = ServerAdapter(::openServer, ::showMenu)
        b.rvServers.layoutManager = LinearLayoutManager(this)
        b.rvServers.adapter = adapter
        b.fabAdd.setOnClickListener { showEditor(null) }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = store.list()
        adapter.submit(list)
        b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openServer(s: Server) {
        startActivity(
            Intent(this, ServerActivity::class.java).putExtra(ServerActivity.EXTRA_ID, s.id)
        )
    }

    private fun showMenu(s: Server, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.menu_edit))
        popup.menu.add(0, 2, 1, getString(R.string.menu_reset_hostkey))
        popup.menu.add(0, 3, 2, getString(R.string.menu_delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showEditor(s)
                2 -> {
                    store.save(s.copy(hostKey = ""))
                    Toast.makeText(this, R.string.hostkey_reset_done, Toast.LENGTH_SHORT).show()
                }
                3 -> confirmDelete(s)
            }
            true
        }
        popup.show()
    }

    private fun confirmDelete(s: Server) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_delete)
            .setMessage(getString(R.string.confirm_delete, s.name))
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                store.delete(s.id)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditor(existing: Server?) {
        val d = DialogServerBinding.inflate(layoutInflater)
        if (existing != null) {
            d.etName.setText(existing.name)
            d.etHost.setText(existing.host)
            d.etPort.setText(existing.port.toString())
            d.etUser.setText(existing.username)
            d.etPass.setText(existing.password)
            d.etKey.setText(existing.privateKey)
        } else {
            d.etPort.setText("22")
            d.etUser.setText("root")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_server else R.string.menu_edit)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = d.etHost.text.toString().trim()
                if (host.isEmpty()) {
                    d.tilHost.error = getString(R.string.required)
                    return@setOnClickListener
                }
                d.tilHost.error = null

                val port = d.etPort.text.toString().toIntOrNull()
                    ?.takeIf { it in 1..65535 } ?: 22
                val hostChanged = existing != null &&
                    (existing.host != host || existing.port != port)

                val base = existing ?: Server()
                store.save(
                    base.copy(
                        name = d.etName.text.toString().trim().ifBlank { host },
                        host = host,
                        port = port,
                        username = d.etUser.text.toString().trim().ifBlank { "root" },
                        password = d.etPass.text.toString(),
                        privateKey = d.etKey.text.toString(),
                        hostKey = if (existing == null || hostChanged) "" else existing.hostKey
                    )
                )
                refresh()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}

class ServerAdapter(
    private val onClick: (Server) -> Unit,
    private val onMenu: (Server, View) -> Unit
) : RecyclerView.Adapter<ServerAdapter.VH>() {

    private var items: List<Server> = emptyList()

    fun submit(list: List<Server>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.b.tvName.text = s.name
        holder.b.tvAddr.text = "${s.username}@${s.host}:${s.port}"
        holder.b.root.setOnClickListener { onClick(s) }
        holder.b.btnMenu.setOnClickListener { onMenu(s, it) }
    }
}
