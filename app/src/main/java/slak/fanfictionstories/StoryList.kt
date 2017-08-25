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
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.android.UI
import java.util.*
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
      var swipeStory: ItemTouchHelper? = null
      swipeStory = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(recycler: RecyclerView?,
                                      viewHolder: RecyclerView.ViewHolder?): Int {
          return makeMovementFlags(0, ItemTouchHelper.RIGHT)
        }

        override fun onMove(recycler: RecyclerView?, viewHolder: RecyclerView.ViewHolder?,
                            target: RecyclerView.ViewHolder?): Boolean {
          return false
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
          val intent = Intent(recyclerView.context, StoryReaderActivity::class.java)
          val cardView = viewHolder.itemView as StoryCardView
          intent.putExtra(StoryReaderActivity.INTENT_STORY_MODEL, cardView.currentModel!!)
          openStoryReader(intent, cardView.storyId.get())
          // After the reader was opened, reset the translation by reattaching
          // We do this because we might go back from the reader to this activity and
          // it has to look properly
          launch(UI) {
            delay(2500)
            swipeStory!!.attachToRecyclerView(null)
            swipeStory!!.attachToRecyclerView(recyclerView)
          }
        }
      })
      swipeStory.attachToRecyclerView(recyclerView)
      return swipeStory
    }
  }

  var storyId: Optional<Long> = Optional.empty()

  override fun onCreateDrawableState(extraSpace: Int): IntArray {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      // FIXME do we want this elevation like this?
      cardElevation = if (cardElevation == 7F) 20F else 7F
      // This gets animated automatically
      if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
      else storyDetails.visibility = View.GONE
    }
    addBtn.setOnClickListener {
      addBtn.isEnabled = false
      addBtn.text = context.resources.getString(R.string.adding___)
      if (!storyId.isPresent)
        throw IllegalStateException("StoryCardView clicked, but data not filled by model")
      val n = Notifications(this@StoryCardView.context, Notifications.Kind.DOWNLOADING)
      launch(CommonPool) {
        val model = getFullStory(this@StoryCardView.context, storyId.get(), n).await()
        launch(UI) {
          if (model.isPresent) {
            addBtn.visibility = View.INVISIBLE

          } else {
            addBtn.isEnabled = true
            addBtn.text = resources.getString(R.string.add)
          }
        }
        n.cancel()
      }
    }
    return super.onCreateDrawableState(extraSpace)
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

    storyId = Optional.of(model.storyIdRaw)
    if (model.status == StoryStatus.LOCAL) addBtn.visibility = View.INVISIBLE
  }
}

class StoryGroupTitle : TextView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  companion object {
    private val borderHeight =
        MainActivity.res.getDimensionPixelSize(R.dimen.story_list_title_divider_height)
    private val bottomMargin = MainActivity.res.getDimensionPixelSize(R.dimen.story_list_margin)
  }

  init {
    setPadding(0, 0, 0,
        resources.getDimensionPixelSize(R.dimen.story_list_title_underline_margin))
  }

  private val border: Paint by lazy {
    val border = Paint()
    border.style = Paint.Style.STROKE
    border.color =
        MainActivity.res.getColor(android.R.color.secondary_text_dark, this.context.theme)
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

  fun toUIString(): String = MainActivity.res.getString(when (this) {
    CANON -> R.string.group_canon
    AUTHOR -> R.string.group_author
    CATEGORY -> R.string.group_category
    STATUS -> R.string.group_status
    RATING -> R.string.group_rating
    LANGUAGE -> R.string.group_language
    COMPLETION -> R.string.group_completion
    NONE -> R.string.group_none
  })
}

/**
 * @returns a map that maps titles to grouped stories, according to the given GroupStrategy
 */
fun groupStories(stories: MutableList<StoryModel>,
                         strategy: GroupStrategy): Map<String, MutableList<StoryModel>> {
  if (strategy == GroupStrategy.NONE)
    return mapOf(MainActivity.res.getString(R.string.all_stories) to stories)
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
        if (it.src[srcKey] as Long == 1L) MainActivity.res.getString(R.string.completed)
        else MainActivity.res.getString(R.string.in_progress)
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

  fun toUIString(): String = MainActivity.res.getString(when (this) {
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
}

fun orderStories(stories: MutableList<StoryModel>,
                 s: OrderStrategy, d: OrderDirection): MutableList<StoryModel> {
  stories.sortWith(if (d == OrderDirection.DESC) s.comparator.reversed() else s.comparator)
  return stories
}

class StoryAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  class StoryViewHolder(val view: StoryCardView) : RecyclerView.ViewHolder(view)
  class TitleViewHolder(val view: StoryGroupTitle) : RecyclerView.ViewHolder(view)

  // Story or group title
  private val data: MutableList<Either<StoryModel, String>> = mutableListOf()

  var stories: MutableList<StoryModel> = mutableListOf()

  var groupStrategy: GroupStrategy = GroupStrategy.NONE
  var orderStrategy: OrderStrategy = OrderStrategy.TITLE_ALPHABETIC
  var orderDirection: OrderDirection = OrderDirection.DESC

  fun clear() {
    data.clear()
    notifyDataSetChanged()
    notifyItemRangeChanged(0, itemCount)
  }

  fun addData(story: Either<StoryModel, String>) {
    val dataSize = data.size
    data.add(story)
    story.fold( { stories.add(it) }, { false })
    launch(UI) {
      notifyDataSetChanged()
      notifyItemRangeChanged(dataSize, data.size - dataSize)
    }
  }

  fun addData(storyList: List<Either<StoryModel, String>>) {
    data.addAll(storyList)
    storyList.forEach {
      it.fold( { stories.add(it) }, { false })
    }
    launch(UI) {
      notifyDataSetChanged()
      notifyItemRangeChanged(0, itemCount)
    }
  }

  fun initDataFromDb(): Deferred<Unit> = async(CommonPool) {
    data.clear()
    stories = this@StoryAdapter.context.database.getStories().await().toMutableList()
    val toData = stories.filter { true } // FIXME filter
    groupStories(toData.toMutableList(), groupStrategy).forEach {
      val ordered = orderStories(it.value, orderStrategy, orderDirection)
      data.add(Right(it.key))
      data.addAll(ordered.map { Left(it) })
    }

    launch(UI) {
      notifyDataSetChanged()
      notifyItemRangeChanged(0, itemCount)
    }
    return@async
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    data[position].fold(
        { model -> (holder as StoryViewHolder).view.loadFromModel(model) },
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
    val fadeIn = ObjectAnimator.ofFloat(view, "alpha", 0.3F, 1F)
    fadeIn.startDelay = Math.min(addedStory * 50, 250)
    fadeIn.start()
    return if (viewType == 1) TitleViewHolder(view as StoryGroupTitle)
    else StoryViewHolder(view as StoryCardView)
  }

  override fun getItemCount(): Int = data.size
  override fun getItemId(position: Int): Long = data[position].fold(
      { model -> model._id.get() },
      { title -> title.hashCode().toLong() }
  )
  override fun getItemViewType(position: Int): Int = data[position].fold({ return 0 }, { return 1 })
}
