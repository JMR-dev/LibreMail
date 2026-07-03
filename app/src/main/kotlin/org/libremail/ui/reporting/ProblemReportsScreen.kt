// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reporting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.libremail.R
import org.libremail.reporting.ReportKind
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProblemReportsScreen(
    onBack: () -> Unit,
    onOpenReport: (String) -> Unit,
    viewModel: ProblemReportsViewModel = hiltViewModel(),
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()

    // A newly created manual report opens straight into review.
    LaunchedEffect(Unit) {
        viewModel.created.collect { onOpenReport(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reports_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Button(
                onClick = viewModel::createManualReport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(stringResource(R.string.reports_create))
            }
            if (reports.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.reports_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(reports, key = { it.id }) { report ->
                        ReportRow(report = report, onClick = { onOpenReport(report.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportRow(report: ReportSummary, onClick: () -> Unit) {
    val kindLabel = when (report.kind) {
        ReportKind.CRASH -> stringResource(R.string.report_kind_crash)
        ReportKind.MANUAL -> stringResource(R.string.report_kind_manual)
    }
    val timestamp = remember(report.createdAtMillis) {
        DateFormat.getDateTimeInstance().format(Date(report.createdAtMillis))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(kindLabel, style = MaterialTheme.typography.bodyLarge)
        Text(
            timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
