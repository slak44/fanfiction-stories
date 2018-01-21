package slak.fanfictionstories

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
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

enum class GroupStrategy {
  // Group by property
  CANON, AUTHOR, CATEGORY, STATUS, RATING, LANGUAGE, COMPLETION,
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
  val srcKey = when (strategy) {
    GroupStrategy.CANON -> "canon"
    GroupStrategy.AUTHOR -> "author"
    GroupStrategy.CATEGORY -> "category"
    GroupStrategy.STATUS -> "status"
    GroupStrategy.RATING -> "rating"
    GroupStrategy.LANGUAGE -> "language"
    GroupStrategy.COMPLETION -> "isCompleted"
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

enum class OrderDirection { ASC, DESC }

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

class StoryAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class StoryViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)
  class TitleViewHolder(val view: StoryGroupTitle) : RecyclerView.ViewHolder(view)

  /**
   * Reference to currently linked [RecyclerView]. Null if not attached.
   */
  private var recycler: RecyclerView? = null
  override fun onAttachedToRecyclerView(recyclerView: RecyclerView?) {
    super.onAttachedToRecyclerView(recyclerView)
    recycler = recyclerView
  }
  override fun onDetachedFromRecyclerView(oldRecyclerView: RecyclerView?) {
    super.onDetachedFromRecyclerView(oldRecyclerView)
    recycler = null
  }

  /**
   * Pending items are items that have been hidden with [hideStory], and can be reinstated using
   * [undoHideStory]. This maps a [StoryModel] to a pair containing its current adapter position and
   * [stories] array position.
   */
  private val pendingItems = mutableMapOf<StoryModel, Pair<Int, Int>>()

  /**
   * Remove the story from the adapter and keep track of its data.
   * @param position adapter position of story view
   * @see pendingItems
   * @see undoHideStory
   */
  fun hideStory(position: Int, model: StoryModel) {
    if (!stories.contains(model)) throw IllegalArgumentException("Model not part of the adapter")
    val storiesIndex = stories.indexOf(model)
    pendingItems[model] = Pair(position, storiesIndex)
    data.removeAt(position)
    stories.removeAt(storiesIndex)
    notifyItemRemoved(position)
  }

  /**
   * Reverses the effects of [hideStory].
   * @param model a model that was previously passed to [hideStory]
   * @see pendingItems
   * @see hideStory
   */
  fun undoHideStory(model: StoryModel) {
    val pos = pendingItems[model] ?: throw IllegalArgumentException("This model was never hidden")
    data.add(pos.first, Left(model))
    stories.add(pos.second, model)
    pendingItems.remove(model)
    notifyItemInserted(pos.first)
  }

  /**
   * Stores working data (stories and group titles).
   */
  private val data: MutableList<Either<StoryModel, String>> = mutableListOf()

  /**
   * Get an immutable copy of [data], for serialization purposes.
   */
  fun getAdapterData(): List<Either<StoryModel, String>> = data.toList()

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
  }

  /**
   * Stories held by this adapter.
   */
  private var stories: MutableList<StoryModel> = mutableListOf()

  /**
   * Get an immutable copy of [stories].
   */
  fun getStories(): List<StoryModel> = stories.toList()

  @Deprecated("Should use addData instead")
  fun setStories(stories: MutableList<StoryModel>) {
    this.stories = stories
    clearData()
  }

  fun addData(storyOrTitle: Either<StoryModel, String>) {
    data.add(storyOrTitle)
    storyOrTitle.fold( { stories.add(it) }, { false })
    notifyItemInserted(data.size - 1)
  }

  fun addData(storyOrTitleList: List<Either<StoryModel, String>>) {
    data.addAll(storyOrTitleList)
    storyOrTitleList.forEach {
      it.fold( { stories.add(it) }, { false })
    }
    notifyItemRangeInserted(data.size, storyOrTitleList.size)
  }

  fun updateStory(storyIdx: Int, storyModel: StoryModel) {
    val storyDataIdx = data.indexOf(Left(stories[storyIdx]))
    data[storyDataIdx] = Left(storyModel)
    stories[storyIdx] = storyModel
    notifyItemChanged(storyDataIdx)
  }

  @Deprecated("Should use getStories().size")
  val storyCount
    get() = stories.size

  var filteredCount: Int = 0
    private set

  var groupStrategy: GroupStrategy = GroupStrategy.NONE
  var orderStrategy: OrderStrategy = OrderStrategy.TITLE_ALPHABETIC
  var orderDirection: OrderDirection = OrderDirection.DESC

  /**
   * Filter, group, then sort [stories] according to the [orderDirection], [orderStrategy],
   * [groupStrategy].
   *
   * Rebuilds [data] and [stories]. Call in UI thread.
   */
  fun arrangeStories() {
    val toData = stories.filter { true }.toMutableList() // FIXME filter
    filteredCount = stories.size - toData.size
    stories.clear()
    clearData()
    groupStories(toData, groupStrategy).forEach {
      val ordered = orderStories(it.value, orderStrategy, orderDirection)
      addData(listOf(Right(it.key), *ordered.map { Left(it) }.toTypedArray()))
    }
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
