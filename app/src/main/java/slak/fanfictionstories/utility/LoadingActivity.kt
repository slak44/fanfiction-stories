package slak.fanfictionstories.utility

import android.support.v7.widget.Toolbar
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import slak.fanfictionstories.R

open class LoadingActivity(private val idxInToolbarLayout: Int = -1) : ActivityWithStatic() {
  override fun setSupportActionBar(toolbar: Toolbar?) {
    super.setSupportActionBar(toolbar)
    val progress = layoutInflater.inflate(
        R.layout.loading_activity_indeterminate, toolbar!!.parent as ViewGroup, false)
    (toolbar.parent as ViewGroup).addView(progress, idxInToolbarLayout)
  }
  fun showLoading() {
    activityProgressBar.visibility = View.VISIBLE
  }
  fun hideLoading() {
    activityProgressBar.visibility = View.GONE
  }
}
