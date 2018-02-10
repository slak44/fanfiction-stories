package slak.fanfictionstories.utility

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.experimental.*
import org.jetbrains.anko.db.*
import slak.fanfictionstories.StoryModel
import java.util.*

class DatabaseHelper(ctx: Context) : ManagedSQLiteOpenHelper(ctx, "FFStories", null, 1) {
  companion object {
    private var instance: DatabaseHelper? = null

    @Synchronized
    fun getInstance(ctx: Context): DatabaseHelper {
      if (instance == null) {
        instance = DatabaseHelper(ctx.applicationContext)
      }
      return instance!!
    }
  }

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL("""
      CREATE TABLE IF NOT EXISTS stories (
        _id INTEGER PRIMARY KEY UNIQUE,
        title TEXT NOT NULL,
        author TEXT NOT NULL,
        authorId INTEGER NOT NULL,
        summary TEXT NOT NULL,
        category TEXT NOT NULL,
        canon TEXT NOT NULL,
        language TEXT NOT NULL,
        genres TEXT NOT NULL,
        characters TEXT NOT NULL,
        rating TEXT NOT NULL,
        reviews INTEGER CHECK(reviews >= 0) NOT NULL,
        favorites INTEGER CHECK(favorites >= 0) NOT NULL,
        follows INTEGER CHECK(follows >= 0) NOT NULL,
        status TEXT CHECK(status IN ('remote', 'local')) NOT NULL,
        chapterTitles TEXT NOT NULL,
        chapterCount INTEGER CHECK(chapterCount > 0) NOT NULL,
        currentChapter INTEGER CHECK(currentChapter >= 0 AND currentChapter <= chapterCount) NOT NULL,
        isComplete INTEGER CHECK(isComplete IN (0, 1)) NOT NULL,
        scrollProgress REAL CHECK(scrollProgress >= 0 AND scrollProgress <= 100) NOT NULL,
        scrollAbsolute REAL NOT NULL,
        wordCount INTEGER CHECK(wordCount > 0) NOT NULL,
        publishTime INTEGER NOT NULL,
        updateTime INTEGER NOT NULL,
        storyId INTEGER UNIQUE NOT NULL
      );
    """)
  }

  override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
    // Empty for now, there is only one version
  }

  fun getLocalStories() : Deferred<List<StoryModel>> = async2(CommonPool) {
    readableDatabase.select(tableName = "stories")
        .whereSimple("status = ?", "local").parseList(StoryModel.dbParser)
  }

  fun getStories() : Deferred<List<StoryModel>> = async2(CommonPool) {
    readableDatabase.select(tableName = "stories").parseList(StoryModel.dbParser)
  }

  fun storyById(storyId: Long): Optional<StoryModel> {
    return readableDatabase.select("stories")
        .whereSimple("storyId = ?", storyId.toString())
        .parseOpt(StoryModel.dbParser).opt()
  }

  fun updateInStory(storyId: Long, vararg pairs: Pair<String, Any>) {
    writableDatabase.update("stories", *pairs)
        .whereSimple("storyId = ?", storyId.toString()).exec()
  }
}

/**
 * Access property for [Context].
 */
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(this.applicationContext)
