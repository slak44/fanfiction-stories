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

  val storyIdRaw: Long = src["storyId"] as Long
  val title = src["title"] as String
  val authorRaw = src["author"] as String
  val summary = src["summary"] as String
  val category = src["category"] as String // FIXME: ui for this where
  val canonRaw = src["canon"] as String
  val language = src["language"] as String
  val genresRaw = src["genres"] as String
  val charactersRaw = src["characters"] as String
  val ratingRaw = src["rating"] as String
  val wordCount: Int = (src["wordCount"] as Long).toInt()
  val scrollProgress: Int = (src["scrollProgress"] as Long).toInt()
  val chapterCount: Int = (src["chapters"] as Long).toInt()
  val currentChapter: Int = (src["currentChapter"] as Long).toInt()
  val reviewsCount: Int = (src["reviews"] as Long).toInt()
  val favoritesCount: Int = (src["favorites"] as Long).toInt()
  val followsCount: Int = (src["follows"] as Long).toInt()

  // Processed data
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
  val progress: Int get() {
    if (chapterCount == 1) return scrollProgress
    // If this is too inaccurate, we might have to store each chapter's word count, then compute
    // how far along we are
    val avgWordCount: Float = wordCount.toFloat() / chapterCount
    // amount of words before current chapter + amount of words scrolled through in current chapter
    val wordsPassedEstimate: Float =
        (currentChapter - 1) * avgWordCount + scrollProgress.toFloat() / 100 * avgWordCount
    val percentPassed: Float = wordsPassedEstimate * 100 / wordCount
    return Math.round(percentPassed)
  }
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
  val publishDateSeconds: Long = src["publishDate"] as Long
  val updateDateSeconds: Long = src["updateDate"] as Long
  val publishDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(publishDateSeconds * 1000))
  val updateDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(updateDateSeconds * 1000))
  val publishDate: String
    get() = MainActivity.res.getString(R.string.published_on, publishDateFormatted)
  val updateDate: String
    get() = MainActivity.res.getString(R.string.updated_on, updateDateFormatted)

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