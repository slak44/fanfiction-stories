package slak.fanfictionstories

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

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
}

class StoryModel(val src: Map<String, Any?>, val context: Context, fromDb: Boolean) {
  // DB primary key. Does not exist if story not from db
  var _id: Optional<Long> = if (fromDb) Optional.of(src["_id"] as Long) else Optional.empty()

  val storyidRaw: Long = src["storyid"] as Long
  val title = src["title"] as String
  val authorRaw = src["author"] as String
  val summary = src["summary"] as String
  val category = src["category"] as String // FIXME: ui for this where
  val canonRaw = src["canon"] as String
  val language = src["language"] as String
  val genresRaw = src["genres"] as String
  val charactersRaw = src["characters"] as String
  val ratingRaw = src["rating"] as String
  val status = StoryStatus.fromString(src["status"] as String) // FIXME: ui for this where
  val wordCount: Int = (src["wordCount"] as Long).toInt()
  val scrollProgress: Int = (src["scrollProgress"] as Long).toInt()
  val chapterCount: Int = (src["chapters"] as Long).toInt()
  val currentChapter: Int = (src["currentChapter"] as Long).toInt()
  val reviewsCount: Int = (src["reviews"] as Long).toInt()
  val favoritesCount: Int = (src["favorites"] as Long).toInt()
  val followsCount: Int = (src["follows"] as Long).toInt()

  // Processed data
  val storyid: String get() = context.resources.getString(R.string.storyid_x, storyidRaw)
  val isCompleted: Boolean get() = src["isCompleted"] as Long == 1L
  val author: String get() = context.resources.getString(R.string.by_author, authorRaw)
  val canon: String get() = context.resources.getString(R.string.in_canon, canonRaw)
  val words: String get() = context.resources.getString(R.string.x_words, wordCount)
  val rating: String get() = context.resources.getString(R.string.rated_x, ratingRaw)
  val genres: String get() = context.resources.getString(R.string.about_genres, genresRaw)
  val characters: String get() = context.resources.getString(R.string.with_characters, charactersRaw)
  val reviews: String get() = context.resources.getString(R.string.x_reviews, reviewsCount)
  val favorites: String get() = context.resources.getString(R.string.x_favorites, favoritesCount)
  val follows: String get() = context.resources.getString(R.string.x_follows, followsCount)
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
    if (currentChapter == 0) return context.resources.getString(R.string.x_chapters, chapterCount)
    // Otherwise, list current chapter out of total
    else return context.resources.getString(R.string.chapter_progress, currentChapter, chapterCount)
  }

  // Dates
  val publishDateSeconds: Long = src["publishDate"] as Long
  val updateDateSeconds: Long = src["updateDate"] as Long
  val publishDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(publishDateSeconds * 1000))
  val updateDateFormatted: String
    get() = SimpleDateFormat.getDateInstance().format(Date(updateDateSeconds * 1000))
  val publishDate: String
    get() = context.resources.getString(R.string.published_on, publishDateFormatted)
  val updateDate: String
    get() = context.resources.getString(R.string.updated_on, updateDateFormatted)
}