package slak.fanfictionstories.utility

import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.util.Log
import kotlin.reflect.KClass

private const val TAG = "ViewModelWorkaroundActivity"

/** Works around [this](https://issuetracker.google.com/issues/73644080) android bug. */
abstract class ViewModelWorkaroundActivity<T : ViewModel>(
    private val klass: KClass<T>) : ActivityWithStatic() {
  protected lateinit var viewModel: T

  /** Source: https://issuetracker.google.com/issues/73644080#comment12 */
  override fun onDestroy() {
    val current = ViewModelProviders.of(this).get(klass.java)
    super.onDestroy()
    @Suppress("UNCHECKED_CAST")
    val new = ViewModelProviders.of(this, object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>) = current as T
    }).get(klass.java)
    if (current !== new) {
      Log.e(TAG, "Working around android bug")
      viewModel = new
    }
  }
}

/** Works around [this](https://issuetracker.google.com/issues/73644080) android bug. */
abstract class ViewModelWorkaroundLoadingActivity<T : ViewModel>(
    private val klass: KClass<T>,
    idxInToolbarLayout: Int = -1
) : LoadingActivity(idxInToolbarLayout) {
  protected lateinit var viewModel: T

  /** Source: https://issuetracker.google.com/issues/73644080#comment12 */
  override fun onDestroy() {
    val current = ViewModelProviders.of(this).get(klass.java)
    super.onDestroy()
    @Suppress("UNCHECKED_CAST")
    val new = ViewModelProviders.of(this, object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>) = current as T
    }).get(klass.java)
    if (current !== new) {
      Log.e(TAG, "Working around android bug")
      viewModel = new
    }
  }
}
