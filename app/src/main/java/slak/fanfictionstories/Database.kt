package slak.fanfictionstories

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
    db!!.dropTable("stories", true) // FIXME test code
    this.onCreate(db)
    for (i in 1..10) db.insertOrThrow(tableName = "stories", values = *arrayOf( // FIXME test code
        "title" to "Title",
        "author" to "Slak44",
        "authorid" to 1298274,
        "summary" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit. In tincidunt, magna ut molestie faucibus, felis neque ullamcorper risus, non gravida velit lacus sed nisi. Integer commodo metus at purus finibus suscipit. In interdum metus non arcu fringilla, at ultricies massa pharetra. Phasellus nec tincidunt eros, condimentum scelerisque lorem.",
        "canon" to "Nice world",
        "category" to "test",
        "language" to "English",
        "rating" to "Fiction T",
        "status" to "remote",
        "genres" to "Blabla/BlehBleh/BloBlo",
        "characters" to "Test A., Test B.",
        "chapters" to 15,
        "currentChapter" to 1,
        "scrollProgress" to 50,
        "wordCount" to 1000,
        "publishDate" to 1502444959,
        "updateDate" to 1502444960,
        "storyid" to 19429924,
        "isCompleted" to 1,
        "reviews" to 12,
        "favorites" to 29,
        "follows" to 56
    ))
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    // Here you can upgrade tables, as usual
  }

  fun getStories(ctx: Context) : Deferred<List<StoryModel>> = async(CommonPool) { readableDatabase.
    select(tableName = "stories").exec { parseList(object : MapRowParser<StoryModel> {
      override fun parseRow(columns: Map<String, Any?>) = StoryModel(columns, ctx, fromDb = true)
    }) }
  }
}

// Access property for Context
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(this.applicationContext)
