package slak.fanfictionstories

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import slak.fanfictionstories.data.fetchers.Genre
import slak.fanfictionstories.databinding.DialogLocalFilterBinding
import slak.fanfictionstories.utility.onSelect

enum class TimeUnitFilter {
  DAYS, MONTHS, YEARS
}

data class LocalStoryFilter(
    var updateTime: Int? = null,
    var updateTimeUnit: TimeUnitFilter? = null,
    var publishTime: Int? = null,
    var publishTimeUnit: TimeUnitFilter? = null,
    var genre1: Genre? = null,
    var genre2: Genre? = null,
)

fun Context.openFilterStoriesDialog(filters: LocalStoryFilter, action: (newFilters: LocalStoryFilter) -> Unit) {
  val binding = DialogLocalFilterBinding.inflate(LayoutInflater.from(this))

  val newFilters = filters.copy()

  binding.publishTimeValue.setText(filters.publishTime?.toString() ?: "")
  binding.updateTimeValue.setText(filters.updateTime?.toString() ?: "")

  binding.genre1.setSelection(Genre.values().indexOf(filters.genre1))
  binding.genre2.setSelection(Genre.values().indexOf(filters.genre2))
  binding.publishTimeUnit.setSelection(TimeUnitFilter.values().indexOf(filters.publishTimeUnit))
  binding.updateTimeUnit.setSelection(TimeUnitFilter.values().indexOf(filters.updateTimeUnit))

  binding.genre1.onSelect { _, pos -> newFilters.genre1 = Genre.values()[pos] }
  binding.genre2.onSelect { _, pos -> newFilters.genre2 = Genre.values()[pos] }
  binding.publishTimeUnit.onSelect { _, pos -> newFilters.publishTimeUnit = TimeUnitFilter.values()[pos] }
  binding.updateTimeUnit.onSelect { _, pos -> newFilters.updateTimeUnit = TimeUnitFilter.values()[pos] }

  AlertDialog.Builder(this)
      .setTitle(R.string.filter_by)
      .setPositiveButton(R.string.local_filter_btn) { dialog, _ ->
        newFilters.publishTime = binding.publishTimeValue.text.toString().toIntOrNull()
        newFilters.updateTime = binding.updateTimeValue.text.toString().toIntOrNull()

        action(newFilters)
        dialog.dismiss()
      }
      .setView(binding.root)
      .show()
}