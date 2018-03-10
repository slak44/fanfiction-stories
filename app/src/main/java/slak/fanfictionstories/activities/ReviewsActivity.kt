package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.support.v7.widget.CardView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import kotlinx.android.synthetic.main.activity_reviews.*
import kotlinx.android.synthetic.main.dialog_report_review.view.*
import kotlinx.android.synthetic.main.review_component.view.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.fetchers.Review
import slak.fanfictionstories.fetchers.getReviews
import slak.fanfictionstories.utility.*
import java.text.SimpleDateFormat
import java.util.*

class ReviewsActivity : LoadingActivity() {
  companion object {
    const val INTENT_STORY_MODEL = "story_model_extra"
    const val INTENT_TARGET_CHAPTER = "target_chapter_extra"
    private const val RESTORE_CHAPTER = "restore_chapter"
    private const val RESTORE_CURRENT_PAGE = "restore_curr_page"
    private const val RESTORE_TOTAL_PAGES = "restore_total"
    private const val RESTORE_MODEL = "restore_model"
    private const val RESTORE_ADAPTER = "restore_adapter"
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

    title = str(R.string.reviews_for, model.title)
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

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(RESTORE_CHAPTER, chapter)
    outState.putInt(RESTORE_CURRENT_PAGE, currentPage)
    outState.putInt(RESTORE_TOTAL_PAGES, totalPages)
    outState.putParcelable(RESTORE_MODEL, model)
    outState.putParcelableArray(RESTORE_ADAPTER, adapter.getData().toTypedArray())
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    super.onRestoreInstanceState(savedInstanceState)
    chapter = savedInstanceState.getInt(RESTORE_CHAPTER)
    currentPage = savedInstanceState.getInt(RESTORE_CURRENT_PAGE)
    totalPages = savedInstanceState.getInt(RESTORE_TOTAL_PAGES)
    model = savedInstanceState.getParcelable(RESTORE_MODEL)
    val data = savedInstanceState.getParcelableArray(RESTORE_ADAPTER)
    @Suppress("unchecked_cast")
    adapter.addReviews((data as Array<Review>).toList())
    hideLoading()
  }

  private fun setSubtitle() {
    // No need for a subtitle saying which chapter it is when there is only one
    if (model.fragment.chapterCount == 1L) {
      toolbar.subtitle = ""
      return
    }
    toolbar.subtitle =
        if (chapter == 0) str(R.string.all_chapters)
        else str(R.string.chapter_x, chapter)
  }

  private fun addPage(page: Int) = launch(UI) {
    if (totalPages != 0 && page >= totalPages) return@launch
    showLoading()
    val (list, pages) = getReviews(model.storyId, chapter, page).await()
    if (totalPages == 0) totalPages = pages
    if (pages == -1) noReviewsText.visibility = View.VISIBLE
    else noReviewsText.visibility = View.INVISIBLE
    adapter.addReviews(list)
    hideLoading()
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.selectReviewsFor).iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Don't show a chapter selection menu if there is only one chapter
    if (model.fragment.chapterCount == 1L) return false
    menuInflater.inflate(R.menu.menu_reviews, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.selectReviewsFor -> {
        val items = listOf(
            str(R.string.all_chapters),
            *model.chapterTitles().toTypedArray()
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
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}

class ReviewAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class ReviewHolder(val view: CardView) : RecyclerView.ViewHolder(view)

  private val reviews: MutableList<Review> = mutableListOf()

  fun getData(): List<Review> = reviews.toList()

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

  private fun bindReviewProps(view: CardView, review: Review) {
    with(view) {
      reviewAuthor.text = review.author
      reviewChapter.text = str(R.string.chapter_x, review.chapter)
      reviewDate.text = SimpleDateFormat.getDateInstance()
          .format(Date(review.unixTimeSeconds * 1000))
      reviewContent.text = review.content
      viewAuthorBtn.setOnClickListener {
        startActivity<AuthorActivity>(
            AuthorActivity.INTENT_AUTHOR_ID to review.authorId,
            AuthorActivity.INTENT_AUTHOR_NAME to review.author)
      }
      if (review.authorId == -1L) {
        viewAuthorBtn.visibility = View.GONE
        replyBtn.visibility = View.GONE
      } else {
        viewAuthorBtn.visibility = View.VISIBLE
        replyBtn.visibility = View.VISIBLE
      }
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
    with((holder as ReviewHolder).view) {
      bindReviewProps(this, reviews[pos])
      replyBtn.setOnClickListener {
        // FIXME: reply to review
      }
      reportBtn.setOnClickListener {
        val layout = LayoutInflater.from(context)
            .inflate(R.layout.dialog_report_review, null, false)
        // We want the margin, so invisible
        layout.offendingReview.divider.visibility = View.INVISIBLE
        layout.offendingReview.btnBar.visibility = View.GONE
        layout.offendingReview.elevation = 20F // FIXME magic arbitrary number
        bindReviewProps(layout.offendingReview as CardView, reviews[pos])
        AlertDialog.Builder(context)
            .setTitle(R.string.dialog_report_abuse)
            .setView(layout)
            .setPositiveButton(R.string.report, { _, _ ->
              // FIXME: send review
            })
            .show()
      }
      forceLayout()
    }
  }
  override fun getItemCount(): Int = reviews.size
}
