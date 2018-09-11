package slak.fanfictionstories.utility

import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.support.v4.app.FragmentActivity

/**
 * This class is just a big fat hack that uses reflection to allow [ViewModel] subclasses to be
 * passed parameters via [obtainViewModel].
 */
@PublishedApi
internal class ViewModelFactory(private vararg val parameters: Any) :
    ViewModelProvider.NewInstanceFactory() {
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return modelClass
        .getConstructor(*parameters.map { it::class.java }.toTypedArray())
        .newInstance(*parameters)
  }
}

/**
 * Gets the [ViewModel] for this [FragmentActivity].
 *
 * NOTE: Any primitive types in the [ViewModel]'s constructor must be forced to the object versions
 * from `java.lang` (or an error is thrown).
 */
inline fun <reified T : ViewModel> FragmentActivity.obtainViewModel(vararg params: Any): T {
  return ViewModelProviders.of(this, ViewModelFactory(*params)).get(T::class.java)
}

