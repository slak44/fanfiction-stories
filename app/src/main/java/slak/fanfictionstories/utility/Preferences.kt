package slak.fanfictionstories.utility

import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import slak.fanfictionstories.*

object Prefs {
  const val PREFS_FILE = "slak.fanfictionstories.SHARED_PREFERENCES"

  const val RESUME_STORY_ID = "resume_story_id"
  const val LIST_GROUP_STRATEGY = "list_group_strategy"
  const val LIST_ORDER_STRATEGY = "list_order_strategy"
  const val LIST_ORDER_IS_REVERSE = "list_order_strategy_rev"

  var groupStrategy
    get() = GroupStrategy[Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_GROUP_STRATEGY, new.ordinal) } }

  var orderStrategy
    get() = OrderStrategy[Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_ORDER_STRATEGY, new.ordinal) } }

  var orderDirection
    get() = OrderDirection[Static.prefs.getInt(LIST_ORDER_IS_REVERSE, OrderDirection.DESC.ordinal)]
    set(new) { usePrefs { it.putInt(LIST_ORDER_IS_REVERSE, new.ordinal) } }

  fun arrangement() = Arrangement(orderStrategy, orderDirection, groupStrategy)

  const val TEXT_SIZE = "font_option_size"
  const val TEXT_FONT = "font_option_type"
  const val TEXT_COLOR = "font_option_color"
  const val TEXT_ANTIALIAS = "font_option_antialias"

  fun textSize() = Static.defaultPrefs.getString(Prefs.TEXT_SIZE, "14").toFloat()
  fun textColor(theme: Resources.Theme) =
      Static.defaultPrefs.getInt(Prefs.TEXT_COLOR, Static.res.getColor(R.color.textDefault, theme))
  fun textFont() = Typeface.create(
      Static.defaultPrefs.getString(Prefs.TEXT_FONT, "Roboto"), Typeface.NORMAL)
  fun textAntiAlias() = Static.defaultPrefs.getBoolean(Prefs.TEXT_ANTIALIAS, true)

  fun textPaint(theme: Resources.Theme): TextPaint {
    val tp = TextPaint()
    tp.color = textColor(theme)
    tp.typeface = textFont()
    tp.textSize =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize(), Static.res.displayMetrics)
    tp.isAntiAlias = textAntiAlias()
    return tp
  }
}
