package it.presenter.app.ui

import android.app.NotificationManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import it.presenter.app.BuildConfig
import it.presenter.app.R
import it.presenter.app.session.PresenterSession
import it.presenter.app.settings.AppLanguage
import it.presenter.app.settings.RailSide
import it.presenter.app.settings.Settings
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Full-screen settings, inside the main Activity (another Activity would detach the projector). Sections on
 * the left, options on the right.
 */
@Composable
fun SettingsScreen(session: PresenterSession, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var section by rememberSaveable { mutableStateOf(Section.TOOLS) }
    // From "+" (add a server): straight to Servers and keys.
    LaunchedEffect(session.addServerRequested) { if (session.addServerRequested) section = Section.SERVERS }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.settings_back), onClick = onClose)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                ) {
                    for (s in Section.entries) {
                        NavigationDrawerItem(
                            icon = { Icon(s.icon, contentDescription = null) },
                            label = { Text(stringResource(s.title)) },
                            selected = section == s,
                            onClick = { section = s },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.surface),
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 16.dp, bottom = 16.dp),
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    ) {
                        Column(Modifier.widthIn(max = 760.dp)) { SectionContent(session, section) }
                    }
                }
            }
        }
    }
}

private enum class Section(@param:StringRes val title: Int, val icon: ImageVector) {
    TOOLS(R.string.settings_section_tools, Icons.Rounded.Draw),
    LASER(R.string.settings_section_laser, ToolIcons.Laser),
    PAGING(R.string.settings_section_paging, Icons.Rounded.Swipe),
    PROJECTOR(R.string.settings_section_projector, Icons.Rounded.Tv),
    TIMER(R.string.settings_section_timer, Icons.Rounded.Schedule),
    SHORTCUTS(R.string.settings_section_help, Icons.AutoMirrored.Rounded.HelpOutline),
    CODE(R.string.settings_section_code, Icons.Rounded.AccountTree),
    SERVERS(R.string.settings_section_servers, Icons.Rounded.Key),
    SESSION(R.string.settings_section_session, Icons.Rounded.History),
    LANGUAGE(R.string.settings_section_language, Icons.Rounded.Language),
    ABOUT(R.string.settings_section_about, Icons.Rounded.Info),
}

@Composable
private fun SectionContent(session: PresenterSession, section: Section) {
    val settings = session.settings
    when (section) {
        Section.TOOLS -> {
            ChoiceRow(R.string.settings_rail_side) {
                Segmented(
                    options = listOf(RailSide.LEFT to R.string.settings_rail_left, RailSide.RIGHT to R.string.settings_rail_right),
                    selected = settings.railSide,
                    onSelect = settings::updateRailSide,
                )
            }
            SwitchRow(R.string.settings_finger_draws, R.string.settings_finger_draws_summary, session.tools.fingerDraws) {
                session.tools.toggleFingerDraws()
            }
            SwitchRow(R.string.settings_finger_undo, R.string.settings_finger_undo_summary, settings.fingerUndo, settings::updateFingerUndo)
        }
        Section.LASER -> {
            // The slider works in tenths of a second; the value is saved on release.
            var seconds by remember { mutableFloatStateOf(settings.laserTrailHoldMs / 1000f) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_laser_trail)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.settings_laser_trail_summary))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Slider(
                                value = seconds,
                                onValueChange = { seconds = (it * 10).roundToLong() / 10f },
                                onValueChangeFinished = { settings.updateLaserTrailHoldMs((seconds * 1000).roundToLong()) },
                                valueRange = Settings.LASER_TRAIL_RANGE.first / 1000f..Settings.LASER_TRAIL_RANGE.last / 1000f,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.settings_seconds, String.format(LocalConfiguration.current.locales[0], "%.1f", seconds)),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp).width(56.dp),
                            )
                        }
                    }
                },
                colors = rowColors(),
            )
        }
        Section.PAGING -> {
            SwitchRow(R.string.settings_transitions, R.string.settings_transitions_summary, settings.animatedTransitions, settings::updateAnimatedTransitions)
            SwitchRow(R.string.settings_beamer_paging, R.string.settings_beamer_paging_summary, settings.beamerOverlays, settings::updateBeamerOverlays)
        }
        Section.PROJECTOR -> {
            SwitchRow(R.string.settings_keep_screen_on, R.string.settings_keep_screen_on_summary, settings.keepScreenOn, settings::updateKeepScreenOn)
            SwitchRow(R.string.settings_private_start, R.string.settings_private_start_summary, settings.privateStart, settings::updatePrivateStart)
            SwitchRow(R.string.settings_dnd, R.string.settings_dnd_summary, settings.dndWithDisplay, settings::updateDndWithDisplay)
            if (settings.dndWithDisplay) DndAccessRow()
            SwitchRow(
                R.string.settings_projector_inverted, R.string.settings_projector_inverted_summary,
                settings.projectorInverted,
            ) {
                settings.updateProjectorInverted(it)
                // The projector redraws at once with the new colors.
                session.notify(PresenterSession.Change.PROJECTOR)
            }
        }
        Section.TIMER -> {
            // Minutes before the end: yellow does not go below red, red does not exceed yellow.
            MinutesRow(R.plurals.settings_timer_yellow, settings.timerYellowMinutes) { settings.updateTimerThresholds(it, minOf(settings.timerOrangeMinutes, it)) }
            MinutesRow(R.plurals.settings_timer_orange, settings.timerOrangeMinutes) { settings.updateTimerThresholds(maxOf(settings.timerYellowMinutes, it), it) }
            SwitchRow(R.string.settings_show_clock, R.string.settings_show_clock_summary, settings.showClock, settings::updateShowClock)
        }
        Section.SHORTCUTS -> {
            SwitchRow(R.string.settings_hints, R.string.settings_hints_summary, session.hints.enabled, session.hints::updateEnabled)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_shortcuts_summary)) },
                supportingContent = { ShortcutsList(Modifier.padding(top = 12.dp)) },
                colors = rowColors(),
            )
        }
        Section.CODE -> {
            SwitchRow(R.string.settings_code_light, R.string.settings_code_light_summary, settings.codeLightTheme) {
                settings.updateCodeLightTheme(it)
                session.notify(PresenterSession.Change.PROJECTOR)
            }
            SwitchRow(R.string.settings_code_keys, R.string.settings_code_keys_summary, settings.terminalExtraKeys, settings::updateTerminalExtraKeys)
            SwitchRow(R.string.settings_links_in_app, R.string.settings_links_in_app_summary, settings.linksInApp, settings::updateLinksInApp)
        }
        Section.SERVERS -> ServersContent(session)
        Section.SESSION -> {
            SwitchRow(R.string.settings_session, R.string.settings_session_summary, settings.sessionEnabled, settings::updateSessionEnabled)
        }
        Section.LANGUAGE -> LanguageContent()
        Section.ABOUT -> AboutContent()
    }
}

@Composable
private fun LanguageContent() {
    val context = LocalContext.current
    if (!Settings.languageSupported) {
        ListItem(headlineContent = { Text(stringResource(R.string.settings_language_unsupported)) }, colors = rowColors())
        return
    }
    // Reread at every configuration change: changing language does not recreate the Activity.
    val current = remember(LocalConfiguration.current) { Settings.language(context) }
    Column(Modifier.selectableGroup()) {
        for (language in AppLanguage.entries) {
            // Language names are written in the language itself, as Android does.
            val label = when (language) {
                AppLanguage.SYSTEM -> stringResource(R.string.settings_language_system)
                else -> Locale.forLanguageTag(language.tag).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
            }
            ListItem(
                headlineContent = { Text(label) },
                leadingContent = { RadioButton(selected = current == language, onClick = null) },
                colors = rowColors(),
                modifier = Modifier.selectable(
                    selected = current == language,
                    role = Role.RadioButton,
                    // The Activity is not recreated: the texts update and the settings stay open.
                    onClick = { if (current != language) Settings.setLanguage(context, language) },
                ),
            )
        }
    }
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    val info = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            val distribution = when (BuildConfig.DISTRIBUTION) {
                "store" -> stringResource(R.string.settings_distribution_store)
                "fdroid" -> stringResource(R.string.settings_distribution_fdroid)
                else -> stringResource(R.string.settings_distribution_full)
            }
            Text(
                listOf(
                    stringResource(R.string.settings_version, info.versionName ?: "", info.longVersionCode.toInt()),
                    stringResource(R.string.settings_distribution, distribution),
                    stringResource(R.string.settings_author, AUTHOR),
                    stringResource(R.string.settings_app_license),
                ).joinToString("\n"),
            )
        },
        colors = rowColors(),
    )
    HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    Text(
        stringResource(R.string.settings_licenses),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        stringResource(R.string.settings_licenses_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
    )
    val licenses = remember { Licenses.load(context) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    for (group in licenses) {
        val expanded = open == group.kind.name
        val action = stringResource(if (expanded) R.string.license_hide else R.string.license_show)
        ListItem(
            headlineContent = { Text(licenseTitle(group.kind)) },
            supportingContent = { Text(group.families.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                WithTooltip(action) {
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = action)
                }
            },
            colors = rowColors(),
            modifier = Modifier.clickable(onClickLabel = action) { open = if (expanded) null else group.kind.name },
        )
        if (expanded) {
            Text(
                stringResource(R.string.license_components, group.components.size, group.components.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                group.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun licenseTitle(kind: LicenseKind): String = when (kind) {
    LicenseKind.APACHE -> "Apache License 2.0"
    LicenseKind.MIT -> "MIT"
    LicenseKind.BSD -> "BSD"
    LicenseKind.ISC -> "ISC"
    LicenseKind.OFL -> "SIL Open Font License 1.1"
    LicenseKind.OTHER -> stringResource(R.string.license_other)
    LicenseKind.TERMS -> stringResource(R.string.license_terms)
}

/** Author of the app, in Settings → About (a proper name: not translated). */
private const val AUTHOR = "Mario Di Raimondo <mario.diraimondo@gmail.com>"

// ---------------------------------------------------------------- rows

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes summary: Int?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = summary?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = rowColors(),
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

/**
 * Do not disturb needs an access the user grants in the system settings: while it is missing, a row says so and
 * opens that page. Checked again when coming back to the app.
 */
@Composable
private fun DndAccessRow() {
    val context = LocalContext.current
    val notifications = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var granted by remember { mutableStateOf(notifications.isNotificationPolicyAccessGranted) }
    LifecycleResumeEffect(Unit) {
        granted = notifications.isNotificationPolicyAccessGranted
        onPauseOrDispose { }
    }
    if (granted) return
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_dnd_access)) },
        supportingContent = { Text(stringResource(R.string.settings_dnd_access_summary)) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) },
        colors = rowColors(),
        modifier = Modifier.clickable {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
        },
    )
}

@Composable
private fun ChoiceRow(@StringRes title: Int, content: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Column(Modifier.padding(top = 8.dp)) { content() } },
        colors = rowColors(),
    )
}

/** Minutes (1–30) with a slider; saved on release. */
@Composable
private fun MinutesRow(@PluralsRes title: Int, value: Int, onChange: (Int) -> Unit) {
    var minutes by remember(value) { mutableFloatStateOf(value.toFloat()) }
    ListItem(
        headlineContent = { Text(minutes.roundToInt().let { pluralStringResource(title, it, it) }) },
        supportingContent = {
            Slider(
                value = minutes,
                onValueChange = { minutes = it },
                onValueChangeFinished = { onChange(minutes.roundToInt()) },
                valueRange = Settings.TIMER_RANGE.first.toFloat()..Settings.TIMER_RANGE.last.toFloat(),
                steps = Settings.TIMER_RANGE.last - Settings.TIMER_RANGE.first - 1,
            )
        },
        colors = rowColors(),
    )
}

@Composable
private fun <T> Segmented(options: List<Pair<T, Int>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.height(40.dp)) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(stringResource(label)) }
        }
    }
}
