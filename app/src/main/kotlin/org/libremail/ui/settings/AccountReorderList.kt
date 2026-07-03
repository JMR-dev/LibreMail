// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.libremail.R
import org.libremail.domain.model.Account
import kotlin.math.roundToInt

/**
 * The Settings accounts list with long-press drag-to-reorder (issue #164). Tapping a row still opens
 * that account; a long-press lifts it and the drag follows the finger, and on release the new
 * top-to-bottom order is persisted via [onReorder] (the account ids, in order). Every surface that
 * lists accounts — this list, the drawer switcher, the unified-inbox filter chips — reads the same
 * `ORDER BY sortOrder` query, so they all follow the committed order automatically.
 *
 * Built with `pointerInput` + `Modifier.offset` over a plain [Column] rather than a `LazyColumn` or a
 * reorderable-list dependency: the account list is tiny (a handful of rows), it lives inside an
 * already-scrolling settings column (a nested lazy list can't share that scroll), and the repo
 * prefers no unnecessary dependencies. Only the dragged row moves during the gesture; the list
 * commits to the dropped position on release, so no mid-drag recomposition can cancel the gesture.
 */
@Composable
internal fun AccountReorderList(
    accounts: List<Account>,
    onOpenAccount: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    // Local working order, re-seeded from upstream whenever the account set changes (add / remove / an
    // external reorder) via the remember key. A drag never changes [accounts], so it stays put until
    // the drop optimistically sets the new order — which upstream then echoes back identically.
    var order by remember(accounts) { mutableStateOf(accounts) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth()) {
        order.forEach { account ->
            val dragging = account.id == draggingId
            AccountReorderRow(
                id = account.id,
                email = account.email,
                dragging = dragging,
                offsetY = if (dragging) dragOffsetY.roundToInt() else 0,
                onMeasured = { rowHeightPx = it },
                onOpen = { onOpenAccount(account.id) },
                onDragStart = {
                    draggingId = account.id
                    dragOffsetY = 0f
                },
                onDrag = { delta -> dragOffsetY += delta },
                onDragEnd = {
                    commitDrag(order, account.id, dragOffsetY, rowHeightPx)?.let { moved ->
                        order = moved
                        onReorder(moved.map { it.id })
                    }
                    draggingId = null
                    dragOffsetY = 0f
                },
            )
        }
    }
}

/**
 * The list [current] reordered by moving the account [id] the number of row-slots its accumulated
 * [dragOffsetY] spans (at [rowHeightPx] each), or null when the drop leaves it in place. Pure so the
 * index maths is unit-testable without a gesture.
 */
internal fun commitDrag(current: List<Account>, id: String, dragOffsetY: Float, rowHeightPx: Int): List<Account>? {
    if (rowHeightPx <= 0) return null
    val from = current.indexOfFirst { it.id == id }
    if (from == -1) return null
    val to = (from + (dragOffsetY / rowHeightPx).roundToInt()).coerceIn(0, current.lastIndex)
    if (to == from) return null
    return current.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun AccountReorderRow(
    id: String,
    email: String,
    dragging: Boolean,
    offsetY: Int,
    onMeasured: (Int) -> Unit,
    onOpen: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "accountDragElevation")
    Surface(
        color = if (dragging) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        tonalElevation = elevation,
        shadowElevation = elevation,
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, offsetY) }
            .onSizeChanged { onMeasured(it.height) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                // Keyed on the account id so the gesture restarts only when this slot's account
                // changes (an add/remove/re-seed) — never mid-drag, when the order is held stable.
                .pointerInput(id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    )
                }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = email, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.account_reorder_handle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
