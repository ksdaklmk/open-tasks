package app.opentasks.core.model

enum class TaskCsvField {
    TITLE,
    PROJECT,
    STATUS,
    PRIORITY,
    START,
    DUE,
    COMPLETION,
    ESTIMATE,
    TAGS,
    DESCRIPTION,
}

enum class TaskCsvStatusChoice { BACKLOG, IN_PROGRESS, DONE, IGNORE }

enum class TaskCsvPriorityChoice { NONE, LOW, MEDIUM, HIGH, URGENT, IGNORE }

enum class TaskCsvDateOrder { DAY_MONTH_YEAR, MONTH_DAY_YEAR }

enum class TaskCsvEstimateUnit { MINUTES, HOURS }

enum class TaskCsvTagMode(val separator: Char?) {
    COMMA(','),
    SEMICOLON(';'),
    PIPE('|'),
    SINGLE(null),
}

data class TaskCsvMapping(
    val columns: Map<TaskCsvField, Int> = emptyMap(),
    val statusChoices: Map<String, TaskCsvStatusChoice> = emptyMap(),
    val priorityChoices: Map<String, TaskCsvPriorityChoice> = emptyMap(),
    val dateOrder: TaskCsvDateOrder? = null,
    val estimateUnit: TaskCsvEstimateUnit? = null,
    val tagMode: TaskCsvTagMode? = null,
)

enum class TaskCsvWarningReason {
    EMPTY_ROW,
    TITLE_BLANK,
    TITLE_TOO_LONG,
    PROJECT_OMITTED,
    PROJECT_CASE_MERGED,
    STATUS_OMITTED,
    STATUS_FALLBACK,
    PRIORITY_OMITTED,
    START_OMITTED,
    START_TIME_INFERRED,
    START_ZONE_INFERRED,
    DUE_OMITTED,
    DUE_TIME_INFERRED,
    DUE_ZONE_INFERRED,
    COMPLETION_OMITTED,
    COMPLETION_TIME_INFERRED,
    COMPLETION_ZONE_INFERRED,
    COMPLETION_INFERRED,
    COMPLETION_OVERRIDES_STATUS,
    ESTIMATE_OMITTED,
    TAG_BLANK_OMITTED,
    TAG_TOO_LONG_OMITTED,
    TAG_DUPLICATE_OMITTED,
    TAG_LIMIT_OMITTED,
    TAG_CASE_MERGED,
    DESCRIPTION_OMITTED,
}

data class TaskCsvWarning(
    val rowNumber: Int,
    val field: TaskCsvField?,
    val reason: TaskCsvWarningReason,
)

enum class TaskCsvBlockingIssue {
    TITLE_MAPPING_REQUIRED,
    COLUMN_MAPPING_INVALID,
    STATUS_CHOICES_REQUIRED,
    PRIORITY_CHOICES_REQUIRED,
    DATE_ORDER_REQUIRED,
    ESTIMATE_UNIT_REQUIRED,
    TAG_MODE_REQUIRED,
    NO_VALID_TASKS,
    TOO_MANY_NEW_PROJECTS,
    TOO_MANY_NEW_TAGS,
    TARGET_REJECTED,
}
