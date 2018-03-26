package slak.fanfictionstories.utility

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import kotlinx.android.parcel.RawValue

/** Replacement for [java.util.Optional] using a sealed class. Is [Parcelable] if [E] is. */
sealed class Optional2<E> {
  /**
   * Gets the stored value.
   * @throws NoSuchElementException if the optional is empty
   * @returns the stored value
   */
  fun get(): E = when (this) {
    is Empty -> throw NoSuchElementException("This optional is empty")
    is Value<E> -> value
  }

  /**
   * Execute code only if the value is present.
   * @param block the code to execute, receives the optional's value
   */
  inline fun ifPresent(block: (E) -> Unit) {
    if (this is Empty) return
    else block(get())
  }

  /** @returns the optional value if it exists, null otherwise */
  fun orNull(): E? = if (this is Empty) null else get()

  /** @returns the optional value if it exists or [other] if it doesn't */
  fun orElse(other: E) = if (this is Empty) other else get()

  /** @returns the optional value if it exists or [block]'s application otherwise */
  inline fun orElse(block: () -> E) = if (this is Empty) block() else get()

  /**
   * @throws Throwable [th]
   * @returns the optional's value
   */
  fun orElseThrow(th: Throwable): E = if (this is Empty) throw th else get()
}

@Parcelize
class Empty<T> : Optional2<T>(), Parcelable
@Parcelize
class Value<T>(val value: @RawValue T) : Optional2<T>(), Parcelable

/** Convenience extension fun for creating optional objects. */
fun <T> T?.opt2(): Optional2<T> = if (this == null) Empty() else Value(this)
