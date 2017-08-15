package slak.fanfictionstories

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.CardView
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.android.synthetic.main.activity_story_list.*
import kotlinx.android.synthetic.main.content_story_list.*
import kotlinx.android.synthetic.main.story_component.view.*
import org.jetbrains.anko.db.*

class StoryCardView : CardView {
  constructor(context: Context) : super(context)
  constructor(context: Context, set: AttributeSet) : super(context, set)
  constructor(context: Context, set: AttributeSet, defStyle: Int) : super(context, set, defStyle)

  override fun onCreateDrawableState(extraSpace: Int): IntArray {
    // Disable touching on the progress seek bar
    storyProgress.setOnTouchListener { _, _ -> true }
    storyMainContent.setOnClickListener {
      // FIXME do we want this elevation like this?
      if (cardElevation == 7F) cardElevation = 20F
      else cardElevation = 7F
      // This gets animated automatically
      if (storyDetails.visibility == View.GONE) storyDetails.visibility = View.VISIBLE
      else storyDetails.visibility = View.GONE
    }
    return super.onCreateDrawableState(extraSpace)
  }

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

class StoryAdapter(val activity: StoryListActivity) : BaseAdapter() {
  var data = activity.getStoriesMeta()

  override fun getCount(): Int {
    return data.size
  }

  override fun getItem(position: Int): Any {
    return data[position]
  }

  override fun getItemId(position: Int): Long {
    return data[position]._id.get()
  }

  override fun getView(idx: Int, recycleView: View?, parent: ViewGroup?): View {
    // FIXME: use the recycle crap
    val view = activity.layoutInflater.inflate(R.layout.story_component, parent, false) as StoryCardView
    view.loadFromModel(data[idx])
    return view
  }
}

class StoryListActivity : AppCompatActivity() {
  fun getStoriesMeta() : List<StoryModel> {
    return database.use {
      select(tableName = "stories").exec {
        parseList(object : MapRowParser<StoryModel> {
          override fun parseRow(columns: Map<String, Any?>) =
              StoryModel(columns, this@StoryListActivity, fromDb = true)
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
