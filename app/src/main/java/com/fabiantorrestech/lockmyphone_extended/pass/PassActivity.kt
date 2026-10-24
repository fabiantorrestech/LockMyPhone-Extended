package com.fabiantorrestech.lockmyphone_extended.pass

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.MainActivity
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.core.apps.AppMatch
import com.fabiantorrestech.lockmyphone_extended.core.apps.LaunchableApp
import com.fabiantorrestech.lockmyphone_extended.core.lock.LockState
import com.fabiantorrestech.lockmyphone_extended.data.ThemeMode
import com.fabiantorrestech.lockmyphone_extended.ui.AppIcon
import com.fabiantorrestech.lockmyphone_extended.ui.AppInfoMenu
import com.fabiantorrestech.lockmyphone_extended.ui.ExitButton
import com.fabiantorrestech.lockmyphone_extended.ui.FastScrollbar
import com.fabiantorrestech.lockmyphone_extended.ui.HideStatusBar
import com.fabiantorrestech.lockmyphone_extended.ui.PickerClock
import com.fabiantorrestech.lockmyphone_extended.ui.TimeLeft
import com.fabiantorrestech.lockmyphone_extended.ui.rememberFontFamily
import com.fabiantorrestech.lockmyphone_extended.ui.RefreshButton
import kotlinx.coroutines.launch
import com.fabiantorrestech.lockmyphone_extended.ui.theme.LockMyPhoneExtendedTheme

/**
 * The target of the lock-screen shortcuts. SystemUI unlocks the device before opening it, and
 * it grants a pass within the relock grace period. It then either opens one app directly or
 * shows the picker of allowed apps.
 */
class PassActivity : ComponentActivity() {

    private val container by lazy { LockApp.container(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lockActive = container.lockController.isLockActive()

        when {
            intent.getBooleanExtra(EXTRA_DIAL, false) -> {
                val dialer = container.appCatalog.defaultDialerPackage()
                if (!lockActive || (dialer != null && container.lockController.grantPass(dialer))) {
                    launch(container.appCatalog.dialIntent())
                } else {
                    refuse()
                }
                finish()
            }
            intent.hasExtra(EXTRA_PACKAGE) -> {
                val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
                if (!lockActive || container.lockController.grantPass(packageName)) {
                    container.appCatalog.launchIntent(packageName)?.let(::launch)
                } else {
                    refuse()
                }
                finish()
            }
            else -> showPicker(lockActive)
        }
    }

    private fun showPicker(lockActive: Boolean) {
        val state = container.lockController.state.value
        // A Home redirect only reopens the list for a pass that's still running: if the phone
        // relocked before this screen came up, it must not hand out a new pass.
        if (intent.getBooleanExtra(EXTRA_FROM_REDIRECT, false) &&
            (state !is LockState.Locked || !state.session.passActive)
        ) {
            finish()
            return
        }
        if (!lockActive || state !is LockState.Locked || !container.lockController.grantPickerPass()) {
            refuse()
            finish()
            return
        }
        // The dialer is in the session's allowed apps too, but it already has its own Phone row.
        val dialer = container.appCatalog.defaultDialerPackage()
        val apps = state.session.allowedPackages
            .filter { it != dialer && it !in state.session.hiddenPackages }
            .map { LaunchableApp(it, container.appCatalog.label(it)) }
            .sortedBy { it.label.lowercase() }
        // Hidden allowed apps: never listed, only found by the search below (Enter, near-exact name).
        val hiddenApps = state.session.allowedPackages
            .filter { it != dialer && it in state.session.hiddenPackages }
            .map { LaunchableApp(it, container.appCatalog.label(it)) }
        // App info lists every allowed app, hidden ones and the dialer included.
        val appInfoApps = state.session.allowedPackages
            .map { LaunchableApp(it, container.appCatalog.label(it)) }
            .sortedBy { it.label.lowercase() }

        enableEdgeToEdge()
        setContent {
            val showIcons by container.settingsStore.showAppIcons.collectAsState()
            val pickerStyle by container.settingsStore.pickerStyle.collectAsState()
            val font = rememberFontFamily(pickerStyle.font, container.fontLibrary)
            val appName: @Composable (String) -> Unit = { label ->
                Text(label, fontSize = pickerStyle.appNameSizeSp.sp, fontFamily = font)
            }
            // Always OLED black, whatever the app's theme.
            LockMyPhoneExtendedTheme(ThemeMode.OLED) {
                HideStatusBar(pickerStyle.hideStatusBar)
                // Back goes to the countdown screen instead of the home screen (which would relock).
                BackHandler { openCountdown() }
                val listState = rememberLazyListState()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding).fillMaxSize()) {
                        val lockNow by container.lockController.state.collectAsState()
                        val endMs = (lockNow as? LockState.Locked)?.session?.effectiveEndMs
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            // Pinned above the list, so it stays in the corner while the apps scroll.
                            PickerClock(pickerStyle, font, Modifier.weight(1f))
                            when {
                                // The lock is over: nothing left to count, so offer a way out instead.
                                endMs == null -> ExitButton(pickerStyle, font, onExit = ::finish)
                                pickerStyle.showTimeLeft -> TimeLeft(endMs, pickerStyle, font)
                            }
                            if (endMs != null) {
                                AppInfoMenu(
                                    apps = appInfoApps,
                                    iconProvider = container.appCatalog,
                                    showIcons = showIcons,
                                    onOpenAppInfo = ::openAppInfo,
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(state = listState) {
                                // Space where the "Allowed apps" heading was: the clock says enough.
                                item { Spacer(Modifier.height(64.dp)) }
                                item {
                                    ListItem(
                                        leadingContent = if (showIcons) {
                                            {
                                                container.appCatalog.defaultDialerPackage()?.let {
                                                    AppIcon(it, container.appCatalog)
                                                }
                                            }
                                        } else {
                                            null
                                        },
                                        headlineContent = { appName(stringResource(R.string.notif_action_phone)) },
                                        modifier = Modifier.clickable {
                                            launch(container.appCatalog.dialIntent())
                                            finish()
                                        },
                                    )
                                }
                                if (hiddenApps.isNotEmpty()) {
                                    item(key = "search") {
                                        HiddenAppSearch(hiddenApps, showIcons, appName) { app ->
                                            container.appCatalog.launchIntent(app.packageName)?.let(::launch)
                                            finish()
                                        }
                                    }
                                }
                                // A place's lock: check right here whether you've left, without going back.
                                if (container.notifications.isRefreshable(state.session)) {
                                    item {
                                        val refresh by container.placeRefresher.state.collectAsState()
                                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            RefreshButton(refresh.forLock(state.session.startedAtMs)) {
                                                container.appScope.launch { container.placeRefresher.refresh() }
                                            }
                                        }
                                    }
                                }
                                items(apps, key = { it.packageName }, contentType = { "app" }) { app ->
                                    ListItem(
                                        leadingContent = if (showIcons) {
                                            { AppIcon(app.packageName, container.appCatalog) }
                                        } else {
                                            null
                                        },
                                        headlineContent = { appName(app.label) },
                                        modifier = Modifier.clickable {
                                            container.appCatalog.launchIntent(app.packageName)?.let(::launch)
                                            finish()
                                        },
                                    )
                                }
                            }
                            FastScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                        }
                    }
                }
            }
        }
    }

    /**
     * The search for hidden allowed apps: nothing shows while typing; Enter shows the apps whose name the text is, or
     * nearly is ([AppMatch]). Changing the text clears the results again.
     */
    @Composable
    private fun HiddenAppSearch(
        hiddenApps: List<LaunchableApp>,
        showIcons: Boolean,
        appName: @Composable (String) -> Unit,
        onOpen: (LaunchableApp) -> Unit,
    ) {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<LaunchableApp>?>(null) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    results = null
                },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    results = hiddenApps.filter { AppMatch.matches(query, it.label) }.sortedBy { it.label.lowercase() }
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                results == null -> Unit
                results!!.isEmpty() -> Text(
                    stringResource(R.string.picker_search_none),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                else -> results!!.forEach { app ->
                    ListItem(
                        leadingContent = if (showIcons) {
                            { AppIcon(app.packageName, container.appCatalog) }
                        } else {
                            null
                        },
                        headlineContent = { appName(app.label) },
                        modifier = Modifier.clickable { onOpen(app) },
                    )
                }
            }
        }
    }

    /**
     * Coming back to this screen (e.g. unlocking the phone) after the lock ended: close it, so the
     * user lands on the app they were in before, or the home screen.
     */
    override fun onRestart() {
        super.onRestart()
        if (!container.lockController.isLockActive()) finish()
    }

    /**
     * Shows the lock countdown. MainActivity is started before this activity finishes, so the
     * launcher never comes to the front in between (that would count as leaving and relock).
     */
    private fun openCountdown() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun launch(target: Intent) {
        try {
            startActivity(target)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.pass_app_missing, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens an allowed app's App info page. This list stays underneath, so Back returns to it;
     * any other Settings page relocks ([LockController.startAppInfoVisit]).
     */
    private fun openAppInfo(packageName: String) {
        val settingsPackage = container.appCatalog.appInfoPackage()
        if (settingsPackage == null || !container.lockController.startAppInfoVisit(settingsPackage)) {
            Toast.makeText(this, R.string.picker_app_info_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        launch(container.appCatalog.appInfoIntent(packageName))
    }

    private fun refuse() {
        Toast.makeText(this, R.string.pass_refused, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_DIAL = "dial"
        private const val EXTRA_FROM_REDIRECT = "from_redirect"

        fun appIntent(context: Context, packageName: String): Intent =
            Intent(context, PassActivity::class.java)
                .setAction("pass.app.$packageName") // unique per app so PendingIntents don't merge
                .putExtra(EXTRA_PACKAGE, packageName)

        fun dialIntent(context: Context): Intent =
            Intent(context, PassActivity::class.java)
                .setAction("pass.dial")
                .putExtra(EXTRA_DIAL, true)

        fun pickerIntent(context: Context): Intent =
            Intent(context, PassActivity::class.java).setAction("pass.picker")

        /** The list again after Home during a pass; it never grants a new pass. */
        fun redirectIntent(context: Context): Intent =
            pickerIntent(context).putExtra(EXTRA_FROM_REDIRECT, true)
    }
}
