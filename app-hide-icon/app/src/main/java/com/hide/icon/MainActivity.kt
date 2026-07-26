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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
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
        val hidden: Boolean
    )

    private fun resolveAttr(name: String): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(
            resources.getIdentifier(name, "attr", packageName),
            typedValue, true
        )
        return typedValue.data
    }

    private fun resolveAttr(ctx: android.content.Context, name: String): Int {
        val typedValue = android.util.TypedValue()
        val resId = ctx.resources.getIdentifier(name, "attr", ctx.packageName)
        ctx.theme.resolveAttribute(resId, typedValue, true)
        return typedValue.data
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: View
    private lateinit var emptyText: View
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: TextInputEditText
    private lateinit var adapter: AppAdapter

    private var allApps = listOf<AppEntry>()

    private val appDiff = object : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(old: AppEntry, new: AppEntry) =
            old.packageName == new.packageName && old.activityName == new.activityName

        override fun areContentsTheSame(old: AppEntry, new: AppEntry) =
            old.hidden == new.hidden && old.label == new.label
    }

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
        recycler.itemAnimator = DefaultItemAnimator().apply {
            addDuration = 120L
            removeDuration = 120L
            changeDuration = 150L
            moveDuration = 220L
            supportsChangeAnimations = false
        }

        setSupportActionBar(toolbar)

        swipeRefresh.setColorSchemeColors(
            resolveAttr("colorPrimary"),
            resolveAttr("colorSecondary"),
            resolveAttr("colorTertiary")
        )
        swipeRefresh.setOnRefreshListener { loadAppsAsync(isRefresh = true) }

        adapter = AppAdapter { entry, enabled -> toggleApp(entry, enabled) }
        recycler.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                submitFilteredList(s?.toString() ?: "")
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
            submitFilteredList(searchInput.text?.toString() ?: "")
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
            adapter.submitList(filtered)
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
            Settings.Secure.putString(contentResolver, HIDDEN_KEY, list.toString())
        } else {
            val updated = JSONArray()
            for (i in 0 until list.length()) {
                val obj = list.getJSONObject(i)
                if (obj.getString("packageName") != entry.packageName) {
                    updated.put(obj)
                }
            }
            Settings.Secure.putString(contentResolver, HIDDEN_KEY, updated.toString())
        }

        // Create NEW entry with toggled hidden — DiffUtil detects the change
        allApps = allApps.map {
            if (it.packageName == entry.packageName && it.activityName == entry.activityName) {
                it.copy(hidden = hide)
            } else it
        }
        allApps = allApps.sortedWith(
            compareByDescending<AppEntry> { it.hidden }.thenBy { it.label.lowercase() }
        )
        submitFilteredList(searchInput.text?.toString() ?: "")
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

    inner class AppAdapter(
        private val onToggle: (AppEntry, Boolean) -> Unit
    ) : ListAdapter<AppEntry, AppAdapter.VH>(appDiff) {

        init { setHasStableIds(true) }

        override fun getItemId(position: Int): Long =
            getItem(position).packageName.hashCode().toLong()

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
            val entry = getItem(position)
            val ctx = holder.itemView.context
            holder.icon.setImageDrawable(entry.icon)
            holder.name.text = entry.label
            holder.pkg.text = entry.packageName
            if (entry.hidden) {
                holder.card.setCardBackgroundColor(resolveAttr(ctx, "colorSecondaryContainer"))
                holder.name.setTextColor(resolveAttr(ctx, "colorOnSecondaryContainer"))
                holder.pkg.setTextColor(resolveAttr(ctx, "colorOnSecondaryContainer"))
            } else {
                holder.card.setCardBackgroundColor(resolveAttr(ctx, "colorSurface"))
                holder.name.setTextColor(resolveAttr(ctx, "colorOnSurface"))
                holder.pkg.setTextColor(resolveAttr(ctx, "colorOnSurfaceVariant"))
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
    }
}
