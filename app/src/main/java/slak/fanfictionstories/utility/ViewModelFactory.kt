package slak.fanfictionstories.utility

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * This class is just a big fat hack that uses reflection to allow [ViewModel] subclasses to be passed parameters.
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
