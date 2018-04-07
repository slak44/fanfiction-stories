package slak.fanfictionstories

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import android.util.TypedValue
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.fetchers.Language
import slak.fanfictionstories.utility.NetworkType
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.str
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/** A metric ton of boilerplate with interfacing with various [SharedPreferences] data. */
object Prefs {
  private const val TAG = "PrefsObject"

  const val PREFS_FILE = "slak.fanfictionstories.SHARED_PREFERENCES"

  const val RESUME_STORY_ID = "resume_story_id"
  const val REMEMBER_LANG_ID = "remember_lang_id"
  const val LIST_GROUP_STRATEGY = "list_group_strategy"
  const val LIST_ORDER_STRATEGY = "list_order_strategy"
  const val LIST_ORDER_IS_REVERSE = "list_order_strategy_rev"
  const val AUTHOR_LIST_GROUP_STRATEGY = "author_list_group_strategy"
  const val AUTHOR_LIST_ORDER_STRATEGY = "author_list_order_strategy"
  const val AUTHOR_LIST_ORDER_IS_REVERSE = "author_list_order_strategy_rev"

  var storyListGroupStrategy
    get() = GroupStrategy[Static.prefs.getInt(LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    set(new) = use { it.putInt(LIST_GROUP_STRATEGY, new.ordinal) }

  var storyListOrderStrategy
    get() = OrderStrategy[Static.prefs.getInt(LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    set(new) = use { it.putInt(LIST_ORDER_STRATEGY, new.ordinal) }

  var storyListOrderDirection
    get() = OrderDirection[Static.prefs.getInt(LIST_ORDER_IS_REVERSE, OrderDirection.DESC.ordinal)]
    set(new) = use { it.putInt(LIST_ORDER_IS_REVERSE, new.ordinal) }

  fun storyListArrangement() =
      Arrangement(storyListOrderStrategy, storyListOrderDirection, storyListGroupStrategy)

  var authorGroupStrategy
    get() = GroupStrategy[Static.prefs.getInt(AUTHOR_LIST_GROUP_STRATEGY, GroupStrategy.NONE.ordinal)]
    set(new) = use { it.putInt(AUTHOR_LIST_GROUP_STRATEGY, new.ordinal) }

  var authorOrderStrategy
    get() = OrderStrategy[Static.prefs.getInt(AUTHOR_LIST_ORDER_STRATEGY, OrderStrategy.TITLE_ALPHABETIC.ordinal)]
    set(new) = use { it.putInt(AUTHOR_LIST_ORDER_STRATEGY, new.ordinal) }

  var authorOrderDirection
    get() = OrderDirection[Static.prefs.getInt(AUTHOR_LIST_ORDER_IS_REVERSE, OrderDirection.DESC.ordinal)]
    set(new) = use { it.putInt(AUTHOR_LIST_ORDER_IS_REVERSE, new.ordinal) }

  fun authorArrangement() =
      Arrangement(authorOrderStrategy, authorOrderDirection, authorGroupStrategy)

  fun textSize() = Static.defaultPrefs.getString(
      str(R.string.key_option_size), str(R.string.option_size_default)).toFloat()

  fun textColor(theme: Resources.Theme) = Static.defaultPrefs.getInt(
      str(R.string.key_option_color), Static.res.getColor(R.color.textDefault, theme))

  fun textFontName() = Static.defaultPrefs.getString(
      str(R.string.key_option_font), str(R.string.option_font_default))

  fun textFont() = Typeface.create(textFontName(), Typeface.NORMAL)

  fun textAntiAlias() = Static.defaultPrefs.getBoolean(
      str(R.string.key_option_antialias), str(R.string.option_antialias_default).toBoolean())

  private var textPaintCache: TextPaint? = null
  private var fontNameCache: String? = null

  fun textPaint(theme: Resources.Theme): TextPaint {
    val color = textColor(theme)
    val size =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize(), Static.res.displayMetrics)
    val antialias = textAntiAlias()
    val fontName = textFontName()
    // Try to return the same instance if its params haven't changed, because [Paint] uses native
    // calls, and these are annoyingly slow, plus returning a new instance every time breaks
    // equality
    if (textPaintCache != null &&
        textPaintCache!!.textSize == size &&
        textPaintCache!!.color == color &&
        textPaintCache!!.isAntiAlias == antialias &&
        fontNameCache == fontName) {
      Log.v(TAG, "Reusing TextPaint instance ${textPaintCache}")
      return textPaintCache!!
    }
    val tp = TextPaint()
    tp.color = color
    tp.typeface = textFont()
    tp.textSize = size
    tp.isAntiAlias = antialias
    fontNameCache = fontName
    textPaintCache = tp
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

  fun filterLanguage() = Static.defaultPrefs.getBoolean(str(R.string.key_option_lang_mem),
      str(R.string.option_lang_mem_default).toBoolean())

  fun preferredLanguage(): Language =
      Language.values()[Static.defaultPrefs.getInt(REMEMBER_LANG_ID, Language.ALL.ordinal)]

  fun locale() = Static.defaultPrefs.getString(
      str(R.string.key_option_locale), str(R.string.option_locale_default))

  val simpleDateFormatter: DateFormat
    get() {
      val localeStr = locale()
      val locale = if (localeStr == str(R.string.option_locale_default)) {
        Locale.getDefault()
      } else {
        Locale(localeStr)
      }
      return SimpleDateFormat.getDateInstance(SimpleDateFormat.DEFAULT, locale)
    }

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
