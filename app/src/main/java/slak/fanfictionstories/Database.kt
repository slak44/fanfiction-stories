package slak.fanfictionstories

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import org.jetbrains.anko.db.*

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
        status TEXT CHECK(status IN ('seen', 'remote', 'local')) NOT NULL,
        chapters INTEGER CHECK(chapters > 0) NOT NULL,
        currentChapter INTEGER CHECK(currentChapter >= 0 AND currentChapter <= chapters) NOT NULL,
        isCompleted INTEGER CHECK(isCompleted IN (0, 1)) NOT NULL,
        scrollProgress INTEGER NOT NULL,
        wordCount INTEGER CHECK(chapters > 0) NOT NULL,
        publishDate INTEGER NOT NULL,
        updateDate INTEGER NOT NULL,
        storyid INTEGER NOT NULL
      );
    """)
  }

  override fun onOpen(db: SQLiteDatabase?) {
    super.onOpen(db)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Here you can upgrade tables, as usual
  }

  fun getStories(ctx: Context) : Deferred<List<StoryModel>> = async(CommonPool) { readableDatabase.
    select(tableName = "stories").exec { parseList(object : MapRowParser<StoryModel> {
      override fun parseRow(columns: Map<String, Any?>) = StoryModel(columns, ctx, fromDb = true)
    }) }
  }

  fun insertStory(model: StoryModel) = async(CommonPool) {
    // For some retarded-ass reason the map can't be spread by *, and neither can an array of pairs
    // FIXME ¯\_(ツ)_/¯
    writableDatabase.insertOrThrow("stories",
        "title" to model.src["title"],
        "author" to model.src["author"],
        "authorid" to model.src["authorid"],
        "summary" to model.src["summary"],
        "category" to model.src["category"],
        "canon" to model.src["canon"],
        "language" to model.src["language"],
        "genres" to model.src["genres"],
        "characters" to model.src["characters"],
        "rating" to model.src["rating"],
        "reviews" to model.src["reviews"],
        "favorites" to model.src["favorites"],
        "follows" to model.src["follows"],
        "status" to model.src["status"],
        "chapters" to model.src["chapters"],
        "currentChapter" to model.src["currentChapter"],
        "isCompleted" to model.src["isCompleted"],
        "scrollProgress" to model.src["scrollProgress"],
        "wordCount" to model.src["wordCount"],
        "publishDate" to model.src["publishDate"],
        "updateDate" to model.src["updateDate"],
        "storyid" to model.src["storyid"]
    )
  }
}

// Access property for Context
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(this.applicationContext)
