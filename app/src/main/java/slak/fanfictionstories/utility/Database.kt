package slak.fanfictionstories.utility

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.experimental.*
import org.jetbrains.anko.db.*
import slak.fanfictionstories.StoryModel

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
        authorid INTEGER NOT NULL,
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
        chapters INTEGER CHECK(chapters > 0) NOT NULL,
        currentChapter INTEGER CHECK(currentChapter >= 0 AND currentChapter <= chapters) NOT NULL,
        isCompleted INTEGER CHECK(isCompleted IN (0, 1)) NOT NULL,
        scrollProgress REAL CHECK(scrollProgress >= 0 AND scrollProgress <= 100) NOT NULL,
        scrollAbsolute REAL NOT NULL,
        wordCount INTEGER CHECK(wordCount > 0) NOT NULL,
        publishDate INTEGER NOT NULL,
        updateDate INTEGER NOT NULL,
        storyId INTEGER UNIQUE NOT NULL
      );
    """)
  }

  override fun onOpen(db: SQLiteDatabase?) {
    super.onOpen(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Here you can upgrade tables, as usual
  }

  fun getStories() : Deferred<List<StoryModel>> = async2(CommonPool) {
    readableDatabase.select(tableName = "stories")
        .whereSimple("status = ?", "local").parseList(StoryModel.dbParser)
  }
}

// Access property for Context
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(this.applicationContext)
