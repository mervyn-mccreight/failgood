package failgood.internal.util

class StringUtils

internal fun pluralize(count: Int, item: String) = if (count == 1) "1 $item" else "$count ${item}s"
