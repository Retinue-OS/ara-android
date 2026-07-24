package os.retinue.ara.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.retinue.ara.state.JobRecord

/**
 * Process-global bridge between the [PollingService] (which owns polling) and
 * the UI. Both run in the same process, so a shared observable is a simple,
 * correct way for the Activity/ViewModel to "observe the polling service"
 * without binder plumbing. The durable copy of truth is still DataStore; this is
 * the live channel.
 */
object JobBus {
    private val _job = MutableStateFlow<JobRecord?>(null)
    val job: StateFlow<JobRecord?> = _job.asStateFlow()

    /** Submit-stage failures that occur before any [JobRecord] exists. */
    private val _error = MutableStateFlow<ServiceError?>(null)
    val error: StateFlow<ServiceError?> = _error.asStateFlow()

    fun publish(record: JobRecord?) {
        _job.value = record
    }

    fun publishError(error: ServiceError) {
        _error.value = error
    }

    fun clearError() {
        _error.value = null
    }
}

/** Failure kinds surfaced by the service, mapped to user-facing strings by the UI. */
enum class ServiceErrorKind {
    MissingCertificate,
    CertificateUnavailable,
    Tls,
    Submit,
    BadJobUri,
    Blocked,
    ServiceStartFailed,
    NoEndpoint,
}

data class ServiceError(val kind: ServiceErrorKind, val message: String? = null)

/**
 * Process-level foreground flag, maintained from [os.retinue.ara.AraApp] via the
 * process lifecycle. The service reads it to decide whether a completed job
 * warrants a background answer-ready notification.
 */
object AraForeground {
    @Volatile
    var inForeground: Boolean = false
}
