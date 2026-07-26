package app.opentasks.core.sync

data class VersionedField<T>(
    val value: T,
    val timestamp: HlcTimestamp,
)

data class TombstonedValue<T>(
    val value: T?,
    val timestamp: HlcTimestamp,
    val deleted: Boolean,
)

data class SetMutation<T>(
    val value: T,
    val timestamp: HlcTimestamp,
    val present: Boolean,
)

object MergeRules {
    fun <T> scalar(first: VersionedField<T>, second: VersionedField<T>): VersionedField<T> =
        if (first.timestamp >= second.timestamp) first else second

    fun <T> tombstone(
        first: TombstonedValue<T>,
        second: TombstonedValue<T>,
    ): TombstonedValue<T> = when {
        first.timestamp > second.timestamp -> first
        second.timestamp > first.timestamp -> second
        first.deleted -> first
        else -> second
    }

    fun <T> setMembership(mutations: Collection<SetMutation<T>>): Set<T> =
        mutations
            .groupBy(SetMutation<T>::value)
            .mapValues { (_, values) ->
                values.maxWithOrNull(compareBy(SetMutation<T>::timestamp))!!
            }
            .filterValues(SetMutation<T>::present)
            .keys

    fun fractionalRankBetween(before: String?, after: String?): String {
        if (before == null && after == null) return "m"
        if (before == null) return "${after!!.first().code / 2}".padStart(3, '0')
        if (after == null) return "$before~"
        return "$before|$after"
    }
}

data class CloudFormatHeader(
    val schemaVersion: Int,
    val cryptoVersion: Int,
    val minimumReaderVersion: Int,
    val vaultId: String,
    val checksum: String,
) {
    fun requireReadable(readerVersion: Int) {
        require(readerVersion >= minimumReaderVersion) {
            "Reader $readerVersion cannot open minimum version $minimumReaderVersion"
        }
    }
}
