package slak.fanfictionstories

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View

import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.db.dropTable
import java.io.File

class MainActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)
    setSupportActionBar(toolbar)
    // FIXME: set drawable and text string for resume button if a story is available to be resumed
    storyListButton.setOnClickListener {
      val intent = Intent(this, StoryListActivity::class.java)
      startActivity(intent)
    }
    if (BuildConfig.DEBUG) {
      debugButtons.visibility = View.VISIBLE
      regenTableBtn.setOnClickListener {
        database.use { dropTable("stories", true) }
        Log.w("MainActivity", "DROPPED STORIES TABLE")
        database.onCreate(database.writableDatabase)
        Log.w("MainActivity", "REINITED STORIES TABLE")
      }
      addStoryBtn.setOnClickListener {
        getFullStory(this, 12555864L)
      }
      wipeDiskDataBtn.setOnClickListener {
        File(getStorageDir(this@MainActivity).get(), "storiesData").deleteRecursively()
      }
    }
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    // Inflate the menu; this adds items to the action bar if it is present.
    menuInflater.inflate(R.menu.menu_main, menu)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    return when (item.itemId) {
      R.id.action_settings -> true
      else -> super.onOptionsItemSelected(item)
    }
  }
}
