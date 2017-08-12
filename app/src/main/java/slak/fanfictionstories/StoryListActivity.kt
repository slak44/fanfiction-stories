package slak.fanfictionstories

import android.content.Context
import android.os.Bundle
import android.support.constraint.ConstraintLayout
import android.support.design.widget.CoordinatorLayout
import android.support.v7.app.AppCompatActivity
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.story_component.view.*
import org.jetbrains.anko.db.*
import java.text.SimpleDateFormat
import java.util.*

class StoryLayout : LinearLayout {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  override fun onCreateDrawableState(extraSpace: Int): IntArray {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      // FIXME do we want this elevation like this?
      if (cardWrapper.cardElevation == 7F) cardWrapper.cardElevation = 14F
      else cardWrapper.cardElevation = 7F
      if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
      else storyDetails.visibility = View.GONE
    }
    return super.onCreateDrawableState(extraSpace)
  }

//  override fun performClick(): Boolean {
//    return super.performClick()
//  }
//
//  override fun onTouchEvent(event: MotionEvent?): Boolean {
//    // FIXME do we want this elevation like this?
//    if (cardWrapper.cardElevation == 7F) cardWrapper.cardElevation = 14F
//    else cardWrapper.cardElevation = 7F
//    if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
//    else storyDetails.visibility = View.GONE
//    println(storyDetails.visibility)//FIXME
//    performClick()
//    return super.onTouchEvent(event)
//  }

  fun loadFromModel(model: StoryModel) {
    // Unexpanded view
    titleText.text = model.title
    authorText.text = model.author
    categoryText.text = model.category
    wordsText.text = model.words
    storyProgress.progress = model.progress
    isCompletedText.visibility = if (model.isCompleted) View.VISIBLE else View.INVISIBLE
    // Detail view
    languageText.text = model.language
    ratingText.text = model.rating
    summaryText.text = model.summary
    chaptersText.text = model.chapters
    genresText.text = model.genres
    charactersText.text = model.characters
    updateDateText.text = model.updateDate
    publishDateText.text = model.publishDate
    reviewsText.text = model.reviews
    favoritesText.text = model.favorites
    followsText.text = model.follows
    storyidText.text = model.storyid
  }
}

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

class StoryModel(val src: Map<String, Any?>, val context: Context) {
  init {
    println(src)//FIXME
  }
  val _id: Long = src["_id"] as Long
  val storyidRaw: String = src["storyid"] as String

  val title = src["title"] as String
  val authorRaw = src["author"] as String
  val summary = src["summary"] as String
  val categoryRaw = src["category"] as String
  val language = src["language"] as String
  val genresRaw = src["genres"] as String
  val charactersRaw = src["characters"] as String
  val ratingRaw = src["rating"] as String
  val status = StoryStatus.fromString(src["status"] as String)
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
  val category: String get() = context.resources.getString(R.string.in_category, categoryRaw)
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

class StoryAdapter(val activity: StoryListActivity) : BaseAdapter() {
  var data = activity.getStoriesMeta()

  override fun getCount(): Int {
    return data.size
  }

  override fun getItem(position: Int): Any {
    return data[position]
  }

  override fun getItemId(position: Int): Long {
    return data[position]._id
  }

  override fun getView(idx: Int, recycleView: View?, parent: ViewGroup?): View {
    // FIXME: use the recycle crap
    val view = activity.layoutInflater.inflate(R.layout.story_component, parent, false) as ConstraintLayout
    view.storyLayout.loadFromModel(data[idx])
    return view
  }
}

class StoryListActivity : AppCompatActivity() {
  fun getStoriesMeta() : List<StoryModel> {
    return database.use {
      select(tableName = "stories").exec {
        parseList(object : MapRowParser<StoryModel> {
          override fun parseRow(columns: Map<String, Any?>) =
              StoryModel(columns, this@StoryListActivity)
        })
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_list)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val adapter = StoryAdapter(this)
    storyListView.adapter = adapter
  }
}
