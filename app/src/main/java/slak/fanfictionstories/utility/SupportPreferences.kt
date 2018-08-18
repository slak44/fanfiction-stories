package slak.fanfictionstories.utility

import android.content.Context
import android.support.v7.preference.PreferenceViewHolder
import android.util.AttributeSet
import android.widget.TextView
import com.takisoft.fix.support.v7.preference.AutoSummaryEditTextPreference
import com.takisoft.fix.support.v7.preference.EditTextPreference
import slak.fanfictionstories.R

/** Wrap [AutoSummaryEditTextPreference] due to bug. */
@Suppress("unused")
class AutoSummaryEditTextPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AutoSummaryEditTextPreference(context, attrs, defStyleAttr) {
  init {
    widgetLayoutResource = R.layout.preference_widget_2line_text
    dialogLayoutResource = R.layout.preference_dialog_edit_text
    positiveButtonText = str(R.string.ok)
    negativeButtonText = str(R.string.cancel)
  }

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    (holder.findViewById(android.R.id.title) as TextView).textSize = 16F // sp
  }
}

/** Wrap [EditTextPreference] due to bug. */
@Suppress("unused")
class EditTextPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr) {
  init {
    widgetLayoutResource = R.layout.preference_widget_2line_text
    dialogLayoutResource = R.layout.preference_dialog_edit_text
    positiveButtonText = str(R.string.ok)
    negativeButtonText = str(R.string.cancel)
  }

  override fun onBindViewHolder(holder: PreferenceViewHolder) {
    super.onBindViewHolder(holder)
    (holder.findViewById(android.R.id.title) as TextView).textSize = 16F // sp
  }
}