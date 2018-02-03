package slak.fanfictionstories

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.widget.Switch
import org.jetbrains.anko.db.MapRowParser
import slak.fanfictionstories.fetchers.Fetcher
import slak.fanfictionstories.fetchers.Fetcher.CHAPTER_TITLE_SEPARATOR
import slak.fanfictionstories.fetchers.Genre
import slak.fanfictionstories.utility.Prefs
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.autoSuffixNumber
import slak.fanfictionstories.utility.opt
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

enum class StoryStatus {
  // Incomplete metadata, will be set to remote if user wants to read it
  TRANSIENT,
  // Partially on disk
  REMOTE,
  // Fully on disk
  LOCAL;

  companion object {
    fun fromString(s: String): StoryStatus = when (s) {
      "transient" -> TRANSIENT
      "remote" -> REMOTE
      "local" -> LOCAL
      else -> throw IllegalArgumentException("Invalid arg: " + s)
    }
  }

  override fun toString(): String = when (this) {
    TRANSIENT -> "transient"
    REMOTE -> "remote"
    LOCAL -> "local"
  }

  fun toUIString(): String = when (this) {
    TRANSIENT -> throw IllegalArgumentException("Does not have an associated string")
    REMOTE -> Static.res.getString(R.string.remote)
    LOCAL -> Static.res.getString(R.string.local)
  }
}

private const val TEMP_SEPARATOR = "temporary_separator"

@Suppress("MemberVisibilityCanPrivate")
class StoryModel(val src: MutableMap<String, Any>) : Parcelable {
  var status = StoryStatus.fromString(src["status"] as String)
    set(value) {
      field = value
      src["status"] = value.toString()
    }

  // Raw data
  val storyIdRaw: Long = src["storyId"] as Long
  val wordCount: Int = (src["wordCount"] as Long).toInt()
  val reviewsCount: Int = (src["reviews"] as Long).toInt()
  val favoritesCount: Int = (src["favorites"] as Long).toInt()
  val followsCount: Int = (src["follows"] as Long).toInt()
  val authorRaw = src["author"] as String
  val authorIdRaw = src["authorid"] as Long
  val categoryRaw = src["category"] as String
  val genresRaw = src["genres"] as String
  val canonRaw = src["canon"] as String
  val charactersRaw = src["characters"] as String
  val ratingRaw = src["rating"] as String
  val scrollProgress: Double = src["scrollProgress"] as Double
  val chapterTitlesRaw: String = src["chapterTitles"] as String

  // UI data
  val chapterTitles = chapterTitlesRaw.split(CHAPTER_TITLE_SEPARATOR)
  val chapterCount: Int = (src["chapters"] as Long).toInt()
  val title = src["title"] as String
  val summary = src["summary"] as String
  val category: String get() = Static.res.getString(R.string.in_category, categoryRaw)
  val language = src["language"] as String
  val currentChapter: Int = (src["currentChapter"] as Long).toInt()
  val storyId: String get() = Static.res.getString(R.string.storyid_x, storyIdRaw)
  val isCompleted: Boolean get() = src["isCompleted"] as Long == 1L
  val author: String get() = Static.res.getString(R.string.by_author, authorRaw)
  val canon: String get() = Static.res.getString(R.string.in_canon, canonRaw)
  val words: String get() = Static.res.getString(R.string.x_words, autoSuffixNumber(wordCount))
  val rating: String get() = Static.res.getString(R.string.rated_x, ratingRaw)
  val genres: String get() = Static.res.getString(R.string.about_genres, genresRaw)
  val characters: String get() = Static.res.getString(R.string.with_characters, charactersRaw)
  val reviews: String get() = Static.res.getString(R.string.x_reviews, reviewsCount)
  val favorites: String get() = Static.res.getString(R.string.x_favorites, favoritesCount)
  val follows: String get() = Static.res.getString(R.string.x_follows, followsCount)
  val wordsProgressedApprox: Int get() {
    if (currentChapter == 0) return 0
    // If this is too inaccurate, we might have to store each chapter's word count, then compute
    // how far along we are
    val avgWordCount: Float = wordCount.toFloat() / chapterCount
    // amount of words before current chapter + amount of words scrolled through in current chapter
    val wordsPassedEstimate: Double =
        (currentChapter - 1) * avgWordCount + scrollProgress / 100 * avgWordCount
    return wordsPassedEstimate.toInt()
  }
  val progress: Double get() {
    if (chapterCount == 1) return scrollProgress
    // Return percentage
    return wordsProgressedApprox * 100.0 / wordCount
  }
  @Suppress("LiftReturnOrAssignment")
  val chapters: String get() {
    // If we didn't start reading the thing, show total chapter count
    if (currentChapter == 0) {
      // Special-case one chapter
      if (chapterCount == 1) return Static.res.getString(R.string.one_chapter)
      else return Static.res.getString(R.string.x_chapters, chapterCount)
    }
    // Otherwise, list current chapter out of total
    else return Static.res.getString(R.string.chapter_progress, currentChapter, chapterCount)
  }
  val genreList: List<Genre> get() =
    (if (genresRaw == Static.res.getString(R.string.none)) null else genresRaw)
        ?.replace("/", TEMP_SEPARATOR)
        // Take special care of "Hurt/Comfort" since it contains a slash
        ?.replace("Hurt${TEMP_SEPARATOR}Comfort", "Hurt/Comfort")
        ?.split(TEMP_SEPARATOR)
        ?.map { Genre.fromString(it) }
        ?: throw IllegalStateException("There are no genres")
  val characterList: List<String> get() =
    charactersRaw.replace("[", "").split(", ", "] ")

  // Dates
  val publishDateSeconds: Long = src["publishDate"] as Long
  val updateDateSeconds: Long = src["updateDate"] as Long
  private val publishDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(publishDateSeconds * 1000))
  private val updateDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(updateDateSeconds * 1000))
  val publishDate: String
    get() = Static.res.getString(R.string.published_on, publishDateFormatted)
  val updateDate: String
    get() = Static.res.getString(R.string.updated_on, updateDateFormatted)

  fun toKvPairs(): Array<Pair<String, Any>> = src.entries.map { it.key to it.value }.toTypedArray()

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    parcel.writeSerializable(HashMap(src))
  }

  override fun describeContents(): Int = 0
  companion object {
    @JvmField @Suppress("unused")
    val CREATOR = object : Parcelable.Creator<StoryModel> {
      override fun createFromParcel(parcel: Parcel): StoryModel {
        @Suppress("unchecked_cast")
        val map = parcel.readSerializable() as HashMap<String, Any>
        return StoryModel(map)
      }

      override fun newArray(size: Int): Array<StoryModel?> = arrayOfNulls(size)
    }

    val dbParser = object : MapRowParser<StoryModel> {
      override fun parseRow(columns: Map<String, Any?>) = StoryModel(
          // We are allowed to do this because nothing in the DB is null
          columns.entries.map { it.key to it.value!! }.toMap().toMutableMap())
    }
  }
}

enum class GroupStrategy {
  // Group by property
  CANON, AUTHOR, CATEGORY, STATUS, RATING, LANGUAGE, COMPLETION, GENRE,
  // Don't do grouping
  NONE;

  fun toUIString(): String = Static.res.getString(when (this) {
    CANON -> R.string.group_canon
    AUTHOR -> R.string.group_author
    CATEGORY -> R.string.group_category
    STATUS -> R.string.group_status
    RATING -> R.string.group_rating
    LANGUAGE -> R.string.group_language
    COMPLETION -> R.string.group_completion
    GENRE -> R.string.group_genre
    NONE -> R.string.group_none
  })

  companion object {
    operator fun get(index: Int) = values()[index]
    fun uiItems() = values().map { it.toUIString() }.toTypedArray()
  }
}

/**
 * @returns a map that maps titles to grouped stories, according to the given GroupStrategy
 */
fun groupStories(stories: MutableList<StoryModel>,
                 strategy: GroupStrategy): Map<String, MutableList<StoryModel>> {
  if (strategy == GroupStrategy.NONE)
    return mapOf(Static.res.getString(R.string.all_stories) to stories)
  if (strategy == GroupStrategy.GENRE) {
    val map = hashMapOf<String, MutableList<StoryModel>>()
    Genre.values().forEach { map[it.toUIString()] = mutableListOf() }
    val none = Static.res.getString(R.string.none)
    map[none] = stories.filter { it.genresRaw == none }.toMutableList()
    stories.filter { it.genresRaw != none }
        .forEach { story -> story.genreList.forEach { map[it.toUIString()]!!.add(story) } }
    Genre.values().forEach { if (map[it.toUIString()]!!.isEmpty()) map.remove(it.toUIString()) }
    return map
  }
  val srcKey = when (strategy) {
    GroupStrategy.CANON -> "canon"
    GroupStrategy.AUTHOR -> "author"
    GroupStrategy.CATEGORY -> "category"
    GroupStrategy.STATUS -> "status"
    GroupStrategy.RATING -> "rating"
    GroupStrategy.LANGUAGE -> "language"
    GroupStrategy.COMPLETION -> "isCompleted"
    GroupStrategy.GENRE -> throw IllegalStateException("Unreachable code, fast-pathed above")
    GroupStrategy.NONE -> throw IllegalStateException("Unreachable code, fast-pathed above")
  }
  val map = hashMapOf<String, MutableList<StoryModel>>()
  stories.forEach {
    val value: String = when (strategy) {
      GroupStrategy.STATUS -> StoryStatus.fromString(it.src[srcKey] as String).toUIString()
      GroupStrategy.COMPLETION ->
        if (it.src[srcKey] as Long == 1L) Static.res.getString(R.string.completed)
        else Static.res.getString(R.string.in_progress)
      else -> it.src[srcKey] as String
    }
    if (map[value] == null) map[value] = mutableListOf()
    map[value]!!.add(it)
  }
  return map
}

fun groupByDialog(context: Context, defaultStrategy: GroupStrategy,
                  action: (GroupStrategy) -> Unit) {
  AlertDialog.Builder(context)
      .setTitle(R.string.group_by)
      .setSingleChoiceItems(GroupStrategy.uiItems(), defaultStrategy.ordinal, { d, which ->
        d.dismiss()
        action(GroupStrategy[which])
      }).show()
}

@SuppressLint("InflateParams")
fun orderByDialog(context: Context,
                  defaultStrategy: OrderStrategy,
                  defaultDirection: OrderDirection,
                  action: (OrderStrategy, OrderDirection) -> Unit) {
  val layout = LayoutInflater.from(context)
      .inflate(R.layout.dialog_order_by_switch, null, false)
  val switch = layout.findViewById(R.id.reverseOrderSw) as Switch
  if (defaultDirection == OrderDirection.ASC) switch.toggle()
  AlertDialog.Builder(context)
      .setTitle(R.string.sort_by)
      .setView(layout)
      .setSingleChoiceItems(OrderStrategy.uiItems(), defaultStrategy.ordinal, { d, which ->
        d.dismiss()
        action(OrderStrategy[which],
            if (switch.isChecked) OrderDirection.ASC else OrderDirection.DESC)
      })
      .show()
}

private val progress = Comparator<StoryModel>
{ m1, m2 -> Math.signum(m1.progress - m2.progress).toInt() }
private val wordCount = Comparator<StoryModel> { m1, m2 -> m1.wordCount - m2.wordCount }
private val reviewCount = Comparator<StoryModel> { m1, m2 -> m1.reviewsCount - m2.reviewsCount }
private val followCount = Comparator<StoryModel> { m1, m2 -> m1.followsCount - m2.followsCount }
private val favsCount = Comparator<StoryModel> { m1, m2 -> m1.favoritesCount - m2.favoritesCount }
private val chapterCount = Comparator<StoryModel> { m1, m2 -> m1.chapterCount - m2.chapterCount }

// These give most recent of the dates
private val publish = Comparator<StoryModel> { m1, m2 ->
  if (m1.publishDateSeconds == m2.publishDateSeconds ) return@Comparator 0
  return@Comparator if (m1.publishDateSeconds - m2.publishDateSeconds > 0) 1 else -1
}
private val update = Comparator<StoryModel> { m1, m2 ->
  if (m1.updateDateSeconds == m2.updateDateSeconds ) return@Comparator 0
  return@Comparator if (m1.updateDateSeconds - m2.updateDateSeconds > 0) 1 else -1
}

// Lexicographic comparison of titles
private val titleAlphabetic = Comparator<StoryModel> { m1, m2 ->
  if (m1.title == m2.title) return@Comparator 0
  return@Comparator if (m1.title < m2.title) 1 else -1
}

enum class OrderDirection {
  ASC, DESC;

  companion object {
    operator fun get(index: Int) = OrderDirection.values()[index]
  }
}

enum class OrderStrategy(val comparator: Comparator<StoryModel>) {
  // Numeric orderings
  WORD_COUNT(wordCount), PROGRESS(progress), REVIEW_COUNT(reviewCount),
  FOLLOWS(followCount), FAVORITES(favsCount), CHAPTER_COUNT(chapterCount),
  // Date orderings
  PUBLISH_DATE(publish), UPDATE_DATE(update),
  // Other
  TITLE_ALPHABETIC(titleAlphabetic);

  fun toUIString(): String = Static.res.getString(when (this) {
    WORD_COUNT -> R.string.order_word_count
    PROGRESS -> R.string.order_progress
    REVIEW_COUNT -> R.string.order_reviews
    FOLLOWS -> R.string.order_follows
    FAVORITES -> R.string.order_favorites
    CHAPTER_COUNT -> R.string.order_chapter_count
    PUBLISH_DATE -> R.string.order_publish_date
    UPDATE_DATE -> R.string.order_update_date
    TITLE_ALPHABETIC -> R.string.order_title_alphabetic
  })

  companion object {
    operator fun get(index: Int) = values()[index]
    fun uiItems() = values().map { it.toUIString() }.toTypedArray()
  }
}

fun orderStories(stories: MutableList<StoryModel>,
                 s: OrderStrategy, d: OrderDirection): MutableList<StoryModel> {
  stories.sortWith(if (d == OrderDirection.DESC) s.comparator.reversed() else s.comparator)
  return stories
}

/**
 * Contains all the data necessary to arrange a list of [StoryModel]s.
 */
data class Arrangement(val orderStrategy: OrderStrategy = OrderStrategy.TITLE_ALPHABETIC,
                       val orderDirection: OrderDirection = OrderDirection.DESC,
                       val groupStrategy: GroupStrategy = GroupStrategy.NONE)
