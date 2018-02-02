package slak.fanfictionstories.utility

import android.support.v7.widget.Toolbar
import android.view.View
import android.view.ViewGroup
import kotlinx.android.synthetic.main.loading_progress_bar.*
import slak.fanfictionstories.R

open class LoadingActivity : ActivityWithStatic() {
  override fun setSupportActionBar(toolbar: Toolbar?) {
    super.setSupportActionBar(toolbar)
    layoutInflater.inflate(
        R.layout.loading_progress_bar, toolbar!!.parent as ViewGroup, true)
  }
  fun showLoading() {
    activityProgressBar.visibility = View.VISIBLE
  }
  fun hideLoading() {
    activityProgressBar.visibility = View.GONE
  }
}
