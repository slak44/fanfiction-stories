package slak.fanfictionstories

import android.animation.ObjectAnimator
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Parcelable
import android.support.v7.widget.CardView
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.android.parcel.Parcelize
import kotlinx.android.synthetic.main.story_component.view.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import slak.fanfictionstories.StoryListItem.*
import slak.fanfictionstories.activities.AuthorActivity
import slak.fanfictionstories.activities.StoryReaderActivity
import slak.fanfictionstories.fetchers.fetchAndWriteStory
import slak.fanfictionstories.utility.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adds a [ItemTouchHelper] to the recycler that lets stories be swiped right to be read.
 * @param onSwiped called when a story is entered, with the model used in the intent
 */
fun RecyclerView.createStorySwipeHelper(onSwiped: (StoryModel) -> Unit = {}) {
  // Use a `lateinit var` because the coroutine inside cannot access
  // the `this` that is the ItemTouchHelper.Callback
  lateinit var swipeStory: ItemTouchHelper
  swipeStory = ItemTouchHelper(object : ItemTouchHelper.Callback() {
    override fun getMovementFlags(recycler: RecyclerView, vh: RecyclerView.ViewHolder): Int = if (vh is StoryAdapter.StoryViewHolder) makeMovementFlags(0, ItemTouchHelper.RIGHT) else 0

    override fun onMove(recycler: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
      if (viewHolder !is StoryAdapter.StoryViewHolder) return
      val cardView = viewHolder.itemView as StoryCardView
      onSwiped(cardView.currentModel!!)
      startActivity<StoryReaderActivity>(
          StoryReaderActivity.INTENT_STORY_MODEL to cardView.currentModel!! as Parcelable)
      // After the reader was opened, reset the translation by reattaching
      // We do this because we might go back from the reader to this activity and
      // it has to look properly
      launch(UI) {
        delay(500)
        swipeStory.attachToRecyclerView(null)
        swipeStory.attachToRecyclerView(this@createStorySwipeHelper)
      }
    }
  })
  swipeStory.attachToRecyclerView(this)
}

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    const val DEFAULT_ELEVATION = 7F
    const val CLICK_ELEVATION = 20F
  }

  var currentModel: StoryModel? = null

  /**
   * Whether or not the details layout is visible.
   */
  var isExtended: Boolean = false
    set(value) {
      if (value) {
        cardElevation = CLICK_ELEVATION
        storyDetails.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        btnBar.visibility = View.VISIBLE
      } else {
        cardElevation = DEFAULT_ELEVATION
        storyDetails.visibility = View.GONE
        divider.visibility = View.GONE
        btnBar.visibility = View.GONE
      }
      field = value
      onExtendedStateChange(value)
    }

  /**
   * Called when the view is extended/unextended.
   */
  var onExtendedStateChange: (Boolean) -> Unit = {}

  fun loadFromModel(model: StoryModel) {
    currentModel = model
    // Unexpanded view
    titleText.text = model.title
    authorText.text = str(R.string.by_author, model.author)
    canonText.text = str(R.string.in_canon, model.canon)
    wordsText.text = str(R.string.x_words, autoSuffixNumber(model.fragment.wordCount))
    storyProgress.progress = Math.round(model.progressAsPercentage()).toInt()
    isCompletedText.visibility = if (model.isComplete()) View.VISIBLE else View.INVISIBLE
    // Detail view
    languageText.text = model.fragment.language
    ratingText.text = str(R.string.rated_x, model.fragment.rating)
    summaryText.text = model.summary
    chaptersText.text = if (model.progress.currentChapter == 0L) {
      // If we didn't start reading the thing, show total chapter count
      // Special-case one chapter
      if (model.fragment.chapterCount == 1L) str(R.string.one_chapter)
      else str(R.string.x_chapters, model.fragment.chapterCount)
    } else {
      // Otherwise, list current chapter out of total
      str(R.string.chapter_progress, model.progress.currentChapter, model.fragment.chapterCount)
    }
    genresText.text = str(R.string.about_genres, model.fragment.genres)
    charactersText.text = str(R.string.with_characters, model.fragment.characters)
    categoryText.text = str(R.string.in_category, model.category)
    categoryText.visibility = if (model.category == null) View.GONE else View.VISIBLE
    updateDateText.text = str(R.string.updated_on,
        SimpleDateFormat.getDateInstance().format(Date(model.fragment.updateTime * 1000)))
    if (model.fragment.updateTime == 0L) {
      // Do this instead of View.GONE or View.INVISIBLE because we want
      // its margins, but not its height
      updateDateText.height = 0
      updateDateText.requestLayout()
    }
    publishDateText.text = str(R.string.published_on,
        SimpleDateFormat.getDateInstance().format(Date(model.fragment.publishTime * 1000)))
    reviewsText.text = str(R.string.x_reviews, model.fragment.reviews)
    favoritesText.text = str(R.string.x_favorites, model.fragment.favorites)
    followsText.text = str(R.string.x_follows, model.fragment.follows)
    storyIdText.text = str(R.string.storyid_x, model.storyId)

    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.GONE
    if (model.status == StoryStatus.TRANSIENT) removeBtn.visibility = View.GONE

    // Reset card UI (including the measured size) to default
    addBtn.isEnabled = true
    val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
    measure(unspec, unspec)
  }

  fun setChildrenListeners(model: StoryModel, holder: RecyclerView.ViewHolder,
                           viewModel: StoryListViewModel) {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    // Show/hide details layout when pressing content
    storyMainContent.setOnClickListener { isExtended = !isExtended }
    removeBtn.setOnClickListener {
      // Even though we have a model, fetch it from db to make sure there are no inconsistencies
      val dbModel = runBlocking { Static.database.storyById(model.storyId).await() }
      if (!dbModel.isPresent) {
        errorDialog(R.string.storyid_does_not_exist, R.string.storyid_does_not_exist_tip)
        return@setOnClickListener
      }
      // Hide card
      viewModel.hideStory(holder.adapterPosition, model)
      // We need this because otherwise the screen gets out of sync with the data
      viewModel.notifyChanged()
      undoableAction(holder.itemView, R.string.removed_story, {
        viewModel.undoHideStory(model)
      }) {
        deleteLocalStory(context, model.storyId).join()
        context.database.useAsync {
          val currentResume = Static.prefs.getLong(Prefs.RESUME_STORY_ID, -1)
          if (currentResume == model.storyId) Prefs.useImmediate { it.remove(Prefs.RESUME_STORY_ID) }
          delete("stories", "storyId = ?", arrayOf(model.storyId.toString()))
        }.await()
      }
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = str(R.string.adding___)
      launch(UI) {
        val newModel = fetchAndWriteStory(model.storyId).await()
        if (newModel.isPresent) {
          addBtn.visibility = View.GONE
        } else {
          addBtn.visibility = View.VISIBLE
          addBtn.isEnabled = true
          addBtn.text = str(R.string.download)
        }
      }
    }
    authorBtn.setOnClickListener {
      startActivity<AuthorActivity>(
          AuthorActivity.INTENT_AUTHOR_ID to model.authorId,
          AuthorActivity.INTENT_AUTHOR_NAME to model.author)
    }
  }
}

class StoryGroupTitle : TextView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    private val borderHeight = Static.res.px(R.dimen.story_list_title_divider_height)
    private val bottomMargin = Static.res.px(R.dimen.story_list_margin)
  }

  init {
    setPadding(0, 0, 0, resources.px(R.dimen.story_list_title_underline_margin))
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

@SuppressWarnings("ParcelCreator")
sealed class StoryListItem : Parcelable {
  @Parcelize
  data class StoryCardData(var model: StoryModel,
                           var isExtended: Boolean = false) : StoryListItem()

  @Parcelize
  data class GroupTitle(val title: String) : StoryListItem()

  @Parcelize
  data class LoadingItem(val id: Int = ++counter) : StoryListItem()

  companion object {
    private var counter: Int = 0xF000000
  }
}

/**
 * Handles data for a list of stories. Recommended to be used with a [RecyclerView] and a
 * [RecyclerView.Adapter], for handling [IAdapterDataObservable] events.
 */
open class StoryListViewModel : ViewModel(), IAdapterDataObservable by AdapterDataObservable() {
  companion object {
    private const val TAG = "StoryListViewModel"
  }

  /** The stories, group titles, and loading items of the list. */
  private val data: MutableList<StoryListItem> = mutableListOf()

  /**
   * FIXME: see if room can make this obsolete
   */
  fun triggerDatabaseLoad() = launch(UI) {
    val stories = Static.database.getStories().await()
    arrangeStories(stories, Prefs.arrangement())
  }

  /** How many stories are in [data]. */
  private val storyCount: MutableLiveData<Int> = MutableLiveData()
  /** How many stories have been filtered in the latest [arrangeStories] call. */
  private val filteredCount: MutableLiveData<Int> = MutableLiveData()
  /** Mediates [storyCount] and [filteredCount]. */
  private val dataSize: MediatorLiveData<Pair<Int, Int>> = MediatorLiveData()

  fun getStoryCount(): LiveData<Int> = storyCount
  fun getFilteredCount(): LiveData<Int> = storyCount
  fun getCounts(): LiveData<Pair<Int, Int>> = dataSize

  init {
    storyCount.it = 0
    filteredCount.it = 0
    dataSize.it = Pair(0, 0)
    dataSize.addSource(storyCount, {
      dataSize.it = Pair(it!!, dataSize.it.second)
    })
    dataSize.addSource(filteredCount, {
      dataSize.it = Pair(dataSize.it.first, it!!)
    })
  }

  /** Get the [StoryListItem] at the given position. */
  fun itemAt(position: Int) = data[position]

  /** Get how many items are stored. */
  fun itemCount() = data.size

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
    storyCount.it = 0
    filteredCount.it = 0
  }

  fun addData(item: StoryListItem) {
    data.add(item)
    notifyItemRangeInserted(data.size - 1, 1)
    if (item is StoryCardData) storyCount.it++
  }

  fun addData(items: List<StoryListItem>) {
    data.addAll(items)
    notifyItemRangeInserted(data.size, items.size)
    val newStories = items.count { it is StoryCardData }
    if (newStories > 0) storyCount.it += newStories
  }

  fun addDeferredData(deferredList: Deferred<List<StoryListItem>>) = launch(UI) {
    addData(LoadingItem())
    val loaderIdx = data.size - 1
    addData(deferredList.await())
    data.removeAt(loaderIdx)
    notifyItemRangeRemoved(loaderIdx, 1)
  }

  /** Update [StoryModel.progress] and [StoryModel.status] from the provided model. */
  fun updateStoryModel(newModel: StoryModel) {
    val idx = data.indexOfFirst { it is StoryCardData && it.model.storyId == newModel.storyId }
    if (idx == -1) throw IllegalArgumentException("Model not part of the adapter")
    (data[idx] as StoryCardData).model.progress = newModel.progress
    (data[idx] as StoryCardData).model.status = newModel.status
    notifyItemRangeChanged(idx, 1)
  }

  /** Update a story's card extension state. */
  fun updateStoryState(position: Int, state: Boolean) {
    if (data[position] !is StoryCardData) {
      throw IllegalArgumentException("Item at $position is not a StoryCardData")
    }
    data[position] = StoryCardData((data[position] as StoryCardData).model, state)
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
    if (data.find { it is StoryCardData && it.model.storyId == model.storyId } == null)
      throw IllegalArgumentException("Model not part of the adapter")
    Log.v(TAG, "hideStory: pos=$position, model: $model")
    pendingItems[model] = position
    data.removeAt(position)
    notifyItemRangeRemoved(position, 1)
    storyCount.it--
  }

  /**
   * Reverses the effects of [hideStory].
   * @param model a model that was previously passed to [hideStory]
   * @see pendingItems
   * @see hideStory
   */
  fun undoHideStory(model: StoryModel) {
    val pos = pendingItems[model] ?: throw IllegalArgumentException("This model was never hidden")
    Log.v(TAG, "undoHideStory: pos=$pos, model: $model")
    data.add(pos, StoryCardData(model))
    pendingItems.remove(model)
    notifyItemRangeInserted(pos, 1)
    storyCount.it++
  }

  /**
   * Filter, group, sort [stories] according to the [arrangement], and put the results in [data].
   */
  fun arrangeStories(stories: List<StoryModel>, arrangement: Arrangement) {
    // Ignore currently pending stories, the user might have rearranged before the db was updated
    val storiesNotPending = stories.filter { (storyId) ->
      pendingItems.keys.find { it.storyId == storyId } == null
    }
    clearData()
    val toData = storiesNotPending.filter { true }.toMutableList() // FIXME filter
    groupStories(toData, arrangement.groupStrategy).toSortedMap().forEach {
      val ordered = orderStories(it.value, arrangement.orderStrategy, arrangement.orderDirection)
      addData(listOf(GroupTitle(it.key), *ordered.map { StoryCardData(it) }.toTypedArray()))
    }
    filteredCount.it = storiesNotPending.size - toData.size
    storyCount.it = storiesNotPending.size
  }
}

/**
 * Adapts [StoryListItem]s to views for a [RecyclerView].
 */
class StoryAdapter(private val viewModel: StoryListViewModel) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  val vmObserver = createObserverForAdapter(this)

  init {
    setHasStableIds(true)
    viewModel.registerObserver(vmObserver)
  }

  class StoryViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)
  class TitleViewHolder(val view: StoryGroupTitle) : RecyclerView.ViewHolder(view)
  class ProgressBarHolder(val view: ProgressBar) : RecyclerView.ViewHolder(view)

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    val item = viewModel.itemAt(position)
    when (item) {
      is StoryCardData -> {
        val view = (holder as StoryViewHolder).view
        view.onExtendedStateChange = { viewModel.updateStoryState(position, it) }
        view.isExtended = item.isExtended
        view.loadFromModel(item.model)
        view.setChildrenListeners(item.model, holder, viewModel)
      }
      is GroupTitle -> (holder as TitleViewHolder).view.text = item.title
      is LoadingItem -> (holder as ProgressBarHolder).view.id = item.id
    }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    val view: View = when (viewType) {
      0 -> inflater.inflate(R.layout.story_component, parent, false)
      1 -> StoryGroupTitle(parent.context)
      2 -> inflater.inflate(R.layout.loading_circle_indeterminate, parent, false)
      else -> throw IllegalStateException("getItemViewType out of sync with onCreateViewHolder")
    }
    view.alpha = 0F
    view.isDrawingCacheEnabled = true
    val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0.3F, 1F)
    fadeIn.startDelay = 50
    fadeIn.start()
    return when (viewType) {
      0 -> StoryViewHolder(view as StoryCardView)
      1 -> TitleViewHolder(view as StoryGroupTitle)
      2 -> ProgressBarHolder(view as ProgressBar)
      else -> throw IllegalStateException("getItemViewType out of sync with onCreateViewHolder")
    }
  }

  override fun getItemCount(): Int = viewModel.itemCount()
  override fun getItemId(position: Int): Long {
    val item = viewModel.itemAt(position)
    return when (item) {
      is StoryCardData -> item.model.storyId + (1 shl 15)
      is GroupTitle -> item.title.hashCode().toLong() + (2 shl 15)
      is LoadingItem -> item.id.toLong() + (3 shl 15)
    }
  }

  override fun getItemViewType(position: Int): Int = when (viewModel.itemAt(position)) {
    is StoryCardData -> 0
    is GroupTitle -> 1
    is LoadingItem -> 2
  }
}
