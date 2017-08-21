package slak.fanfictionstories

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import org.jetbrains.anko.db.MapRowParser
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

enum class StoryStatus {
  SEEN, REMOTE, LOCAL;

  companion object {
    fun fromString(s: String): StoryStatus = when (s) {
      "seen" -> SEEN
      "remote" -> REMOTE
      "local" -> LOCAL
      else -> throw IllegalArgumentException("Invalid arg: " + s)
    }
  }

  override fun toString(): String = when(this) {
    SEEN -> "seen"
    REMOTE -> "remote"
    LOCAL -> "local"
  }
}

class StoryModel(val src: MutableMap<String, Any>, fromDb: Boolean) : Parcelable {
  // DB primary key. Does not exist if story not from db
  val _id: Optional<Long> = if (fromDb) Optional.of(src["_id"] as Long) else Optional.empty()

  var status = StoryStatus.fromString(src["status"] as String) // FIXME: ui for this where
    set(value) {
      field = value
      src["status"] = value.toString()
    }

  // Raw data
  val storyIdRaw: Long = src["storyId"] as Long
  private val authorRaw = src["author"] as String
  private val canonRaw = src["canon"] as String
  private val genresRaw = src["genres"] as String
  private val charactersRaw = src["characters"] as String
  private val categoryRaw = src["category"] as String
  private val ratingRaw = src["rating"] as String
  private val wordCount: Int = (src["wordCount"] as Long).toInt()
  private val scrollProgress: Double = src["scrollProgress"] as Double
  private val reviewsCount: Int = (src["reviews"] as Long).toInt()
  private val favoritesCount: Int = (src["favorites"] as Long).toInt()
  private val followsCount: Int = (src["follows"] as Long).toInt()
  private val chapterTitlesRaw: String = src["chapterTitles"] as String

  // UI data
  val chapterTitles = chapterTitlesRaw.split(StoryFetcher.CHAPTER_TITLE_SEPARATOR)
  val chapterCount: Int = (src["chapters"] as Long).toInt()
  val title = src["title"] as String
  val summary = src["summary"] as String
  val category: String = MainActivity.res.getString(R.string.in_category, categoryRaw)
  val language = src["language"] as String
  val currentChapter: Int = (src["currentChapter"] as Long).toInt()
  val storyId: String get() = MainActivity.res.getString(R.string.storyid_x, storyIdRaw)
  val isCompleted: Boolean get() = src["isCompleted"] as Long == 1L
  val author: String get() = MainActivity.res.getString(R.string.by_author, authorRaw)
  val canon: String get() = MainActivity.res.getString(R.string.in_canon, canonRaw)
  val words: String get() = MainActivity.res.getString(R.string.x_words, wordCount)
  val rating: String get() = MainActivity.res.getString(R.string.rated_x, ratingRaw)
  val genres: String get() = MainActivity.res.getString(R.string.about_genres, genresRaw)
  val characters: String get() = MainActivity.res.getString(R.string.with_characters, charactersRaw)
  val reviews: String get() = MainActivity.res.getString(R.string.x_reviews, reviewsCount)
  val favorites: String get() = MainActivity.res.getString(R.string.x_favorites, favoritesCount)
  val follows: String get() = MainActivity.res.getString(R.string.x_follows, followsCount)
  val progress: Double get() {
    if (chapterCount == 1) return scrollProgress
    // If this is too inaccurate, we might have to store each chapter's word count, then compute
    // how far along we are
    val avgWordCount: Float = wordCount.toFloat() / chapterCount
    // amount of words before current chapter + amount of words scrolled through in current chapter
    val wordsPassedEstimate: Double =
        (currentChapter - 1) * avgWordCount + scrollProgress / 100 * avgWordCount
    // Return percentage
    return wordsPassedEstimate * 100 / wordCount
  }
  @Suppress("LiftReturnOrAssignment")
  val chapters: String get() {
    // If we didn't start reading the thing, show total chapter count
    if (currentChapter == 0) {
      // Special-case one chapter
      if (chapterCount == 1) return MainActivity.res.getString(R.string.one_chapter)
      else return MainActivity.res.getString(R.string.x_chapters, chapterCount)
    }
    // Otherwise, list current chapter out of total
    else return MainActivity.res.getString(R.string.chapter_progress, currentChapter, chapterCount)
  }

  // Dates
  @Suppress("MemberVisibilityCanPrivate")
  val publishDateSeconds: Long = src["publishDate"] as Long
  @Suppress("MemberVisibilityCanPrivate")
  val updateDateSeconds: Long = src["updateDate"] as Long
  private val publishDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(publishDateSeconds * 1000))
  private val updateDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(updateDateSeconds * 1000))
  val publishDate: String
    get() = MainActivity.res.getString(R.string.published_on, publishDateFormatted)
  val updateDate: String
    get() = MainActivity.res.getString(R.string.updated_on, updateDateFormatted)

  fun toKvPairs(): Array<Pair<String, Any>> {
    return src.entries.map { Pair(it.key, it.value) }.toTypedArray()
  }

  override fun writeToParcel(parcel: Parcel, flags: Int) {
    val bundle = Bundle()
    bundle.putSerializable("map", HashMap(src))
    parcel.writeBundle(bundle)
    parcel.writeInt(if (_id.isPresent) 1 else 0)
  }

  override fun describeContents(): Int {
    return 0
  }
  companion object {
    @JvmField @Suppress("unused")
    val CREATOR = object : Parcelable.Creator<StoryModel> {
      @SuppressLint("ParcelClassLoader")
      override fun createFromParcel(parcel: Parcel): StoryModel {
        val bundle = parcel.readBundle()
        @Suppress("UNCHECKED_CAST")
        val map = bundle.getSerializable("map") as HashMap<String, Any>
        val fromDb = parcel.readInt() == 1
        return StoryModel(map, fromDb)
      }

      override fun newArray(size: Int): Array<StoryModel?> {
        return arrayOfNulls(size)
      }
    }

    val dbParser = object : MapRowParser<StoryModel> {
      override fun parseRow(columns: Map<String, Any?>) = StoryModel(
          // We are allowed to do this because nothing in the DB is null
          columns.entries.map { Pair(it.key, it.value!!) }.toMap().toMutableMap(), fromDb = true)
    }
  }
}