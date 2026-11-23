package os.retinue.ara.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import os.retinue.ara.R
import os.retinue.ara.launcher.AppInfo
import os.retinue.ara.launcher.AppRecency
import os.retinue.ara.launcher.LauncherConfig
import os.retinue.ara.launcher.LauncherEntry
import os.retinue.ara.launcher.PinnedShortcut
import os.retinue.ara.launcher.TrayLogic

/** A line of the tray is as tall as the bar, which is its first line. */
private val TrayLineHeight = LauncherBarHeight

/** The narrowest an app gets in the tray's lines; they share the width evenly. */
private val TrayCellMinWidth = 72.dp

/** A release faster than this (per second) goes all the way up or down; slower ones settle on a line. */
private val TrayFlingVelocity = 600.dp

/**
 * Space left above the tray when the page shows no top menu, or has not said
 * yet: a strip to tap. A measured menu is never taller than half the page
 * (see [TrayLogic.TOP_MENU_SCRIPT]) and leaves at least [MinTopMenu].
 */
private val DefaultTopMenu = 48.dp
private val MinTopMenu = 24.dp

/** How dark the page gets behind the tray, pulled all the way up. */
private const val ScrimAlpha = 0.32f

/**
 * The tray's lines: every app and web app (apps inside folders too, but no
 * folders) except those on the bar itself, which is the tray's first line;
 * the most recently used first (see [AppRecency]), the never-used by name.
 * [usage] is Android's last use per package (with usage access), [launches]
 * Ara's own per item.
 */
internal fun trayItems(
    apps: List<AppInfo>,
    shortcuts: List<PinnedShortcut>,
    launcher: LauncherConfig,
    launches: Map<String, Long>,
    usage: Map<String, Long>,
): List<DrawerItem> {
    val onBar = launcher.entries.mapNotNullTo(HashSet()) { (it as? LauncherEntry.Launchable)?.key }
    val items = drawerItems(apps, shortcuts).filterNot { it.key in onBar }
    return AppRecency.recentFirst(items) { item ->
        AppRecency.lastUsed(item.key, (item as? DrawerItem.App)?.app?.packageName, launches, usage)
    }
}

/**
 * How far the tray is pulled up over the page. It is the launcher bar at rest
 * (0); dragged up (the bar, or the lines below it), it shows more lines of
 * apps, up to the web app's top menu. Let go, it settles on a whole line; a
 * fling takes it all the way. [collapse] puts it down again, animated, from
 * outside: a launch, a tap above it, Back, Home.
 *
 * Also the bar's [DraggableState]. A running animation gives way to a drag,
 * on the bar or on the lines, and to [snapCollapsed]. Lives as long as the
 * window, so it can be asked to collapse from there.
 */
@Stable
class TrayState : DraggableState {

    /** Pixels the tray is pulled up above the bar (0: only the bar). */
    var expansion by mutableFloatStateOf(0f)
        private set

    /** How far it can go (set by the layout: the space below the top menu, or where the apps end). */
    var maxExpansion by mutableFloatStateOf(0f)
        private set

    /** Pulled up at all: the page waits behind a scrim, and Back puts the tray down first. */
    val isOpen: Boolean by derivedStateOf { expansion > 0f }

    /** Asked to go down; the tray's composable animates it (it has the frame clock). */
    internal var collapseRequests by mutableIntStateOf(0)
        private set

    private var line = 1f
    private var flingVelocity = Float.MAX_VALUE
    private val mutex = MutatorMutex()

    /** The animation on its way (opening, settling, closing), if any. */
    private var animation: Job? = null
    private val dragScope = object : DragScope {
        override fun dragBy(pixels: Float) {
            pull(pixels)
        }
    }

    /** Put the tray down, animated: also one only starting to come up (a no-op when it is down and still). */
    fun collapse() {
        if (expansion > 0f || animation != null) collapseRequests++
    }

    /** Put the tray down at once, stopping any animation: the window is leaving the screen, or the bar is hiding. */
    fun snapCollapsed() {
        animation?.cancel()
        expansion = 0f
    }

    /** The layout's measures: the tray's reach, a line's height, the fling speed (all px). */
    internal fun setBounds(max: Float, line: Float, flingVelocity: Float) {
        this.line = line
        this.flingVelocity = flingVelocity
        if (max != maxExpansion) {
            maxExpansion = max
            if (expansion > max) expansion = max
        }
    }

    /**
     * A finger on the lines moved by [delta] px (positive = down): an
     * animation on its way stops, as it does for a drag on the bar. Returns
     * what the tray took of it.
     */
    internal fun pullByUser(delta: Float): Float {
        animation?.cancel()
        return pull(delta)
    }

    /** A finger moved by [delta] px (positive = down); returns what the tray took of it. */
    internal fun pull(delta: Float): Float {
        val before = expansion
        expansion = (before - delta).coerceIn(0f, maxExpansion)
        return before - expansion
    }

    /** Let go with [velocity] px/s (positive = up): settle on a line, or fling to an end. */
    internal suspend fun settle(velocity: Float) {
        animateTo(TrayLogic.settle(expansion, velocity, line, maxExpansion, flingVelocity), velocity)
    }

    internal suspend fun animateTo(target: Float, velocity: Float = 0f) {
        mutex.mutate {
            val job = currentCoroutineContext()[Job]
            animation = job
            try {
                animate(expansion, target, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    expansion = value.coerceIn(0f, maxExpansion)
                }
            } finally {
                if (animation === job) animation = null
            }
        }
    }

    override fun dispatchRawDelta(delta: Float) {
        pull(delta)
    }

    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        mutex.mutateWith(dragScope, dragPriority, block)
    }
}

/**
 * The lines below the bar share the tray's drag: a finger moving up grows the
 * tray before the lines scroll, one moving down shrinks it once the lines are
 * back at their top, and letting go between two lines settles the tray.
 */
private class TrayScrollConnection(private val tray: TrayState) : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f && source == NestedScrollSource.UserInput) Offset(0f, tray.pullByUser(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, tray.pullByUser(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (tray.expansion <= 0f || tray.expansion >= tray.maxExpansion) return Velocity.Zero
        tray.settle(-available.y)
        return available
    }
}

/**
 * The launcher bar ([bar]) as a tray, over the bottom of the page: pull it up
 * for more lines of apps and web apps ([items], the most recently used first;
 * no folders), up to the web app's top menu ([topMenuPx] below the top of the
 * page, as the page measured it; null when unknown). A tap on an app opens it,
 * a long-press offers what All apps does (the other way of opening it, pin to
 * the bar, add to a folder or a new one, remove a web app). A tap anywhere
 * above the tray puts it down. [onOpened] runs as it starts to come up.
 *
 * [modifier] gives the place between the system bars; the tray takes its
 * bottom, the rest passes touches to the page unless the tray is open.
 */
@Composable
internal fun LauncherTray(
    state: TrayState,
    items: List<DrawerItem>,
    folders: List<LauncherEntry.Folder>,
    topMenuPx: Float?,
    onOpened: () -> Unit,
    onLaunch: (DrawerItem) -> Unit,
    /** The long-press item for the other way of opening an app (see [AlternateOpen]); null for none. */
    alternateOpen: AlternateOpen?,
    onLaunchAlternate: (DrawerItem) -> Unit,
    onPin: (DrawerItem) -> Unit,
    onAddToFolder: (LauncherEntry.Launchable, LauncherEntry.Folder) -> Unit,
    onNewFolder: (LauncherEntry.Launchable) -> Unit,
    onRemoveShortcut: (PinnedShortcut) -> Unit,
    modifier: Modifier = Modifier,
    bar: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val open = state.isOpen
    LaunchedEffect(state.collapseRequests) {
        if (state.collapseRequests > 0) state.animateTo(0f)
    }
    LaunchedEffect(open) {
        if (open) onOpened()
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val topMenu = with(density) { topMenuPx?.toDp()?.coerceAtLeast(MinTopMenu) } ?: DefaultTopMenu
        val columns = (maxWidth / TrayCellMinWidth).toInt().coerceAtLeast(1)
        val linePx = with(density) { TrayLineHeight.toPx() }
        val availablePx = with(density) { (maxHeight - topMenu - LauncherBarHeight).toPx() }
        val maxPx = TrayLogic.maxExpansion(availablePx, TrayLogic.lines(items.size, columns), linePx)
        val flingPx = with(density) { TrayFlingVelocity.toPx() }
        SideEffect { state.setBounds(maxPx, linePx, flingPx) }

        if (open) {
            // Over the page while the tray is up: a tap anywhere above it puts it down.
            val scrim = MaterialTheme.colorScheme.scrim
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val max = state.maxExpansion
                        val fraction = if (max > 0f) (state.expansion / max).coerceIn(0f, 1f) else 0f
                        drawRect(scrim.copy(alpha = ScrimAlpha * fraction))
                    }
                    .pointerInput(state) { detectTapGestures { state.collapse() } },
            )
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = if (open) 8.dp else 0.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                val more = stringResource(R.string.tray_more_apps)
                val fewer = stringResource(R.string.tray_fewer_apps)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            state = state,
                            orientation = Orientation.Vertical,
                            enabled = maxPx > 0f,
                            onDragStopped = { velocity -> state.settle(-velocity) },
                        ),
                ) {
                    bar()
                    if (maxPx > 0f) {
                        // The handle: what says the bar comes up. Touches go through it to the bar;
                        // a screen reader gets an action to open and close the tray.
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 3.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(2.dp),
                                )
                                .semantics {
                                    contentDescription = if (open) fewer else more
                                    onClick(label = if (open) fewer else more) {
                                        if (open) state.collapse() else scope.launch { state.animateTo(state.maxExpansion) }
                                        true
                                    }
                                },
                        )
                    }
                }
                if (open) {
                    TrayLines(
                        state = state,
                        items = items,
                        columns = columns,
                        folders = folders,
                        onLaunch = onLaunch,
                        alternateOpen = alternateOpen,
                        onLaunchAlternate = onLaunchAlternate,
                        onPin = onPin,
                        onAddToFolder = onAddToFolder,
                        onNewFolder = onNewFolder,
                        onRemoveShortcut = onRemoveShortcut,
                    )
                }
            }
        }
    }
}

/** The lines below the bar, as tall as the tray is pulled up (they scroll once it is all the way up). */
@Composable
private fun TrayLines(
    state: TrayState,
    items: List<DrawerItem>,
    columns: Int,
    folders: List<LauncherEntry.Folder>,
    onLaunch: (DrawerItem) -> Unit,
    alternateOpen: AlternateOpen?,
    onLaunchAlternate: (DrawerItem) -> Unit,
    onPin: (DrawerItem) -> Unit,
    onAddToFolder: (LauncherEntry.Launchable, LauncherEntry.Folder) -> Unit,
    onNewFolder: (LauncherEntry.Launchable) -> Unit,
    onRemoveShortcut: (PinnedShortcut) -> Unit,
) {
    // A new one each time the tray comes up: it starts at the most recent apps.
    val grid = rememberLazyGridState()
    val connection = remember(state) { TrayScrollConnection(state) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = grid,
        modifier = Modifier
            .fillMaxWidth()
            // As tall as the tray is pulled up: read while measuring, so a drag only lays it out again.
            .layout { measurable, constraints ->
                val height = constraints.constrainHeight(state.expansion.roundToInt())
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .nestedScroll(connection),
    ) {
        items(items, key = { it.key }) { item ->
            TrayTile(
                item = item,
                folders = folders,
                onLaunch = { onLaunch(item) },
                alternateOpen = alternateOpen?.item(item.key),
                onLaunchAlternate = { onLaunchAlternate(item) },
                onPin = { onPin(item) },
                onAddToFolder = onAddToFolder,
                onNewFolder = onNewFolder,
                onRemove = (item as? DrawerItem.Shortcut)?.let { { onRemoveShortcut(it.shortcut) } },
            )
        }
    }
}

/** An app or web app in the tray, looking like one on the bar; long-press for All apps' menu. */
@Composable
private fun TrayTile(
    item: DrawerItem,
    folders: List<LauncherEntry.Folder>,
    onLaunch: () -> Unit,
    alternateOpen: Int?,
    onLaunchAlternate: () -> Unit,
    onPin: () -> Unit,
    onAddToFolder: (LauncherEntry.Launchable, LauncherEntry.Folder) -> Unit,
    onNewFolder: (LauncherEntry.Launchable) -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(TrayLineHeight)) {
        LauncherSlot(
            label = item.label,
            icon = { DrawerItemIcon(item, size = 34.dp) },
            onClick = onLaunch,
            onLongClick = { menu = true },
        )
        DrawerItemMenu(
            expanded = menu,
            onDismiss = { menu = false },
            item = item,
            folders = folders,
            // What is on the bar is not in the tray.
            pinned = false,
            alternateOpen = alternateOpen,
            onLaunchAlternate = onLaunchAlternate,
            onPin = onPin,
            onAddToFolder = onAddToFolder,
            onNewFolder = onNewFolder,
            onRemove = onRemove,
        )
    }
}
