package slak.fanfictionstories

import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_story_reader.*

class StoryReaderActivity : AppCompatActivity() {

  companion object {
    const val INTENT_STORY_MODEL = "bundle"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_story_reader)
    setSupportActionBar(toolbar)
    fab.setOnClickListener { view ->
      Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
          .setAction("Action", null).show()
    }
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val model = intent.getParcelableExtra<StoryModel>(INTENT_STORY_MODEL)

    title = model.title
  }
}
