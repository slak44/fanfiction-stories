package slak.fanfictionstories

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import slak.fanfictionstories.databinding.DialogLocalFilterBinding

enum class TimeUnitFilter {
  DAYS, MONTHS, YEARS
}

data class LocalStoryFilter(
    var updateTime: Int? = null,
    var updateTimeUnit: TimeUnitFilter? = null,
    var publishTime: Int? = null,
    var publishTimeUnit: TimeUnitFilter? = null
)

fun Context.openFilterStoriesDialog(action: (newFilters: LocalStoryFilter) -> Unit) {
  val binding = DialogLocalFilterBinding.inflate(LayoutInflater.from(this))

  val filters = LocalStoryFilter()

  AlertDialog.Builder(this)
      .setTitle(R.string.filter_by)
      .setPositiveButton(R.string.local_filter_btn) { dialog, _ ->
        action(filters)
        dialog.dismiss()
      }
      .setView(binding.root)
      .show()
}