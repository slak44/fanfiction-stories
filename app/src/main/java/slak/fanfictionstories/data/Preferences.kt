package slak.fanfictionstories.data

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Typeface
import android.text.Layout
import org.threeten.bp.LocalTime
import org.threeten.bp.ZoneId
import org.threeten.bp.ZonedDateTime
import slak.fanfictionstories.*
import slak.fanfictionstories.data.fetchers.Language
import slak.fanfictionstories.utility.*
import slak.fanfictionstories.utility.Optional
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

/** A metric ton of boilerplate for interfacing with various [SharedPreferences] data. */
object Prefs {
  const val PREFS_FILE = "slak.fanfictionstories.SHARED_PREFERENCES"

  private const val LIST_ORDER_IS_REVERSE = "list_order_strategy_rev"
  private const val LIST_GROUP_STRATEGY = "list_group_strategy"
  private const val LIST_ORDER_STRATEGY = "list_order_strategy"
  private const val AUTHOR_LIST_GROUP_STRATEGY = "author_list_group_strategy"
  private const val AUTHOR_LIST_ORDER_STRATEGY = "author_list_order_strategy"
  private const val AUTHOR_LIST_ORDER_IS_REVERSE = "author_list_order_strategy_rev"

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

  private const val RESUME_STORY_ID = "resume_story_id"
  const val NO_RESUME_STORY = -3954L
  var resumeStoryId: StoryId
    get() = Static.prefs.getLong(RESUME_STORY_ID, NO_RESUME_STORY)
    set(new) = use { it.putLong(RESUME_STORY_ID, new) }

  private const val REMEMBER_LANG_ID = "remember_lang_id"
  var preferredLanguage: Language
    get() = Language.values()[Static.prefs.getInt(REMEMBER_LANG_ID, Language.ALL.ordinal)]
    set(new) = use { it.putInt(REMEMBER_LANG_ID, new.ordinal) }

  fun textSize() = Static.defaultPrefs.getString(
      str(R.string.key_option_size), str(R.string.option_size_default))!!.toFloat()

  fun textColor(theme: Resources.Theme) = Static.defaultPrefs.getInt(
      str(R.string.key_option_color), Static.res.getColor(R.color.textDefault, theme))

  fun textFontName(): String = Static.defaultPrefs.getString(
      str(R.string.key_option_font), str(R.string.option_font_default))!!

  fun textFont(): Typeface = Typeface.create(textFontName(), Typeface.NORMAL)

  fun textAntiAlias() = Static.defaultPrefs.getBoolean(
      str(R.string.key_option_antialias), str(R.string.option_antialias_default).toBoolean())

  fun textBreakStrategy() = when (Static.defaultPrefs.getString(
      str(R.string.key_option_break_strategy), str(R.string.option_break_strategy_default))) {
    "simple" -> Layout.BREAK_STRATEGY_SIMPLE
    "balanced" -> Layout.BREAK_STRATEGY_BALANCED
    "high_quality" -> Layout.BREAK_STRATEGY_HIGH_QUALITY
    else -> throw IllegalStateException("The string values are out of sync with this function")
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
        str(R.string.key_option_update_time), str(R.string.option_update_time_default))!!
    val (hour, minute) = updateTime.split(":").map { it.toInt() }
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    return ZonedDateTime.of(now.toLocalDate(), LocalTime.of(hour, minute), ZoneId.systemDefault())
  }

  fun filterLanguage() = Static.defaultPrefs.getBoolean(str(R.string.key_option_lang_mem),
      str(R.string.option_lang_mem_default).toBoolean())

  fun locale(): String = Static.defaultPrefs.getString(
      str(R.string.key_option_locale), str(R.string.option_locale_default))!!

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

  private const val UPDATE_RESUME_INDEX = "update_resume_index"
  private const val NO_RESUME = -982
  var updateResumeIndex: Optional<Int>
    get() {
      val idx = Static.prefs.getInt(UPDATE_RESUME_INDEX, NO_RESUME)
      return if (idx == NO_RESUME) Empty() else idx.opt()
    }
    set(new) = use { it.putInt(UPDATE_RESUME_INDEX, new.orElse { NO_RESUME }) }

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
