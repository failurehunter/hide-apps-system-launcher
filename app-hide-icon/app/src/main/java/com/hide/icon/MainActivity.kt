package com.hide.icon

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Switch
import com.google.android.material.appbar.MaterialToolbar
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val apps = loadApps()
        val rv = findViewById<RecyclerView>(R.id.recycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = AppAdapter(apps) { entry, enabled ->
            toggleApp(entry, enabled)
        }
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val hidden = readHiddenSet()
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
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

    private fun toggleApp(entry: AppEntry, hide: Boolean) {
        val list = readHiddenList()
        val serial = "0"

        if (hide) {
            val obj = JSONObject().apply {
                put("packageName", entry.packageName)
                put("activityName", entry.activityName)
                put("serialNumber", serial)
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
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$LAUNCHER_PKG")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "Нажмите «Принудительная остановка»", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    inner class AppAdapter(
        private val items: List<AppEntry>,
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.item_app, parent, false)) {
            val icon: ImageView = itemView.findViewById(R.id.app_icon)
            val name: TextView = itemView.findViewById(R.id.app_name)
            val pkg: TextView = itemView.findViewById(R.id.app_package)
            val toggle: Switch = itemView.findViewById(R.id.app_toggle)
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
