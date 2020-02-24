package slak.fanfictionstories.utility

import android.app.Activity
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import slak.fanfictionstories.R

/** This interface is used to control a loading progress bar. */
interface IHasLoadingBar {
  val loading: ProgressBar

  /** Returns whether or not the loading bar is currently visible. */
  @UiThread
  fun isLoading(): Boolean = loading.visibility == View.VISIBLE

  /** Make the loading bar visible. */
  @UiThread
  fun showLoading() {
    loading.visibility = View.VISIBLE
  }

  /** Hide the loading bar. */
  @UiThread
  fun hideLoading() {
    loading.visibility = View.GONE
  }
}

/**
 * Inflates a horizontal indeterminate [ProgressBar] and adds it to the bottom of the action bar.
 *
 * Should be called _after_ [AppCompatActivity.setSupportActionBar].
 *
 * The progress bar view's id is [R.id.activityProgressBar].
 * @param toolbar the activity's [Toolbar]
 * @param idxInToolbarLayout certain implementations may have other views around here, so this parameter is provided for
 * choose where to insert the loader ([ViewGroup.addView]'s index arg)
 */
fun <T> T.setLoadingView(toolbar: Toolbar, idxInToolbarLayout: Int = -1) where T : Activity, T: IHasLoadingBar {
  val progress = layoutInflater.inflate(
      R.layout.loading_activity_indeterminate, toolbar.parent as ViewGroup, false)
  (toolbar.parent as ViewGroup).addView(progress, idxInToolbarLayout)
}
