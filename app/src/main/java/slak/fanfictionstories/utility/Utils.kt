package slak.fanfictionstories.utility

import java.util.*

/** @see autoSuffixNumber */
private val siSuffixes = TreeMap(mapOf(
    1_000L to "K",
    1_000_000L to "M",
    1_000_000_000L to "G",
    1_000_000_000_000L to "T",
    1_000_000_000_000_000L to "P",
    1_000_000_000_000_000_000L to "E"
))

/**
 * Truncate a number and append the respective SI suffix.
 *
 * Examples: 12345 -> 12K, 5257274 -> 5M
 *
 * Original Java Source: [https://stackoverflow.com/a/30661479/3329467]
 */
fun autoSuffixNumber(value: Long): String {
  // Long.MIN_VALUE == -Long.MIN_VALUE so we need an adjustment here
  if (value == Long.MIN_VALUE) return autoSuffixNumber(Long.MIN_VALUE + 1)
  if (value < 0) return "-" + autoSuffixNumber(-value)
  if (value < 1000) return value.toString() // deal with easy case

  val (divideBy, suffix) = requireNotNull(siSuffixes.floorEntry(value)) { "Missing SI suffix" }

  val truncated = value / (divideBy!! / 10) // the number part of the output times 10
  val hasDecimal = truncated < 100 && truncated / 10.0 != (truncated / 10).toDouble()
  return if (hasDecimal) "${truncated / 10.0}$suffix" else "${truncated / 10}$suffix"
}

/** @see autoSuffixNumber(Long) */
fun autoSuffixNumber(value: Int): String = autoSuffixNumber(value.toLong())
