package slak.fanfictionstories.utility

import android.support.annotation.UiThread
import android.support.v7.widget.Toolbar
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import slak.fanfictionstories.R

/**
 * This activity adds a horizontal indeterminate progress bar to the bottom of the action bar.
 * @param idxInToolbarLayout certain implementations may have other views around here, so this
 * parameter is provided to choose where to insert the loader ([ViewGroup.addView]'s index arg)
 */
open class LoadingActivity(private val idxInToolbarLayout: Int = -1) : ActivityWithStatic() {
  override fun setSupportActionBar(toolbar: Toolbar?) {
    super.setSupportActionBar(toolbar)
    val progress = layoutInflater.inflate(
        R.layout.loading_activity_indeterminate, toolbar!!.parent as ViewGroup, false)
    (toolbar.parent as ViewGroup).addView(progress, idxInToolbarLayout)
  }

  /** Make the loading bar visible. */
  @UiThread
  open fun showLoading() {
    activityProgressBar.visibility = View.VISIBLE
  }

  /** Hide the loading bar. */
  @UiThread
  open fun hideLoading() {
    activityProgressBar.visibility = View.GONE
  }
}
