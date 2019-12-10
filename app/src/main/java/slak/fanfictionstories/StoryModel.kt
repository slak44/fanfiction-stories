package slak.fanfictionstories

import android.annotation.SuppressLint
import android.content.Context
import android.os.Parcelable
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.dialog_order_by_switch.view.*
import org.jetbrains.anko.db.MapRowParser
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.Genre
import slak.fanfictionstories.data.fetchers.ParserUtils.CHAPTER_TITLE_SEPARATOR
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.str
import java.io.Serializable
import java.util.*
import kotlin.collections.set
import kotlin.math.sign

/** Represents a [StoryModel]'s chapter download state. */
enum class StoryStatus {
  /** Incomplete metadata, will be set to remote if user wants to read it. */
  TRANSIENT,
  /** Partially on disk. */
  REMOTE,
  /** Fully on disk. */
  LOCAL;

  companion object {
    /** Convert from the string found in the db to an instance of [StoryStatus]. */
    fun fromString(s: String): StoryStatus = when (s) {
      "transient" -> TRANSIENT
      "remote" -> REMOTE
      "local" -> LOCAL
      else -> throw IllegalArgumentException("Invalid arg: $s")
    }
  }

  /** Convert from an instance of [StoryStatus] to a string to store in the db. */
  override fun toString(): String = when (this) {
    TRANSIENT -> "transient"
    REMOTE -> "remote"
    LOCAL -> "local"
  }

  /** Convert from an instance of [StoryStatus] to a user-viewable string */
  fun toUIString(): String = when (this) {
    // We pretend TRANSIENT == REMOTE, because they are the same as far as the user is concerned
    TRANSIENT, REMOTE -> str(R.string.remote)
    LOCAL -> str(R.string.local)
  }
}

/** The story data that can be extracted from the text div. */
@Parcelize
data class StoryModelFragment(
    val rating: String,
    val language: String,
    val wordCount: Long,
    val chapterCount: Long,
    val favorites: Long,
    val follows: Long,
    val reviews: Long,
    val genres: String,
    val characters: String,
    val publishTime: Long,
    val updateTime: Long,
    val isComplete: Long
) : Parcelable, Serializable

/**
 * Stores the progress made in a story.
 * @param scrollAbsolute serialized value from
 * [slak.fanfictionstories.utility.FastTextView.scrollStateFromScrollY]
 * @param scrollProgress percentage scrolled in current chapter
 * @param currentChapter the current chapter
 */
@Parcelize
data class StoryProgress(
    val scrollProgress: Double = 0.0,
    val scrollAbsolute: Double = 0.0,
    val currentChapter: Long = 0L
) : Parcelable, Serializable

/** Alias the story ids to [Long] for clarity. */
typealias StoryId = Long

/** Models a story, representing all of its data except for the chapter text. */
@Parcelize
data class StoryModel(
    val storyId: StoryId,
    var status: StoryStatus,
    var progress: StoryProgress,
    val fragment: StoryModelFragment,
    var addedTime: Long?,
    var lastReadTime: Long?,
    val canon: String,
    val category: String?,
    val summary: String,
    val author: String,
    val authorId: Long,
    val title: String,
    val serializedChapterTitles: String?,
    val imageUrl: String
) : Parcelable, Serializable {
  init {
    require(storyId > 0) { "Story id is strictly positive" }
    require(authorId > 0) { "Author id is strictly positive" }
    require(fragment.publishTime >= 0) { "Publish time is positive" }
    require(fragment.updateTime >= 0) { "Update time is positive" }
    require(!(fragment.chapterCount > 1 && serializedChapterTitles != null && serializedChapterTitles.isEmpty())) {
      "There are chapters, but the titles do not exist"
    }
  }

  /** Checks if this model is suitable for being written into the database. */
  fun isPersistable(): Boolean = when {
    category == null -> false
    serializedChapterTitles == null -> false
    addedTime == null -> false
    lastReadTime == null -> false
    else -> true
  }

  /** @returns an estimate about how many words have been read */
  fun wordsProgressedApprox(): Long {
    if (progress.currentChapter == 0L) return 0L
    // If this is too inaccurate, we might have to store each chapter's word count, then compute
    // how far along we are
    val avgWordCount: Double = fragment.wordCount.toDouble() / fragment.chapterCount
    // amount of words before current chapter + amount of words scrolled through in current chapter
    val wordsPassedEstimate: Double =
        (progress.currentChapter - 1) * avgWordCount + progress.scrollProgress / 100 * avgWordCount
    return wordsPassedEstimate.toLong()
  }

  /** @returns the progress through the story as a percentage */
  fun progressAsPercentage(): Double {
    if (fragment.chapterCount == 1L) return progress.scrollProgress
    // Return percentage
    return wordsProgressedApprox() * 100.0 / fragment.wordCount
  }

  /** @returns whether or not the story has been marked complete by its author */
  fun isComplete(): Boolean = fragment.isComplete == 1L

  /** @returns parsed [Genre]s from the raw data in [StoryModelFragment.genres] */
  fun genreList(): List<Genre> {
    if (fragment.genres == str(R.string.none)) return emptyList()
    return fragment.genres
        .replace("/", TEMP_SEPARATOR)
        // Take special care of "Hurt/Comfort" since it contains a slash
        .replace("Hurt${TEMP_SEPARATOR}Comfort", "Hurt/Comfort")
        .split(TEMP_SEPARATOR)
        .map { Genre.fromString(it) }
  }

  fun characterList(): List<String> = fragment.characters.replace("[", "").split(", ", "] ")

  /** @returns deserialized chapter titles */
  fun chapterTitles(): List<String> =
      serializedChapterTitles?.split(CHAPTER_TITLE_SEPARATOR) ?: emptyList()

  /** @returns a map for writing to the database */
  fun toMap(): Map<String, Any?> = mapOf(
      "storyId" to storyId,
      "status" to status.toString(),
      "scrollProgress" to progress.scrollProgress,
      "scrollAbsolute" to progress.scrollAbsolute,
      "currentChapter" to progress.currentChapter,
      "rating" to fragment.rating,
      "language" to fragment.language,
      "wordCount" to fragment.wordCount,
      "chapterCount" to fragment.chapterCount,
      "favorites" to fragment.favorites,
      "follows" to fragment.follows,
      "reviews" to fragment.reviews,
      "genres" to fragment.genres,
      "characters" to fragment.characters,
      "updateTime" to fragment.updateTime,
      "publishTime" to fragment.publishTime,
      "isComplete" to fragment.isComplete,
      "title" to title,
      "summary" to summary,
      "author" to author,
      "authorId" to authorId,
      "category" to (category ?: ""),
      "canon" to canon,
      "chapterTitles" to serializedChapterTitles,
      "addedTime" to addedTime,
      "lastReadTime" to lastReadTime,
      "imageUrl" to imageUrl
  )

  /** @returns a list of pairs for writing to the database */
  fun toPairs(): Array<Pair<String, Any?>> =
      toMap().entries.map { it.key to it.value }.toTypedArray()

  companion object {
    private const val TEMP_SEPARATOR = "temporary_separator"

    /** Get a [StoryModel] from a database row. */
    fun fromPairs(pairs: List<Pair<String, Any?>>): StoryModel {
      val map = pairs.toMap()
      return StoryModel(
          storyId = map.getValue("storyId") as Long,
          fragment = StoryModelFragment(
              rating = map.getValue("rating") as String,
              language = map.getValue("language") as String,
              wordCount = map.getValue("wordCount") as Long,
              chapterCount = map.getValue("chapterCount") as Long,
              favorites = map.getValue("favorites") as Long,
              follows = map.getValue("follows") as Long,
              reviews = map.getValue("reviews") as Long,
              genres = map.getValue("genres") as String,
              characters = map.getValue("characters") as String,
              publishTime = map.getValue("publishTime") as Long,
              updateTime = map.getValue("updateTime") as Long,
              isComplete = map.getValue("isComplete") as Long
          ),
          progress = StoryProgress(
              scrollProgress = map.getValue("scrollProgress") as Double,
              scrollAbsolute = map.getValue("scrollAbsolute") as Double,
              currentChapter = map.getValue("currentChapter") as Long
          ),
          addedTime = map.getValue("addedTime") as Long,
          lastReadTime = map.getValue("lastReadTime") as Long,
          status = StoryStatus.fromString(map.getValue("status") as String),
          canon = map.getValue("canon") as String,
          category = map.getValue("category") as String,
          summary = map.getValue("summary") as String,
          author = map.getValue("author") as String,
          authorId = map.getValue("authorId") as Long,
          title = map.getValue("title") as String,
          serializedChapterTitles = map.getValue("chapterTitles") as String,
          imageUrl = map.getValue("imageUrl") as String
      )
    }

    val dbParser = object : MapRowParser<StoryModel> {
      override fun parseRow(columns: Map<String, Any?>) = fromPairs(columns.entries.map { it.key to it.value })
    }
  }
}

/**
 * Possible ways of grouping stories in a [StoryListViewModel].
 * @see groupByDialog
 * @see groupStories
 */
enum class GroupStrategy {
  // Group by property
  CANON, AUTHOR, CATEGORY, STATUS, RATING, LANGUAGE, COMPLETION, GENRE, MARKER,
  // Don't do grouping
  NONE;

  fun toUIString(): String = str(when (this) {
    CANON -> R.string.group_canon
    AUTHOR -> R.string.group_author
    CATEGORY -> R.string.group_category
    STATUS -> R.string.group_status
    RATING -> R.string.group_rating
    LANGUAGE -> R.string.group_language
    COMPLETION -> R.string.group_completion
    GENRE -> R.string.group_genre
    MARKER -> R.string.group_marker_color
    NONE -> R.string.group_none
  })

  companion object {
    operator fun get(index: Int) = values()[index]
    fun uiItems() = values().map { it.toUIString() }.toTypedArray()
  }
}

/** @returns a map that maps titles to grouped stories, according to the given [GroupStrategy]. */
suspend fun groupStories(stories: MutableList<StoryModel>,
                         strategy: GroupStrategy): Map<String, MutableList<StoryModel>> {
  if (strategy == GroupStrategy.NONE)
    return mapOf(str(R.string.all_stories) to stories)
  if (strategy == GroupStrategy.GENRE) {
    val map = hashMapOf<String, MutableList<StoryModel>>()
    Genre.values().forEach { map[it.toUIString()] = mutableListOf() }
    val none = str(R.string.none)
    map[none] = stories.filterTo(mutableListOf()) { it.fragment.genres == none }
    stories.filter { it.fragment.genres != none }
        .forEach { story -> story.genreList().forEach { map[it.toUIString()]!!.add(story) } }
    Genre.values().forEach { if (map[it.toUIString()]!!.isEmpty()) map.remove(it.toUIString()) }
    return map
  }
  if (strategy == GroupStrategy.MARKER) {
    val names = Static.res.getStringArray(R.array.markerColorNames)
    val colors = Static.res.getIntArray(R.array.markerColors)
    val map = hashMapOf<String, MutableList<StoryModel>>()
    names.forEach { map[it] = mutableListOf() }
    val idToMarkerName = Static.database.getMarkers(stories.map { it.storyId }).await().mapValues {
      names[colors.indexOf(it.value.toInt())]
    }
    stories.forEach {
      map[idToMarkerName[it.storyId]]!!.add(it)
    }
    names.forEach { if (map[it]!!.isEmpty()) map.remove(it) }
    return map
  }
  val map = hashMapOf<String, MutableList<StoryModel>>()
  stories.forEach {
    val value: String = when (strategy) {
      GroupStrategy.STATUS -> it.status.toUIString()
      GroupStrategy.COMPLETION ->
        if (it.isComplete()) str(R.string.completed) else str(R.string.in_progress)
      GroupStrategy.CANON -> it.canon
      GroupStrategy.AUTHOR -> it.author
      GroupStrategy.CATEGORY -> it.category ?: str(R.string.no_category)
      GroupStrategy.RATING -> str(R.string.rated_x, it.fragment.rating)
      GroupStrategy.LANGUAGE -> it.fragment.language
      else -> throw IllegalStateException("Unreachable code, fast-pathed above")
    }
    if (map[value] == null) map[value] = mutableListOf()
    map[value]!!.add(it)
  }
  return map
}

/** Shows a dialog presenting [GroupStrategy] choices for grouping. */
fun groupByDialog(context: Context, defaultStrategy: GroupStrategy, action: (GroupStrategy) -> Unit) {
  AlertDialog.Builder(context)
      .setTitle(R.string.group_by)
      .setSingleChoiceItems(GroupStrategy.uiItems(), defaultStrategy.ordinal) { d, which ->
        d.dismiss()
        action(GroupStrategy[which])
      }.show()
}

/** Shows a dialog presenting [OrderStrategy] and [OrderDirection] choices for ordering. */
@SuppressLint("InflateParams")
fun orderByDialog(context: Context,
                  defaultStrategy: OrderStrategy,
                  defaultDirection: OrderDirection,
                  action: (OrderStrategy, OrderDirection) -> Unit) {
  val layout = LayoutInflater.from(context)
      .inflate(R.layout.dialog_order_by_switch, null, false)
  if (defaultDirection == OrderDirection.ASC) layout.reverseOrderSw.toggle()
  AlertDialog.Builder(context)
      .setTitle(R.string.sort_by)
      .setView(layout)
      .setSingleChoiceItems(OrderStrategy.uiItems(), defaultStrategy.ordinal) { d, which ->
        d.dismiss()
        action(OrderStrategy[which],
            if (layout.reverseOrderSw.isChecked) OrderDirection.ASC else OrderDirection.DESC)
      }
      .show()
}

private val progress = Comparator<StoryModel> { m1, m2 ->
  sign(m1.progressAsPercentage() - m2.progressAsPercentage()).toInt()
}
private val wordCount = Comparator<StoryModel> { m1, m2 ->
  (m1.fragment.wordCount - m2.fragment.wordCount).toInt()
}
private val reviewCount = Comparator<StoryModel> { m1, m2 ->
  (m1.fragment.reviews - m2.fragment.reviews).toInt()
}
private val followCount = Comparator<StoryModel> { m1, m2 ->
  (m1.fragment.follows - m2.fragment.follows).toInt()
}
private val favoritesCount = Comparator<StoryModel> { m1, m2 ->
  (m1.fragment.favorites - m2.fragment.favorites).toInt()
}
private val chapterCount = Comparator<StoryModel> { m1, m2 ->
  (m1.fragment.chapterCount - m2.fragment.chapterCount).toInt()
}

// These give most recent of the dates
private val publish = Comparator<StoryModel> { m1, m2 ->
  if (m1.fragment.publishTime == m2.fragment.publishTime) return@Comparator 0
  return@Comparator if (m1.fragment.publishTime - m2.fragment.publishTime > 0) 1 else -1
}
private val update = Comparator<StoryModel> { m1, m2 ->
  if (m1.fragment.updateTime == m2.fragment.updateTime) return@Comparator 0
  return@Comparator if (m1.fragment.updateTime - m2.fragment.updateTime > 0) 1 else -1
}
private val added = Comparator<StoryModel> { m1, m2 ->
  if (m1.addedTime == m2.addedTime) return@Comparator 0
  return@Comparator if (m1.addedTime!! - m2.addedTime!! > 0) 1 else -1
}
private val lastRead = Comparator<StoryModel> { m1, m2 ->
  if (m1.lastReadTime == m2.lastReadTime) return@Comparator 0
  return@Comparator if (m1.lastReadTime!! - m2.lastReadTime!! > 0) 1 else -1
}

// Lexicographic comparison of titles
private val titleAlphabetic = Comparator<StoryModel> { m1, m2 ->
  if (m1.title == m2.title) return@Comparator 0
  return@Comparator if (m1.title < m2.title) 1 else -1
}

/**
 * Only possible directions for ordering of content.
 * @see OrderStrategy
 */
enum class OrderDirection {
  ASC, DESC;

  companion object {
    operator fun get(index: Int) = values()[index]
  }
}

/**
 * Possible ways to order stories in a [StoryListViewModel].
 * @see OrderDirection
 * @see orderByDialog
 * @see orderStories
 */
enum class OrderStrategy(val comparator: Comparator<StoryModel>) {
  // Numeric orderings
  WORD_COUNT(wordCount),
  PROGRESS(progress), REVIEW_COUNT(reviewCount),
  FOLLOWS(followCount), FAVORITES(favoritesCount), CHAPTER_COUNT(chapterCount),
  // Date orderings
  PUBLISH_DATE(publish),
  UPDATE_DATE(update), ADDED_DATE(added), LAST_READ_DATE(lastRead),
  // Other
  TITLE_ALPHABETIC(titleAlphabetic);

  fun toUIString(): String = str(when (this) {
    WORD_COUNT -> R.string.order_word_count
    PROGRESS -> R.string.order_progress
    REVIEW_COUNT -> R.string.order_reviews
    FOLLOWS -> R.string.order_follows
    FAVORITES -> R.string.order_favorites
    CHAPTER_COUNT -> R.string.order_chapter_count
    PUBLISH_DATE -> R.string.order_publish_date
    UPDATE_DATE -> R.string.order_update_date
    ADDED_DATE -> R.string.order_added_date
    LAST_READ_DATE -> R.string.order_last_read_date
    TITLE_ALPHABETIC -> R.string.order_title_alphabetic
  })

  companion object {
    operator fun get(index: Int) = values()[index]
    fun uiItems() = values().map { it.toUIString() }.toTypedArray()
  }
}

/** Sorts stories based on the provided [OrderStrategy] and [OrderDirection]. */
fun orderStories(stories: MutableList<StoryModel>,
                 s: OrderStrategy, d: OrderDirection): MutableList<StoryModel> {
  stories.sortWith(if (d == OrderDirection.DESC) s.comparator.reversed() else s.comparator)
  return stories
}

/** Wraps all the data necessary to arrange a list of [StoryModel]s. */
data class Arrangement(val orderStrategy: OrderStrategy = OrderStrategy.TITLE_ALPHABETIC,
                       val orderDirection: OrderDirection = OrderDirection.DESC,
                       val groupStrategy: GroupStrategy = GroupStrategy.NONE)
