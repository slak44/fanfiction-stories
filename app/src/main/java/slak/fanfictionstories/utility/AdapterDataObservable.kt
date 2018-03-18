package slak.fanfictionstories.utility

import android.database.Observable
import android.support.v7.widget.RecyclerView

/**
 * For use with delegation when a class needs [AdapterDataObservable] and can't inherit from it
 * because it's an abstract class.
 * @see RecyclerView.AdapterDataObservable
 */
interface IAdapterDataObservable {
  fun notifyChanged()
  fun notifyItemRangeChanged(positionStart: Int, itemCount: Int)
  fun notifyItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?)
  fun notifyItemRangeInserted(positionStart: Int, itemCount: Int)
  fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int)
  fun notifyItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int)
  fun registerObserver(observer: RecyclerView.AdapterDataObserver)
  fun unregisterAll()
  fun unregisterObserver(observer: RecyclerView.AdapterDataObserver)
}

/**
 * Can notify a bunch of [RecyclerView.AdapterDataObserver] of changes.
 * @see IAdapterDataObservable
 * @see RecyclerView.AdapterDataObservable
 * @see RecyclerView.AdapterDataObserver
 */
class AdapterDataObservable :
    Observable<RecyclerView.AdapterDataObserver>(), IAdapterDataObservable {
  override fun notifyChanged() = mObservers.forEach { it.onChanged() }

  override fun notifyItemRangeChanged(positionStart: Int, itemCount: Int) =
      mObservers.forEach { it.onItemRangeChanged(positionStart, itemCount) }

  override fun notifyItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
      mObservers.forEach { it.onItemRangeChanged(positionStart, itemCount, payload) }

  override fun notifyItemRangeInserted(positionStart: Int, itemCount: Int) =
      mObservers.forEach { it.onItemRangeInserted(positionStart, itemCount) }

  override fun notifyItemRangeRemoved(positionStart: Int, itemCount: Int) =
      mObservers.forEach { it.onItemRangeRemoved(positionStart, itemCount) }

  override fun notifyItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
      mObservers.forEach { it.onItemRangeMoved(fromPosition, toPosition, itemCount) }
}
