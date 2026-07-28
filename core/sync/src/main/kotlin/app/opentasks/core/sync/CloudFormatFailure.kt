package app.opentasks.core.sync

enum class CloudFormatFailure {
    MALFORMED,
    UNSUPPORTED_FORMAT,
    LIMIT_EXCEEDED,
    LENGTH_MISMATCH,
    CHECKSUM_MISMATCH,
    TRUNCATED,
}

class CloudFormatException(
    val failure: CloudFormatFailure,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
