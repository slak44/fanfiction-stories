package slak.fanfictionstories.activities

import android.annotation.SuppressLint
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Intent
import android.os.Bundle
import android.support.annotation.AnyThread
import android.support.annotation.UiThread
import android.support.v7.app.AlertDialog
import android.support.v7.widget.CardView
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.*
import android.widget.ProgressBar
import kotlinx.android.synthetic.main.activity_reviews.*
import kotlinx.android.synthetic.main.component_review.view.*
import kotlinx.android.synthetic.main.dialog_report_review.view.*
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import slak.fanfictionstories.R
import slak.fanfictionstories.StoryModel
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.fetchers.NO_PAGES
import slak.fanfictionstories.data.fetchers.Review
import slak.fanfictionstories.data.fetchers.fetchStoryModel
import slak.fanfictionstories.data.fetchers.getReviews
import slak.fanfictionstories.utility.*
import java.util.*

/** Stores and fetches the data required for a [ReviewsActivity]. */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class ReviewsViewModel(val model: StoryModel, initialChapter: java.lang.Integer) :
    ViewModel(),
    IAdapterDataObservable by AdapterDataObservable() {
  private val _chapter = MutableLiveData<Int>()
  val chapter: LiveData<Int> get() = _chapter

  enum class LoadEvent { LOADING, DONE_LOADING }

  private val loadingEventsData = MutableLiveData<LoadEvent>()
  val loadingEvent: LiveData<LoadEvent> get() = loadingEventsData

  private var currentPage = 0
  var pageCount = 0
    private set
  var reviewCount = 0
    private set

  private val reviewsList: MutableList<Review> = mutableListOf()
  val reviews: List<Review> get() = reviewsList

  init {
    changeChapter(initialChapter.toInt())
  }

  @UiThread
  fun changeChapter(newChapter: Int) {
    clear()
    currentPage = 1
    pageCount = 0
    reviewCount = 0
    _chapter.it = newChapter
  }

  private fun clear() {
    val size = reviewsList.size
    reviewsList.clear()
    notifyItemRangeRemoved(0, size)
  }

  @AnyThread
  fun loadPage(coroutineScope: CoroutineScope) = coroutineScope.launch(Main) {
    if (pageCount != 0 && currentPage >= pageCount) return@launch
    loadingEventsData.it = LoadEvent.LOADING
    val (list, pages, reviews) = getReviews(model.storyId, _chapter.it, currentPage)
    if (pageCount == 0) pageCount = pages
    if (reviewCount == 0) reviewCount = reviews
    reviewsList.addAll(list)
    notifyItemRangeInserted(reviewsList.size - list.size, list.size)
    loadingEventsData.it = LoadEvent.DONE_LOADING
    currentPage++
  }
}

/** Presents a story's reviews. */
class ReviewsActivity : CoroutineScopeActivity(), IHasLoadingBar {
  companion object {
    const val INTENT_STORY_MODEL = "story_model_extra"
    const val INTENT_TARGET_CHAPTER = "target_chapter_extra"
    private const val ALL_CHAPTERS = 0
  }

  override val loading: ProgressBar
    get() = activityProgressBar

  private lateinit var viewModel: ReviewsViewModel

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_reviews)
    setSupportActionBar(toolbar)
    setLoadingView(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    // runBlocking is fine here, because the loading bar is already in place, and so the user
    // already expects to wait
    val (model, chapter) = if (intent.action == Intent.ACTION_VIEW) runBlocking {
      val pathSegments = intent?.data?.pathSegments
          ?: throw IllegalArgumentException("Missing intent data")
      val chapter = if (pathSegments.size < 3) ALL_CHAPTERS else pathSegments[2].toInt()
      val model = fetchStoryModel(pathSegments[1].toLong())
          .orElseThrow(IllegalArgumentException("Story doesn't exist"))
      return@runBlocking model to chapter
    } else {
      val chapter = intent.getIntExtra(INTENT_TARGET_CHAPTER, ALL_CHAPTERS)
      val model: StoryModel = intent.getParcelableExtra(ReviewsActivity.INTENT_STORY_MODEL)
      model to chapter
    }

    viewModel = obtainViewModel(model, chapter)

    title = str(R.string.reviews_for, viewModel.model.title)

    reviewList.adapter = ReviewAdapter(viewModel)
    val layoutManager = LinearLayoutManager(this)
    reviewList.layoutManager = layoutManager

    viewModel.loadingEvent.observe(this) {
      when (it) {
        ReviewsViewModel.LoadEvent.LOADING -> showLoading()
        ReviewsViewModel.LoadEvent.DONE_LOADING -> {
          hideLoading()
          if (viewModel.pageCount == NO_PAGES) noReviewsText.visibility = View.VISIBLE
          else noReviewsText.visibility = View.INVISIBLE
          setSubtitle()
        }
      }
    }

    viewModel.loadPage(this)
    infinitePageScroll(reviewList, layoutManager) { viewModel.loadPage(this) }
  }

  private fun setSubtitle() {
    // No need for a subtitle saying which chapter it is when there is only one
    if (viewModel.model.fragment.chapterCount == 1L) {
      toolbar.subtitle = ""
      return
    }
    toolbar.subtitle =
        if (viewModel.chapter.value == ALL_CHAPTERS) str(R.string.all_chapters)
        else str(R.string.x_reviews_for_chapter_y, viewModel.reviewCount, viewModel.chapter.value)
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.selectReviewsFor).iconTint(R.color.white, theme)
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Don't show a chapter selection menu if there is only one chapter
    if (viewModel.model.fragment.chapterCount == 1L) return false
    menuInflater.inflate(R.menu.menu_reviews, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.selectReviewsFor -> {
        val items = listOf(
            str(R.string.all_chapters),
            *viewModel.model.chapterTitles().toTypedArray()
        ).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.select_chapter)
            .setSingleChoiceItems(items, viewModel.chapter.value!! - 1) { dialog, which: Int ->
              dialog.dismiss()
              if (viewModel.chapter.value == which) return@setSingleChoiceItems
              viewModel.changeChapter(which)
              viewModel.loadPage(this)
              setSubtitle()
            }.show()
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }
}

/** For using [Review] objects with [RecyclerView]. */
class ReviewAdapter(private val viewModel: ReviewsViewModel) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class ReviewHolder(val view: CardView) : RecyclerView.ViewHolder(view)

  private val vmObserver = createObserverForAdapter(this)

  init {
    viewModel.registerObserver(vmObserver)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    return ReviewHolder(LayoutInflater.from(parent.context)
        .inflate(R.layout.component_review, parent, false) as CardView)
  }

  private fun bindReviewProps(view: CardView, review: Review) = with(view) {
    reviewAuthor.text = review.author
    reviewChapter.text = str(R.string.chapter_x, review.chapter)
    reviewDate.text = Prefs.simpleDateFormatter
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

  @SuppressLint("InflateParams")
  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) = with(holder.itemView) {
    bindReviewProps(this as CardView, viewModel.reviews[pos])
    replyBtn.setOnClickListener {
      // FIXME: reply to review
    }
    reportBtn.setOnClickListener {
      val layout = LayoutInflater.from(context).inflate(R.layout.dialog_report_review, null, false)
      // We want the margin, so invisible
      layout.offendingReview.divider.visibility = View.INVISIBLE
      layout.offendingReview.btnBar.visibility = View.GONE
      layout.offendingReview.elevation = 20F
      bindReviewProps(layout.offendingReview as CardView, viewModel.reviews[pos])
      AlertDialog.Builder(context)
          .setTitle(R.string.dialog_report_abuse)
          .setView(layout)
          .setPositiveButton(R.string.report) { _, _ ->
            // FIXME: send report
          }
          .show()
    }
    forceLayout()
  }

  override fun getItemCount(): Int = viewModel.reviews.size
}
