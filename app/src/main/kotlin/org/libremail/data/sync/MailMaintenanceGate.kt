// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide lock serializing the two background maintenance jobs that both touch older cached
 * history: the full-history backfill ([MailBackfiller], #12) and the retention pruner
 * ([MailPruner], #13).
 *
 * The primary correctness guarantee is the retention *floor* — backfill never pages below the limit
 * and pruning only deletes below it, so their working sets are disjoint. This mutex is defence in
 * depth: it guarantees they never interleave even if their views of the floor momentarily disagree,
 * so a prune can never delete a message a backfill is mid-write on. Foreground sync / pull-to-refresh
 * are intentionally NOT gated here (they use [MailSyncer]'s own mutex) so the UI stays responsive.
 */
@Singleton
class MailMaintenanceGate @Inject constructor() {
    val mutex = Mutex()
}
