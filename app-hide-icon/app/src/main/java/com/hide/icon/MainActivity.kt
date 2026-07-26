package com.hide.icon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val HIDDEN_KEY = "miui_home_hide_app_list"
    private val LAUNCHER_PKG = "com.miui.home"

    data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String,
        val icon: Drawable?,
        var hidden: Boolean
    )

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: View
    private lateinit var emptyText: View
    private lateinit var recycler: RecyclerView
    private var adapter: AppAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // ponytail: Dynamic Colors guard for API 30 fallback
        if (Build.VERSION.SDK_INT >= 31) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.empty_text)
        recycler = findViewById(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        setSupportActionBar(toolbar)

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showPermissionDialog()
            return
        }

        loadAppsAsync()
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_missing_title)
            .setMessage(R.string.permission_missing_body)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun loadAppsAsync() {
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        recycler.visibility = View.GONE

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) { loadApps() }
            progress.visibility = View.GONE

            if (apps.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                return@launch
            }

            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            adapter = AppAdapter(apps) { entry, enabled ->
                toggleApp(entry, enabled)
            }
            recycler.adapter = adapter
            updateSubtitle(apps)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val hidden = readHiddenSet()
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .map { resolve ->
                val ai = resolve.activityInfo
                AppEntry(
                    label = resolve.loadLabel(pm).toString(),
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = resolve.loadIcon(pm),
                    hidden = hidden.contains(ai.packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun readHiddenSet(): Set<String> {
        val json = Settings.Secure.getString(contentResolver, HIDDEN_KEY) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).getString("packageName") }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun readHiddenList(): JSONArray {
        val json = Settings.Secure.getString(contentResolver, HIDDEN_KEY) ?: return JSONArray()
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    private fun updateSubtitle(apps: List<AppEntry>) {
        val hiddenCount = apps.count { it.hidden }
        toolbar.subtitle = getString(R.string.toolbar_subtitle_hidden_of_total, hiddenCount, apps.size)
    }

    private fun toggleApp(entry: AppEntry, hide: Boolean) {
        val list = readHiddenList()

        if (hide) {
            val obj = JSONObject().apply {
                put("packageName", entry.packageName)
                put("activityName", entry.activityName)
                put("serialNumber", "0")
            }
            list.put(obj)
            entry.hidden = true
        } else {
            val updated = JSONArray()
            for (i in 0 until list.length()) {
                val obj = list.getJSONObject(i)
                if (obj.getString("packageName") != entry.packageName) {
                    updated.put(obj)
                }
            }
            Settings.Secure.putString(contentResolver, HIDDEN_KEY, updated.toString())
            entry.hidden = false
            restartLauncher()
            return
        }

        Settings.Secure.putString(contentResolver, HIDDEN_KEY, list.toString())
        restartLauncher()
    }

    private fun restartLauncher() {
        val apps = adapter?.let { (0 until it.itemCount).map { i -> it.items[i] } } ?: return
        updateSubtitle(apps)

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$LAUNCHER_PKG")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Snackbar.make(findViewById(android.R.id.content), R.string.launcher_restart_snackbar, Snackbar.LENGTH_LONG)
                .setAction(R.string.launcher_restart_action) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$LAUNCHER_PKG")
                    })
                }
                .show()
        } catch (_: Exception) {}
    }

    inner class AppAdapter(
        val items: List<AppEntry>,
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.item_app, parent, false)) {
            val icon: ImageView = itemView.findViewById(R.id.app_icon)
            val name: TextView = itemView.findViewById(R.id.app_name)
            val pkg: TextView = itemView.findViewById(R.id.app_package)
            val toggle: MaterialSwitch = itemView.findViewById(R.id.app_toggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context), parent)

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.icon.setImageDrawable(entry.icon)
            holder.name.text = entry.label
            holder.pkg.text = entry.packageName
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = entry.hidden
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                onToggle(entry, checked)
            }
        }

        override fun getItemCount() = items.size
    }
}
