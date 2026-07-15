package com.shiphappens.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shiphappens.design.ShipColors
import com.shiphappens.design.ShipTheme
import com.shiphappens.design.colorFromHex
import com.shiphappens.design.hankenFamily
import com.shiphappens.design.monoFamily
import com.shiphappens.ui.components.DaysRing
import com.shiphappens.ui.components.ToastOverlay
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ListScreen(
    onOpenDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ListViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.collect {
            if (it == Lifecycle.State.RESUMED) vm.onForeground()
        }
    }

    ListContent(
        state = state,
        onOpenDetail = onOpenDetail,
        onOpenSettings = onOpenSettings,
        onTabSelect = vm::onTabSelect,
        onRefresh = vm::onRefresh,
        onPendingName = vm::onPendingName,
        onAcceptPending = vm::onAcceptPending,
        onDismissPending = vm::onDismissPending,
        onManualName = vm::onManualName,
        onManualTracking = vm::onManualTracking,
        onPickerToggle = vm::onPickerToggle,
        onPickCarrier = vm::onPickCarrier,
        onAddManual = vm::onAddManual,
        onClearManual = vm::onClearManual,
        onArchive = vm::onArchive,
        onRestore = vm::onRestore,
        onDelete = vm::onDelete,
        onUndo = vm::onUndo,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListContent(
    state: ListUiState,
    onOpenDetail: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onTabSelect: (ListTab) -> Unit = {},
    onRefresh: () -> Unit = {},
    onPendingName: (String) -> Unit = {},
    onAcceptPending: () -> Unit = {},
    onDismissPending: () -> Unit = {},
    onManualName: (String) -> Unit = {},
    onManualTracking: (String) -> Unit = {},
    onPickerToggle: () -> Unit = {},
    onPickCarrier: (String?) -> Unit = {},
    onAddManual: () -> Unit = {},
    onClearManual: () -> Unit = {},
    onArchive: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onDelete: (String) -> Unit = {},
    onUndo: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(state, onOpenSettings)
            Tabs(state.tab, onTabSelect)
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f)
            ) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        bottom = 26.dp,
                        top = 2.dp
                    ),
                ) {
                    state.pendingImport?.let { p ->
                        item(key = "pending") {
                            PendingImportCard(p, onPendingName, onAcceptPending, onDismissPending)
                        }
                    }
                    if (state.tab == ListTab.ACTIVE && state.pendingImport == null) {
                        item(key = "manual") {
                            ManualAddCard(
                                state.manualAdd, onManualName, onManualTracking,
                                onPickerToggle, onPickCarrier, onAddManual, onClearManual
                            )
                        }
                    }
                    items(state.cards, key = { "${state.tab}-${it.id}" }) { card ->
                        ParcelRow(
                            card, tab = state.tab, onClick = { onOpenDetail(card.id) },
                            onArchive = { onArchive(card.id) }, onRestore = { onRestore(card.id) },
                            onDelete = { onDelete(card.id) },
                            modifier = Modifier.animateItem())
                    }
                    state.emptyText?.let {
                        item(key = "empty") { EmptyState(it, Modifier.animateItem()) }
                    }
                }
            }
        }
        ToastOverlay(
            state.toast, onUndo,
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 28.dp),
        )
    }
}

@Composable
private fun Header(state: ListUiState, onOpenSettings: () -> Unit) {
    Row(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.dateLabel.uppercase(), color = ShipColors.faint,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp)
            )
            Text(
                "Ship Happens",
                color = ShipColors.ink,
                fontSize = 31.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = hankenFamily()
            )
            Text(
                state.headerSub,
                color = ShipColors.muted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        OutlinedIconButton(
            onClick = onOpenSettings, shape = RoundedCornerShape(13.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ShipColors.hairlineStrong),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = ShipColors.card),
        ) { Text("⚙", fontSize = 17.sp) }
    }
}

@Composable
private fun Tabs(tab: ListTab, onSelect: (ListTab) -> Unit) {
    Row(
        Modifier.padding(horizontal = 22.dp, vertical = 14.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(ShipColors.segmentBg).padding(4.dp),
    ) {
        listOf(ListTab.ACTIVE to "Active", ListTab.ARCHIVED to "Archived").forEach { (t, label) ->
            val selected = tab == t
            Text(
                label, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (selected) ShipColors.ink else ShipColors.muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = hankenFamily(),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (selected) ShipColors.card else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CarrierBadge(accentHex: String, size: Int = 46) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp))
            .background(colorFromHex(accentHex)),
        contentAlignment = Alignment.Center,
    ) { Text("📦", fontSize = (size * 0.42).sp) }
}

@Composable
private fun ParcelRow(
    card: ParcelCardUi,
    tab: ListTab,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ShipColors.card)
                .border(1.dp, ShipColors.hairline, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CarrierBadge(card.accentHex)
            Column(Modifier.weight(1f)) {
                Text(
                    card.name,
                    color = ShipColors.ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = hankenFamily()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        card.carrierName,
                        color = colorFromHex(card.accentHex),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(Modifier.size(3.dp).clip(CircleShape).background(ShipColors.hairlineStrong))
                    Text(
                        card.statusText,
                        color = ShipColors.muted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (card.refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            color = colorFromHex(card.accentHex),
                            strokeWidth = 1.5.dp,
                        )
                    }
                }
            }
            when {
                card.delivered -> Text(
                    "Delivered",
                    color = ShipColors.delivered,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(ShipColors.deliveredBg)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )

                card.ring != null -> DaysRing(
                    days = card.ring.number,
                    accent = colorFromHex(card.accentHex),
                    urgent = card.urgent
                )
            }
        }
    }

    val (startToEnd, endToStart) = if (tab == ListTab.ACTIVE) {
        archiveAction(onArchive) to deleteAction(onDelete)
    } else {
        deleteAction(onDelete) to restoreAction(onRestore)
    }

    Box(modifier.padding(vertical = 9.dp)) {
        SwipeActionRow(startToEnd, endToStart) { content() }
    }
}

@Composable
private fun DashedCard(borderColor: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp).clip(RoundedCornerShape(20.dp))
            .background(ShipColors.cardAlt).border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

@Composable
private fun SmallActionButton(
    bg: Color,
    label: String,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (outlined) ShipColors.card else bg)
            .then(
                if (outlined) Modifier.border(
                    1.dp,
                    ShipColors.hairlineStrong,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (outlined) ShipColors.faint else Color.White,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun CardTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false
) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = ShipColors.ink,
            fontFamily = if (mono) monoFamily() else hankenFamily(),
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Bold,
            fontSize = if (mono) 12.sp else 16.sp,
        ),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(
                    placeholder,
                    color = ShipColors.faint,
                    fontSize = if (mono) 12.sp else 16.sp
                ); inner()
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PendingImportCard(
    p: PendingImportUi,
    onName: (String) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val accent = colorFromHex(p.accentHex)
    DashedCard(accent) {
        CarrierBadge(p.accentHex, size = 44)
        Column(Modifier.weight(1f)) {
            Text(
                "FROM CLIPBOARD · ${p.carrierName.uppercase()}", color = accent, fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp
            )
            CardTextField(p.name, onName, "Name this package")
            Text(
                p.tracking, color = ShipColors.muted, fontFamily = monoFamily(), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionButton(accent, "✓", onAccept)
            SmallActionButton(ShipColors.card, "✕", onDismiss, outlined = true)
        }
    }
}

@Composable
private fun ManualAddCard(
    m: ManualAddUi, onName: (String) -> Unit, onTracking: (String) -> Unit,
    onPickerToggle: () -> Unit, onPick: (String?) -> Unit, onAdd: () -> Unit, onClear: () -> Unit,
) {
    val accent = m.effectiveAccentHex?.let(::colorFromHex) ?: Color(0xFFC3BDB1)
    Box {
        DashedCard(if (m.effectiveAccentHex != null) accent else Color(0xFFCFC9BE)) {
            Box {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(accent)
                        .clickable(onClick = onPickerToggle), contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (m.effectiveCarrierName != null) "📦" else "+", color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold
                    )
                }
                DropdownMenu(expanded = m.pickerOpen, onDismissRequest = onPickerToggle) {
                    m.options.forEach { opt ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Box(
                                    Modifier.size(14.dp).clip(CircleShape)
                                        .background(
                                            opt.accentHex?.let(::colorFromHex) ?: ShipColors.cardAlt
                                        )
                                        .border(
                                            if (opt.accentHex == null) 2.dp else 0.dp,
                                            Color(0xFFC3BDB1),
                                            CircleShape
                                        )
                                )
                            },
                            text = { Text(opt.label, fontWeight = FontWeight.SemiBold) },
                            onClick = { onPick(opt.code) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    m.effectiveCarrierName?.let { "NEW · ${it.uppercase()}" } ?: "ADD A PACKAGE",
                    color = if (m.effectiveCarrierName != null) accent else ShipColors.faint,
                    fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                )
                CardTextField(m.name, onName, "Package name")
                CardTextField(m.tracking, onTracking, "Tracking number", mono = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(
                    if (m.effectiveAccentHex != null) accent else Color(0xFFD8D3CA),
                    "✓",
                    onAdd
                )
                SmallActionButton(ShipColors.card, "✕", onClear, outlined = true)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 70.dp, horizontal = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEDEBE4)),
            contentAlignment = Alignment.Center
        ) { Text("📦", fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Text(
            text, color = ShipColors.faint, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 21.sp
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_Empty() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "0 arriving soon",
                emptyText = "No active deliveries right now.",
                manualAdd = ManualAddUi(
                    options = listOf(
                        CarrierOption(null, "Auto-detect", null),
                        CarrierOption("ups", "UPS", "#5A3A22"),
                        CarrierOption("usps", "USPS", "#1E3A8F"),
                        CarrierOption("fedex", "FedEx", "#5A1B9A"),
                    ),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_PopulatedWithRings() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "5 arriving soon",
                cards = previewParcelCards.subList(0, 6),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_Delivered() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "0 arriving soon",
                cards = previewParcelCards.subList(5, 7),
                toast = ToastUi("Package archived", showUndo = true),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_ArchivedTab() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "2 packages archived",
                tab = ListTab.ARCHIVED,
                cards = previewParcelCards.subList(5, 7),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_RefreshingAndUnrefreshed() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "2 arriving soon",
                cards = listOf(
                    // Mid-refresh: spinner next to the status text.
                    previewParcelCards[0].copy(refreshing = true),
                    // Never refreshed: no ring yet, just the waiting status.
                    ParcelCardUi(
                        "8", "Wool socks", "UPS", "#5A3A22", "Waiting for first update",
                        delivered = false, ring = null, urgent = false, refreshing = true
                    ),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_ListContent_PendingImport() {
    ShipTheme {
        ListContent(
            ListUiState(
                dateLabel = "Fri, Jul 11",
                headerSub = "1 arriving soon",
                pendingImport = PendingImportUi(
                    carrierName = "USPS",
                    accentHex = "#1E3A8F",
                    tracking = "9400 1118 9922 3300 1122",
                    name = "",
                ),
                cards = previewParcelCards.subList(0, 0),
            ),
        )
    }
}

private val previewParcelCards = listOf(
    ParcelCardUi(
        "1", "Baseball cap", "USPS", "#1E3A8F", "In transit",
        delivered = false, ring = RingUi(2, 0.5f), urgent = false
    ),
    ParcelCardUi(
        "2", "Trail running shoes", "FedEx", "#5A1B9A", "Out for delivery",
        delivered = false, ring = RingUi(1, 0.75f), urgent = true
    ),
    ParcelCardUi(
        "3", "Mechanical keyboard", "UPS", "#5A3A22", "In transit",
        delivered = false, ring = RingUi(5, 0.5f), urgent = false
    ),
    ParcelCardUi(
        "4", "Ceramic desk lamp", "UPS", "#5A3A22", "Out for delivery today",
        delivered = false, ring = RingUi(0, 0.75f), urgent = true
    ),
    ParcelCardUi(
        "5", "Clear phone case", "USPS", "#1E3A8F", "Shipped",
        delivered = false, ring = RingUi(4, 0.25f), urgent = false
    ),
    ParcelCardUi(
        id = "6",
        name = "Oat-blend coffee beans",
        carrierName = "USPS",
        accentHex = "#1E3A8F",
        statusText = "Delivered",
        delivered = true,
        ring = null,
        urgent = false
    ),
    ParcelCardUi(
        id = "7",
        name = "Paperback — The Overstory",
        carrierName = "FedEx",
        accentHex = "#5A1B9A",
        statusText = "Delivered",
        delivered = true,
        ring = null,
        urgent = false
    ),
)