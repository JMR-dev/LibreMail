// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

import javax.inject.Qualifier

/**
 * Qualifies the debug-report ingest endpoint URL — `BuildConfig.DEBUG_REPORT_ENDPOINT`, empty by
 * default because the ingest server (issue #34) is out of scope for this repo. Injecting it, rather
 * than reading the `BuildConfig` static inline in [ReportUploadWorker], keeps the submit path — which
 * the empty default otherwise leaves unreachable — testable by pointing the worker at a local server
 * (issue #257).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DebugReportEndpoint
