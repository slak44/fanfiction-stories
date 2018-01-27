package slak.fanfictionstories

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.support.v4.content.ContextCompat.startActivity
import android.support.v7.widget.CardView
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import either.Either
import either.Left
import either.Right
import either.fold
import kotlinx.android.synthetic.main.story_component.view.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.activities.AuthorActivity
import slak.fanfictionstories.activities.StoryReaderActivity
import slak.fanfictionstories.fetchers.getFullStory
import slak.fanfictionstories.utility.*
import kotlin.Comparator

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  var currentModel: StoryModel? = null

  companion object {
    fun createRightSwipeHelper(
        recyclerView: RecyclerView,
        openStoryReader: (intent: Intent, id: Long) -> Unit
    ): ItemTouchHelper {
      lateinit var swipeStory: ItemTouchHelper
      swipeStory = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recycler: RecyclerView?, vh: RecyclerView.ViewHolder?): Int
            = makeMovementFlags(0, ItemTouchHelper.RIGHT)

        override fun onMove(recycler: RecyclerView?, viewHolder: RecyclerView.ViewHolder?,
                            target: RecyclerView.ViewHolder?): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
          val intent = Intent(recyclerView.context, StoryReaderActivity::class.java)
          val cardView = viewHolder.itemView as StoryCardView
          intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, cardView.currentModel!!)
          openStoryReader(intent, cardView.currentModel!!.storyIdRaw)
          // After the reader was opened, reset the translation by reattaching
          // We do this because we might go back from the reader to this activity and
          // it has to look properly
          launch(UI) {
            delay(500)
            swipeStory.attachToRecyclerView(null)
            swipeStory.attachToRecyclerView(recyclerView)
          }
        }
      })
      swipeStory.attachToRecyclerView(recyclerView)
      return swipeStory
    }

    const val DEFAULT_ELEVATION = 7F
    const val CLICK_ELEVATION = 20F
  }

  fun loadFromModel(model: StoryModel) {
    currentModel = model
    // Unexpanded view
    titleText.text = model.title
    authorText.text = model.author
    canonText.text = model.canon
    wordsText.text = model.words
    storyProgress.progress = Math.round(model.progress).toInt()
    isCompletedText.visibility = if (model.isCompleted) View.VISIBLE else View.INVISIBLE
    // Detail view
    languageText.text = model.language
    ratingText.text = model.rating
    summaryText.text = model.summary
    chaptersText.text = model.chapters
    genresText.text = model.genres
    charactersText.text = model.characters
    categoryText.text = model.category
    categoryText.visibility = if (model.categoryRaw.isEmpty()) View.GONE else View.VISIBLE
    updateDateText.text = model.updateDate
    if (model.updateDateSeconds == 0L) {
      // Do this instead of View.GONE or View.INVISIBLE because we want
      // its margins, but not its height
      updateDateText.height = 0
      updateDateText.requestLayout()
    }
    publishDateText.text = model.publishDate
    reviewsText.text = model.reviews
    favoritesText.text = model.favorites
    followsText.text = model.follows
    storyIdText.text = model.storyId

    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.GONE
    if (model.status == StoryStatus.TRANSIENT) removeBtn.visibility = View.GONE

    // Reset card UI (including the measured size) to default
    storyDetails.visibility = View.GONE
    divider.visibility = View.GONE
    btnBar.visibility = View.GONE
    cardElevation = DEFAULT_ELEVATION
    addBtn.isEnabled = true
    val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    measure(unspec, unspec)
  }

  fun setChildrenListeners(model: StoryModel, holder: RecyclerView.ViewHolder, adapter: StoryAdapter) {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      cardElevation = if (cardElevation == DEFAULT_ELEVATION) CLICK_ELEVATION else DEFAULT_ELEVATION
      if (storyDetails.visibility == View.GONE) {
        storyDetails.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        btnBar.visibility = View.VISIBLE
      } else {
        storyDetails.visibility = View.GONE
        divider.visibility = View.GONE
        btnBar.visibility = View.GONE
      }
    }
    removeBtn.setOnClickListener {
      // Even though we have a model, fetch it from db to make sure there are no inconsistencies
      val dbModel = context.database.storyById(model.storyIdRaw)
      if (!dbModel.isPresent) {
        errorDialog(context, R.string.storyid_does_not_exist, R.string.storyid_does_not_exist_tip)
        return@setOnClickListener
      }
      // Hide card
      adapter.hideStory(holder.adapterPosition, model)
      undoableAction(holder.itemView, R.string.removed_story, {
        adapter.undoHideStory(model)
      }) {
        deleteLocalStory(context, model.storyIdRaw).join()
        context.database.writableDatabase
            .delete("stories", "storyId = ?", arrayOf(model.storyIdRaw.toString()))
      }
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = context.resources.getString(R.string.adding___)
      val n = Notifications(this@StoryCardView.context, Notifications.Kind.DOWNLOADING)
      launch(CommonPool) {
        val newModel = getFullStory(this@StoryCardView.context, model.storyIdRaw, n).await()
        launch(UI) {
          if (newModel.isPresent) {
            addBtn.visibility = View.GONE
          } else {
            addBtn.visibility = View.VISIBLE
            addBtn.isEnabled = true
            addBtn.text = resources.getString(R.string.add)
          }
        }
        n.cancel()
      }
    }
    authorBtn.setOnClickListener {
      val intent = Intent(context, AuthorActivity::class.java)
      intent.putExtra(AuthorActivity.INTENT_AUTHOR_ID, model.authorIdRaw)
      intent.putExtra(AuthorActivity.INTENT_AUTHOR_NAME, model.authorRaw)
      startActivity(context, intent, null)
    }
  }
}

class StoryGroupTitle : TextView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    private val borderHeight =
        Static.res.getDimensionPixelSize(R.dimen.story_list_title_divider_height)
    private val bottomMargin = Static.res.getDimensionPixelSize(R.dimen.story_list_margin)
  }

  init {
    setPadding(0, 0, 0,
        resources.getDimensionPixelSize(R.dimen.story_list_title_underline_margin))
  }

  private val border: Paint by lazy {
    val border = Paint()
    border.style = Paint.Style.STROKE
    border.color = Static.res.getColor(android.R.color.secondary_text_dark, this.context.theme)
    border.strokeWidth = borderHeight.toFloat()
    border
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    (layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, bottomMargin)
    requestLayout()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val width = (parent as RecyclerView).measuredWidth.toFloat()
    canvas.drawLine(0F, measuredHeight.toFloat(), width, measuredHeight.toFloat(), border)
  }
}

class StoryAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class StoryViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)
  class TitleViewHolder(val view: StoryGroupTitle) : RecyclerView.ViewHolder(view)

  /**
   * Stores working data (stories and group titles).
   */
  private val data: MutableList<Either<StoryModel, String>> = mutableListOf()

  /**
   * Get an immutable copy of [data], for serialization purposes.
   */
  fun getData(): List<Either<StoryModel, String>> = data.toList()

  /**
   * Clear adapter data, including any pending items.
   * @see data
   * @see pendingItems
   */
  fun clearData() {
    val dataSize = data.size
    data.clear()
    notifyItemRangeRemoved(0, dataSize)
    pendingItems.clear()
    onSizeChange(0, 0)
  }

  fun addData(storyOrTitle: Either<StoryModel, String>) {
    data.add(storyOrTitle)
    notifyItemInserted(data.size - 1)
    storyCount++
    onSizeChange(storyCount, filteredCount)
  }

  fun addData(storyOrTitleList: List<Either<StoryModel, String>>) {
    data.addAll(storyOrTitleList)
    notifyItemRangeInserted(data.size, storyOrTitleList.size)
    storyCount += storyOrTitleList.size
    onSizeChange(storyCount, filteredCount)
  }

  /**
   * Pending items are items that have been hidden with [hideStory], and can be reinstated using
   * [undoHideStory]. This maps a [StoryModel] to its current adapter position.
   */
  private val pendingItems = mutableMapOf<StoryModel, Int>()

  /**
   * Remove the story from the adapter and keep track of its data.
   * @param position adapter position of story view
   * @see pendingItems
   * @see undoHideStory
   */
  fun hideStory(position: Int, model: StoryModel) {
    if (!data.contains(Left(model))) throw IllegalArgumentException("Model not part of the adapter")
    pendingItems[model] = position
    data.removeAt(position)
    notifyItemRemoved(position)
    storyCount--
    onSizeChange(storyCount, filteredCount)
  }

  /**
   * Reverses the effects of [hideStory].
   * @param model a model that was previously passed to [hideStory]
   * @see pendingItems
   * @see hideStory
   */
  fun undoHideStory(model: StoryModel) {
    val pos = pendingItems[model] ?: throw IllegalArgumentException("This model was never hidden")
    data.add(pos, Left(model))
    pendingItems.remove(model)
    notifyItemInserted(pos)
    storyCount++
    onSizeChange(storyCount, filteredCount)
  }

  /**
   * Called when the story count changes.
   */
  var onSizeChange: (storyCount: Int, filteredCount: Int) -> Unit = { _, _ -> }

  /**
   * How many stories have been filtered in the latest [arrangeStories] call.
   */
  private var filteredCount = 0
  /**
   * How many stories were passed to the latest [arrangeStories] call.
   */
  private var storyCount = 0

  /**
   * Filter, group, sort [stories] according to the [arrangement], and put the results in [data].
   */
  fun arrangeStories(stories: List<StoryModel>, arrangement: Arrangement) {
    // Ignore currently pending stories, the user might have rearranged before the db was updated
    val storiesNotPending = stories.filter { story ->
      pendingItems.keys.find { it.storyIdRaw == story.storyIdRaw } == null
    }
    clearData()
    val toData = storiesNotPending.filter { true }.toMutableList() // FIXME filter
    groupStories(toData, arrangement.groupStrategy).forEach {
      val ordered = orderStories(it.value, arrangement.orderStrategy, arrangement.orderDirection)
      addData(listOf(Right(it.key), *ordered.map { Left(it) }.toTypedArray()))
    }
    filteredCount = storiesNotPending.size - toData.size
    storyCount = storiesNotPending.size
    onSizeChange(storyCount, filteredCount)
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    data[position].fold(
        { model ->
          val view = (holder as StoryViewHolder).view
          view.loadFromModel(model)
          view.setChildrenListeners(model, holder, this)
        },
        { title -> (holder as TitleViewHolder).view.text = title }
    )
  }

  private var addedStory: Long = 0

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val view: View = if (viewType == 1) {
      StoryGroupTitle(context)
    } else {
      // Only increase animation duration from stories
      addedStory++
      LayoutInflater.from(parent.context)
          .inflate(R.layout.story_component, parent, false) as StoryCardView
    }
    view.alpha = 0F
    view.isDrawingCacheEnabled = true
    val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0.3F, 1F)
    fadeIn.startDelay = Math.min(addedStory * 50, 250)
    fadeIn.start()
    return if (viewType == 1) TitleViewHolder(view as StoryGroupTitle)
    else StoryViewHolder(view as StoryCardView)
  }

  override fun getItemCount(): Int = data.size
  override fun getItemId(position: Int): Long = data[position].fold(
      { model -> model.storyIdRaw },
      { title -> title.hashCode().toLong() }
  )
  override fun getItemViewType(position: Int): Int = data[position].fold({ return 0 }, { return 1 })
}
