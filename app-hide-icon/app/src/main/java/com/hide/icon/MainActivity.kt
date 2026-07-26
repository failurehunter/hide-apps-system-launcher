package com.hide.icon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
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
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: TextInputEditText

    private var allApps = listOf<AppEntry>()
    private var adapter: AppAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 31) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        emptyText = findViewById(R.id.empty_text)
        swipeRefresh = findViewById(R.id.swipe_refresh)
        recycler = findViewById(R.id.recycler)
        searchInput = findViewById(R.id.search_input)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator?.apply {
            addDuration = 120L
            removeDuration = 120L
            changeDuration = 150L
            moveDuration = 120L
        }

        setSupportActionBar(toolbar)

        swipeRefresh.setColorSchemeResources(
            R.color.md_theme_primary,
            R.color.md_theme_secondary,
            R.color.md_theme_tertiary
        )
        swipeRefresh.setOnRefreshListener { loadAppsAsync(isRefresh = true) }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchInput.clearFocus()
                true
            } else false
        }

        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showPermissionDialog()
            return
        }

        loadAppsAsync()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            openLauncherAppInfo()
        }
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.permission_missing_title)
            .setMessage(R.string.permission_missing_body)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun loadAppsAsync(isRefresh: Boolean = false) {
        if (!isRefresh) {
            progress.visibility = View.VISIBLE
            emptyText.visibility = View.GONE
            recycler.visibility = View.GONE
        }

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) { loadApps() }
            allApps = apps
            progress.visibility = View.GONE
            swipeRefresh.isRefreshing = false

            if (apps.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                return@launch
            }

            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            val query = searchInput.text?.toString() ?: ""
            submitFilteredList(query)
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
            .sortedWith(compareByDescending<AppEntry> { it.hidden }.thenBy { it.label.lowercase() })
            .toList()
    }

    private fun filterList(query: String) {
        submitFilteredList(query)
    }

    private fun submitFilteredList(query: String) {
        val filtered = if (query.isBlank()) allApps
        else allApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }

        if (filtered.isEmpty() && allApps.isNotEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            adapter = AppAdapter(filtered) { entry, enabled ->
                toggleApp(entry, enabled)
            }
            recycler.adapter = adapter
        }
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
            refreshListAfterToggle()
            return
        }

        Settings.Secure.putString(contentResolver, HIDDEN_KEY, list.toString())
        refreshListAfterToggle()
    }

    private fun refreshListAfterToggle() {
        val query = searchInput.text?.toString() ?: ""
        // Re-sort: hidden apps to top, then alpha
        allApps = allApps.sortedWith(
            compareByDescending<AppEntry> { it.hidden }.thenBy { it.label.lowercase() }
        )
        submitFilteredList(query)
        updateSubtitle(allApps)
    }

    private fun openLauncherAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$LAUNCHER_PKG")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Snackbar.make(
                findViewById(android.R.id.content),
                R.string.launcher_restart_snackbar,
                Snackbar.LENGTH_LONG
            ).setAction(R.string.launcher_restart_action) {
                startActivity(intent)
            }.show()
        } catch (_: Exception) {}
    }

    private data class AccentPair(val container: Int, val onContainer: Int)

    private val accentPalette = listOf(
        AccentPair(R.color.md_theme_primaryContainer, R.color.md_theme_onPrimaryContainer),
        AccentPair(R.color.md_theme_secondaryContainer, R.color.md_theme_onSecondaryContainer),
        AccentPair(R.color.md_theme_tertiaryContainer, R.color.md_theme_onTertiaryContainer),
    )

    inner class AppAdapter(
        val items: List<AppEntry>,
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.VH>() {

        inner class VH(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.item_app, parent, false)) {
            val card: MaterialCardView = itemView as MaterialCardView
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
            val ctx = holder.itemView.context
            if (entry.hidden) {
                val accent = accentPalette[position % accentPalette.size]
                holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, accent.container))
                holder.name.setTextColor(ContextCompat.getColor(ctx, accent.onContainer))
                holder.pkg.setTextColor(ContextCompat.getColor(ctx, accent.onContainer))
            } else {
                holder.card.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.md_theme_surface))
                holder.name.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurface))
                holder.pkg.setTextColor(ContextCompat.getColor(ctx, R.color.md_theme_onSurfaceVariant))
            }
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = entry.hidden
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                onToggle(entry, checked)
            }
            holder.card.setOnClickListener {
                holder.toggle.isChecked = !holder.toggle.isChecked
            }
        }

        override fun getItemCount() = items.size
    }
}
