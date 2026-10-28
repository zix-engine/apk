package com.roziq.zixtheserver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.roziq.zixtheserver.databinding.ActivityFilesBinding
import com.roziq.zixtheserver.databinding.ItemFileBinding
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class RemoteEntry(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
    val perm: String,
    val link: Boolean
)

/** File manager SFTP: jelajah, unduh, unggah, edit teks, ganti nama, hapus. */
class FilesActivity : BaseActivity() {

    private lateinit var b: ActivityFilesBinding
    private lateinit var store: ServerStore
    private lateinit var server: Server
    private lateinit var adapter: FileAdapter

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var session: Session? = null
    @Volatile private var sftp: ChannelSftp? = null
    private var cwd = "/"
    private var firstList = true
    private var pendingDownload: String? = null

    private val downloadLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val remote = pendingDownload
            if (uri != null && remote != null) download(remote, uri)
        }

    private val uploadLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) upload(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ServerStore(this)
        val found = intent.getStringExtra(EXTRA_ID)?.let { store.get(it) }
        if (found == null) {
            finish()
            return
        }
        server = found
        b = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvTitle.text = getString(R.string.files_title, server.name)
        adapter = FileAdapter(::onClickEntry, ::onLongEntry)
        b.rvFiles.layoutManager = LinearLayoutManager(this)
        b.rvFiles.adapter = adapter
        b.rvFiles.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_fall_down)

        b.btnBack.setOnClickListener { goBack() }
        b.btnReload.setOnClickListener { load(cwd) }
        b.fabAdd.setOnClickListener { showAddMenu(it) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        load(null)
    }

    override fun onDestroy() {
        val ch = sftp
        val s = session
        thread(name = "sftp-close") {
            try {
                ch?.disconnect()
                s?.let { Ssh.disconnect(it) }
            } catch (_: Exception) {
            }
        }
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Util

    private fun join(dir: String, name: String) = if (dir == "/") "/$name" else "$dir/$name"

    private fun parent(dir: String): String {
        val p = dir.trimEnd('/').substringBeforeLast('/')
        return p.ifEmpty { "/" }
    }

    private fun goBack() {
        if (cwd != "/") load(parent(cwd)) else finish()
    }

    private fun busy(on: Boolean) {
        b.busy.visibility = if (on) View.VISIBLE else View.INVISIBLE
    }

    private fun ensureConnected(): ChannelSftp {
        sftp?.let { if (it.isConnected) return it }
        val s = store.open(server)
        val ch = s.openChannel("sftp") as ChannelSftp
        ch.connect(10_000)
        session = s
        sftp = ch
        return ch
    }

    /** Menjalankan operasi SFTP di thread tunggal, lalu [done] di UI thread. */
    private fun work(op: (ChannelSftp) -> Unit, done: (() -> Unit)? = null) {
        busy(true)
        try {
            io.execute {
                try {
                    op(ensureConnected())
                    onUi {
                        busy(false)
                        done?.invoke()
                    }
                } catch (e: Exception) {
                    val msg = Ssh.friendlyError(e)
                    onUi {
                        busy(false)
                        toast(msg)
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun size(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    // ------------------------------------------------------------ Daftar

    private fun load(path: String?) {
        var result: List<RemoteEntry> = emptyList()
        var resolved = path ?: cwd
        work({ ch ->
            if (path == null) resolved = ch.pwd()
            val v = ch.ls(resolved) as java.util.Vector<*>
            val out = ArrayList<RemoteEntry>()
            for (o in v) {
                val e = o as ChannelSftp.LsEntry
                if (e.filename == "." || e.filename == "..") continue
                val a = e.attrs
                var isDir = a.isDir
                if (a.isLink) {
                    isDir = try {
                        ch.stat(join(resolved, e.filename)).isDir
                    } catch (_: Exception) {
                        false
                    }
                }
                out.add(RemoteEntry(e.filename, isDir, a.size, a.getMTime() * 1000L, a.permissionsString ?: "", a.isLink))
            }
            result = out.sortedWith(compareBy<RemoteEntry>({ !it.dir }, { it.name.lowercase() }))
        }) {
            cwd = resolved
            b.tvPath.text = cwd
            val list = ArrayList<RemoteEntry>()
            if (cwd != "/") list.add(RemoteEntry("..", true, 0, 0, "", false))
            list.addAll(result)
            adapter.submitList(list) {
                if (firstList) {
                    firstList = false
                    b.rvFiles.scheduleLayoutAnimation()
                }
                b.rvFiles.scrollToPosition(0)
            }
        }
    }

    private fun onClickEntry(e: RemoteEntry) {
        when {
            e.name == ".." -> goBack()
            e.dir -> load(join(cwd, e.name))
            else -> showEntryMenu(e)
        }
    }

    private fun onLongEntry(e: RemoteEntry) {
        if (e.name != "..") showEntryMenu(e)
    }

    private fun showEntryMenu(e: RemoteEntry) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (!e.dir) {
            labels.add(getString(R.string.files_download)); actions.add { startDownload(e) }
            labels.add(getString(R.string.files_edit)); actions.add { edit(e) }
        }
        labels.add(getString(R.string.files_rename)); actions.add { rename(e) }
        labels.add(getString(R.string.menu_delete)); actions.add { confirmDelete(e) }
        labels.add(getString(R.string.files_copy_path)); actions.add {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("path", join(cwd, e.name)))
            toast(R.string.copied)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(e.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ------------------------------------------------------------ Aksi

    private fun startDownload(e: RemoteEntry) {
        pendingDownload = join(cwd, e.name)
        downloadLauncher.launch(e.name)
    }

    private fun download(remote: String, uri: Uri) {
        work({ ch ->
            contentResolver.openOutputStream(uri)?.use { out -> ch.get(remote, out) }
        }) { toast(R.string.files_download_done) }
    }

    private fun upload(uri: Uri) {
        var name = "upload"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) name = c.getString(i)
        }
        val target = join(cwd, name)
        work({ ch ->
            contentResolver.openInputStream(uri)?.use { input -> ch.put(input, target) }
        }) {
            toast(R.string.files_upload_done)
            load(cwd)
        }
    }

    private fun edit(e: RemoteEntry) {
        if (e.size > MAX_EDIT) {
            toast(R.string.files_too_big)
            return
        }
        val path = join(cwd, e.name)
        var text = ""
        work({ ch ->
            text = ch.get(path).use { it.readBytes() }.toString(Charsets.UTF_8)
        }) {
            val input = EditText(this).apply {
                setText(text)
                setHorizontallyScrolling(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                gravity = android.view.Gravity.TOP
                minLines = 12
                setTextColor(zColor(R.attr.zText))
            }
            val holder = FrameLayout(this).apply {
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(e.name)
                .setView(holder)
                .setPositiveButton(R.string.save) { _, _ ->
                    val bytes = input.text.toString().toByteArray(Charsets.UTF_8)
                    work({ ch -> ch.put(ByteArrayInputStream(bytes), path) }) {
                        toast(R.string.files_saved)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun rename(e: RemoteEntry) {
        val input = EditText(this).apply {
            setText(e.name)
            setSingleLine(true)
            setTextColor(zColor(R.attr.zText))
        }
        val holder = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_rename)
            .setView(holder)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && n != e.name && !n.contains('/')) {
                    work({ ch -> ch.rename(join(cwd, e.name), join(cwd, n)) }) { load(cwd) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(e: RemoteEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_delete)
            .setMessage(
                getString(if (e.dir) R.string.files_delete_dir else R.string.files_delete_file, e.name)
            )
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                val p = join(cwd, e.name)
                work({ ch -> if (e.dir) ch.rmdir(p) else ch.rm(p) }) { load(cwd) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.files_upload))
        popup.menu.add(0, 2, 1, getString(R.string.files_new_folder))
        popup.menu.add(0, 3, 2, getString(R.string.files_new_file))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> uploadLauncher.launch(arrayOf("*/*"))
                2 -> askName(R.string.files_new_folder) { n ->
                    work({ ch -> ch.mkdir(join(cwd, n)) }) { load(cwd) }
                }
                3 -> askName(R.string.files_new_file) { n ->
                    work({ ch -> ch.put(ByteArrayInputStream(ByteArray(0)), join(cwd, n)) }) { load(cwd) }
                }
            }
            true
        }
        popup.show()
    }

    private fun askName(title: Int, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setTextColor(zColor(R.attr.zText))
        }
        val holder = FrameLayout(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(holder)
            .setPositiveButton(R.string.save) { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty() && !n.contains('/')) onOk(n)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------ Adapter

    inner class FileAdapter(
        private val onClick: (RemoteEntry) -> Unit,
        private val onLong: (RemoteEntry) -> Unit
    ) : ListAdapter<RemoteEntry, FileAdapter.VH>(DIFF) {

        inner class VH(val v: ItemFileBinding) : RecyclerView.ViewHolder(v.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = getItem(position)
            holder.v.tvIcon.text = when {
                e.name == ".." -> "↩"
                e.dir -> "📁"
                e.link -> "🔗"
                else -> "📄"
            }
            holder.v.tvName.text = if (e.name == "..") getString(R.string.files_up) else e.name
            holder.v.tvMeta.text = if (e.name == "..") "" else {
                val date = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(e.mtime))
                (if (e.dir) "" else size(e.size) + " • ") + date + " • " + e.perm
            }
            holder.v.root.setOnClickListener { onClick(e) }
            holder.v.root.setOnLongClickListener {
                onLong(e)
                true
            }
        }
    }

    companion object {
        const val EXTRA_ID = "server_id"
        private const val MAX_EDIT = 256 * 1024L
        private val DIFF = object : DiffUtil.ItemCallback<RemoteEntry>() {
            override fun areItemsTheSame(a: RemoteEntry, b: RemoteEntry) = a.name == b.name && a.dir == b.dir
            override fun areContentsTheSame(a: RemoteEntry, b: RemoteEntry) = a == b
        }
    }
}
