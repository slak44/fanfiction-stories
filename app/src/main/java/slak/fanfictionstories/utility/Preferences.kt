package slak.fanfictionstories.utility

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.*
import slak.fanfictionstories.fetchers.Language

object Prefs {
  const val PREFS_FILE = "slak.fanfictionstories.SHARED_PREFERENCES"

  const val RESUME_STORY_ID = "resume_story_id"
  const val REMEMBER_LANG_ID = "remember_lang_id"
  const val LIST_GROUP_STRATEGY = "list_group_strategy"
  const val LIST_ORDER_STRATEGY = "list_order_strategy"
  const val LIST_ORDER_IS_REVERSE = "list_order_strategy_rev"

  var groupStrategy
    get() = GroupStrategy[Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    set(new) = use { it.putInt(LIST_GROUP_STRATEGY, new.ordinal) }

  var orderStrategy
    get() = OrderStrategy[Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    set(new) = use { it.putInt(LIST_ORDER_STRATEGY, new.ordinal) }

  var orderDirection
    get() = OrderDirection[Static.prefs.getInt(LIST_ORDER_IS_REVERSE, OrderDirection.DESC.ordinal)]
    set(new) = use { it.putInt(LIST_ORDER_IS_REVERSE, new.ordinal) }

  fun arrangement() = Arrangement(orderStrategy, orderDirection, groupStrategy)

  fun textSize() = Static.defaultPrefs.getString(
      str(R.string.key_option_size), str(R.string.option_size_default)).toFloat()

  fun textColor(theme: Resources.Theme) = Static.defaultPrefs.getInt(
      str(R.string.key_option_color), Static.res.getColor(R.color.textDefault, theme))

  fun textFont() = Typeface.create(Static.defaultPrefs.getString(
      str(R.string.key_option_font), str(R.string.option_font_default)), Typeface.NORMAL)

  fun textAntiAlias() = Static.defaultPrefs.getBoolean(
      str(R.string.key_option_antialias), str(R.string.option_antialias_default).toBoolean())

  fun textPaint(theme: Resources.Theme): TextPaint {
    val tp = TextPaint()
    tp.color = textColor(theme)
    tp.typeface = textFont()
    tp.textSize =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize(), Static.res.displayMetrics)
    tp.isAntiAlias = textAntiAlias()
    return tp
  }

  fun autoUpdateReqNetType(): NetworkType = when (Static.defaultPrefs.getString(
      str(R.string.key_option_net_type), str(R.string.option_net_type_default))) {
    "not_roaming" -> NetworkType.NOT_ROAMING
    "not_metered" -> NetworkType.UNMETERED
    "any" -> NetworkType.ANY
    else -> throw IllegalStateException("The string values are out of sync with this function")
  }

  fun autoUpdateMoment(): ZonedDateTime {
    val updateTime: String = Static.defaultPrefs.getString(
        str(R.string.key_option_update_time), str(R.string.option_update_time_default))
    val (hour, minute) = updateTime.split(":").map { it.toInt() }
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    return ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), ZoneId.systemDefault())
  }

  fun filterLanguage() = Static.prefs.getBoolean(str(R.string.key_option_lang_mem),
      str(R.string.option_lang_mem_default).toBoolean())

  fun preferredLanguage(): Language =
      Language.values()[Static.prefs.getInt(REMEMBER_LANG_ID, Language.ALL.ordinal)]

  /**
   * Like [use], but uses [SharedPreferences.Editor.commit] instead of apply.
   * @see use
   */
  @SuppressLint("ApplySharedPref")
  fun useImmediate(block: (SharedPreferences.Editor) -> Unit) {
    val editor = Static.prefs.edit()
    block(editor)
    editor.commit()
  }

  /** Wraps [SharedPreferences]'s edit-change-apply boilerplate. */
  fun use(block: (SharedPreferences.Editor) -> Unit) {
    val editor = Static.prefs.edit()
    block(editor)
    editor.apply()
  }
}
