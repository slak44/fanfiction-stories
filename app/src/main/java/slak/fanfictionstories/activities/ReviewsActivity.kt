package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.CardView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import kotlinx.android.synthetic.main.activity_reviews.*
import kotlinx.android.synthetic.main.review_component.view.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.fetchers.Review
import slak.fanfictionstories.fetchers.getReviews
import slak.fanfictionstories.utility.ActivityWithStatic
import slak.fanfictionstories.utility.iconTint
import slak.fanfictionstories.utility.infinitePageScroll
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log

class ReviewsActivity : ActivityWithStatic() {
  companion object {
    const val INTENT_STORY_MODEL = "story_model_extra"
    const val INTENT_TARGET_CHAPTER = "target_chapter_extra"
  }

  private var chapter: Int = 0
  private var currentPage: Int = 0
  private var totalPages: Int = 0
  private lateinit var model: StoryModel
  private lateinit var adapter: ReviewAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_reviews)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    model = intent.getParcelableExtra(INTENT_STORY_MODEL)
        ?: throw IllegalStateException("StoryModel is missing from the intent")

    // 0 means reviews from all chapters
    chapter = intent.getIntExtra(INTENT_TARGET_CHAPTER, 0)

    title = resources.getString(R.string.reviews_for, model.title)
    toolbar.subtitle =
        if (chapter != 0) resources.getString(R.string.chapter_x, chapter)
        else resources.getString(R.string.all_chapters)
    setSubtitle()

    adapter = ReviewAdapter()
    reviewList.adapter = adapter
    val layoutManager = LinearLayoutManager(this)
    reviewList.layoutManager = layoutManager

    addPage(1)

    infinitePageScroll(reviewList, layoutManager) {
      addPage(++currentPage)
    }
  }

  private fun setSubtitle() {
    toolbar.subtitle =
        if (chapter != 0) resources.getString(R.string.chapter_x, chapter)
        else resources.getString(R.string.all_chapters)
  }

  private fun addPage(page: Int) = launch(UI) {
    if (totalPages != 0 && page >= totalPages) return@launch
    val (list, pages) =
        getReviews(this@ReviewsActivity, model.storyIdRaw, chapter, page).await()
    if (totalPages == 0) totalPages = pages
    if (pages == -1) noReviewsText.visibility = View.VISIBLE
    else noReviewsText.visibility = View.INVISIBLE
    adapter.addReviews(list)
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.selectReviewsFor).iconTint(android.R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_reviews, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.selectReviewsFor -> {
        println(model.chapterTitles)
        val items = listOf(
            resources.getString(R.string.all_chapters),
            *model.chapterTitles.toTypedArray()
        ).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.select_chapter)
            .setItems(items, { dialog, which: Int ->
              dialog.dismiss()
              if (chapter == which) return@setItems
              adapter.clear()
              chapter = which
              currentPage = 1
              totalPages = 0
              addPage(currentPage)
              // FIXME kill all outstanding "addPage" requests here
              setSubtitle()
            }).show()
      }
    }
    return super.onOptionsItemSelected(item)
  }
}

class ReviewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class ReviewHolder(val view: CardView) : RecyclerView.ViewHolder(view)

  private val reviews: MutableList<Review> = mutableListOf()

  fun clear() {
    val size = reviews.size
    reviews.clear()
    notifyItemRangeRemoved(0, size)
  }

  fun addReviews(newReviews: List<Review>) {
    reviews.addAll(newReviews)
    notifyItemRangeInserted(reviews.size - newReviews.size, newReviews.size)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    return ReviewHolder(LayoutInflater.from(parent.context)
        .inflate(R.layout.review_component, parent, false) as CardView)
  }
  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
    with((holder as ReviewHolder).view) {
      reviewAuthor.text = reviews[pos].author
      reviewChapter.text = resources.getString(R.string.chapter_x, reviews[pos].chapter)
      reviewDate.text = SimpleDateFormat.getDateInstance()
          .format(Date(reviews[pos].unixTimeSeconds * 1000))
      reviewContent.text = reviews[pos].content
      viewAuthorBtn.setOnClickListener {
        // FIXME: show author page
      }
      replyBtn.setOnClickListener {
        // FIXME: reply to review
      }
      reportBtn.setOnClickListener {
        // FIXME: report review for abuse
      }
      forceLayout()
    }
  }
  override fun getItemCount(): Int = reviews.size
}
