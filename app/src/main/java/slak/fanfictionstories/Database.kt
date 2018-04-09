package slak.fanfictionstories

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.Deferred
import org.jetbrains.anko.db.*
import slak.fanfictionstories.utility.Optional
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.async2
import slak.fanfictionstories.utility.opt

class DatabaseHelper(ctx: Context) : ManagedSQLiteOpenHelper(ctx, "FFStories", null, 5) {
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
        storyId INTEGER UNIQUE NOT NULL,
        addedTime INTEGER NOT NULL,
        lastReadTime INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS colorMarkers (
        storyId INTEGER PRIMARY KEY NOT NULL UNIQUE,
        markerColor INTEGER NOT NULL
      );
    """)
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    if (oldVersion == 1 && newVersion == 2) {
      db.execSQL("ALTER TABLE stories ADD COLUMN addedTime INTEGER NOT NULL DEFAULT 0;")
      db.execSQL("ALTER TABLE stories ADD COLUMN lastReadTime INTEGER NOT NULL DEFAULT 0;")
    } else if (oldVersion == 2 && newVersion == 3) {
      db.execSQL("ALTER TABLE stories ADD COLUMN markerColor INTEGER NOT NULL DEFAULT 0;")
    } else if (oldVersion == 3 && newVersion == 4) {
      db.execSQL("""
        CREATE TABLE IF NOT EXISTS transientMarkers (
          storyId INTEGER PRIMARY KEY NOT NULL UNIQUE,
          markerColor INTEGER NOT NULL
        );
      """.trimIndent())
    } else if (oldVersion == 4 && newVersion == 5) {
      db.execSQL("ALTER TABLE transientMarkers RENAME TO colorMarkers;")
      db.execSQL("""
        INSERT INTO colorMarkers (storyId, markerColor)
          SELECT storyId, markerColor FROM stories;
      """.trimIndent())
      // Everything below is just remove the markerColor column from the stories table
      db.execSQL("""
        CREATE TEMPORARY TABLE storiesBackup(_id, title, author, authorId, summary, category, canon,
        language, genres, characters, rating, reviews, favorites, follows, status, chapterTitles,
        chapterCount, currentChapter, isComplete, scrollProgress, scrollAbsolute, wordCount,
        publishTime, updateTime, storyId, addedTime, lastReadTime);
      """.trimIndent())
      db.execSQL("""
        INSERT INTO storiesBackup SELECT _id, title, author, authorId, summary, category, canon,
        language, genres, characters, rating, reviews, favorites, follows, status, chapterTitles,
        chapterCount, currentChapter, isComplete, scrollProgress, scrollAbsolute, wordCount,
        publishTime, updateTime, storyId, addedTime, lastReadTime FROM stories;
      """.trimIndent())
      db.execSQL("DROP TABLE stories;")
      db.execSQL("""
        CREATE TABLE stories (
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
        storyId INTEGER UNIQUE NOT NULL,
        addedTime INTEGER NOT NULL,
        lastReadTime INTEGER NOT NULL
      );
      """.trimIndent())
      db.execSQL("""
        INSERT INTO stories SELECT _id, title, author, authorId, summary, category, canon,
        language, genres, characters, rating, reviews, favorites, follows, status, chapterTitles,
        chapterCount, currentChapter, isComplete, scrollProgress, scrollAbsolute, wordCount,
        publishTime, updateTime, storyId, addedTime, lastReadTime FROM storiesBackup;
      """.trimIndent())
      db.execSQL("DROP TABLE storiesBackup;")
    }
  }

  /** Gets the color marker for a story, if it exists. */
  fun getMarker(storyId: StoryId): Deferred<Optional<Int>> = useAsync {
    select("colorMarkers", "markerColor")
        .whereSimple("storyId = ?", storyId.toString())
        .parseOpt(IntParser).opt()
  }

  /** Sets the color marker for the given story. */
  fun setMarker(storyId: StoryId, color: Int) = useAsync {
    val long = replaceOrThrow("colorMarkers", "storyId" to storyId, "markerColor" to color)
    StoryEventNotifier.notifyStoryChanged(listOf(storyId), StoryEventKind.Changed)
    return@useAsync long
  }

  /**
   * Get a list of [slak.fanfictionstories.StoryStatus.LOCAL] [slak.fanfictionstories.StoryModel]s.
   */
  fun getLocalStories(): Deferred<List<StoryModel>> = useAsync {
    select("stories").whereSimple("status = ?", "local").parseList(StoryModel.dbParser)
  }

  /** Get ALL stored stories. */
  fun getStories(): Deferred<List<StoryModel>> = useAsync {
    select(tableName = "stories").parseList(StoryModel.dbParser)
  }

  /** Get a [StoryModel] by its id, if it exists. */
  fun storyById(storyId: StoryId): Deferred<Optional<StoryModel>> = useAsync {
    select("stories").whereSimple("storyId = ?", storyId.toString())
        .parseOpt(StoryModel.dbParser).opt()
  }

  /** Update some particular columns for a particular storyId. */
  fun updateInStory(storyId: StoryId, vararg pairs: Pair<String, Any>): Deferred<Int> = useAsync {
    val int = update("stories", *pairs).whereSimple("storyId = ?", storyId.toString()).exec()
    StoryEventNotifier.notifyStoryChanged(listOf(storyId), StoryEventKind.Changed)
    return@useAsync int
  }

  /** Upsert a story in the DB. */
  fun upsertStory(model: StoryModel) = useAsync {
    if (!model.isPersistable()) {
      Log.e("upsertStory", model.toString())
      throw IllegalArgumentException("This model is unfit for the database")
    }
    transaction {
      try {
        insertOrThrow("stories", *model.toPairs())
        StoryEventNotifier.notifyStoryChanged(listOf(model), StoryEventKind.New)
      } catch (err: SQLiteConstraintException) {
        // Rethrow if it isn't a unique violation
        if (err.errCode() != SQLiteResultCode.CONSTRAINT_UNIQUE) throw err
        // Unique broken => story already exists, so update it
        update("stories", *model.toPairs())
            .whereSimple("storyId = ?", model.storyId.toString()).exec()
        StoryEventNotifier.notifyStoryChanged(listOf(model), StoryEventKind.Changed)
      }
    }
  }
}

/**
 * All possible SQLite errors/results.
 * Taken from [the SQLite site](https://sqlite.org/rescode.html).
 */
enum class SQLiteResultCode(val code: Int) {
  // Primary result codes
  ABORT(4),
  AUTH(23),
  BUSY(5),
  CANTOPEN(14),
  CONSTRAINT(19),
  CORRUPT(11),
  DONE(101),
  EMPTY(16),
  ERROR(1),
  FORMAT(24),
  FULL(13),
  INTERNAL(2),
  INTERRUPT(9),
  IOERR(10),
  LOCKED(6),
  MISMATCH(20),
  MISUSE(21),
  NOLFS(22),
  NOMEM(7),
  NOTADB(26),
  NOTFOUND(12),
  NOTICE(27),
  OK(0),
  PERM(3),
  PROTOCOL(15),
  RANGE(25),
  READONLY(8),
  ROW(100),
  SCHEMA(17),
  TOOBIG(18),
  WARNING(28),
  // Extended result codes
  ABORT_ROLLBACK(516),
  BUSY_RECOVERY(261),
  BUSY_SNAPSHOT(517),
  CANTOPEN_CONVPATH(1038),
  CANTOPEN_FULLPATH(782),
  CANTOPEN_ISDIR(526),
  CANTOPEN_NOTEMPDIR(270),
  CONSTRAINT_CHECK(275),
  CONSTRAINT_COMMITHOOK(531),
  CONSTRAINT_FOREIGNKEY(787),
  CONSTRAINT_FUNCTION(1043),
  CONSTRAINT_NOTNULL(1299),
  CONSTRAINT_PRIMARYKEY(1555),
  CONSTRAINT_ROWID(2579),
  CONSTRAINT_TRIGGER(1811),
  CONSTRAINT_UNIQUE(2067),
  CONSTRAINT_VTAB(2323),
  CORRUPT_VTAB(267),
  ERROR_MISSING_COLLSEQ(257),
  ERROR_RETRY(513),
  IOERR_ACCESS(3338),
  IOERR_BLOCKED(2826),
  IOERR_CHECKRESERVEDLOCK(3594),
  IOERR_CLOSE(4106),
  IOERR_CONVPATH(6666),
  IOERR_DELETE(2570),
  IOERR_DELETE_NOENT(5898),
  IOERR_DIR_CLOSE(4362),
  IOERR_DIR_FSYNC(1290),
  IOERR_FSTAT(1802),
  IOERR_FSYNC(1034),
  IOERR_GETTEMPPATH(6410),
  IOERR_LOCK(3850),
  IOERR_MMAP(6154),
  IOERR_NOMEM(3082),
  IOERR_RDLOCK(2314),
  IOERR_READ(266),
  IOERR_SEEK(5642),
  IOERR_SHMLOCK(5130),
  IOERR_SHMMAP(5386),
  IOERR_SHMOPEN(4618),
  IOERR_SHMSIZE(4874),
  IOERR_SHORT_READ(522),
  IOERR_TRUNCATE(1546),
  IOERR_UNLOCK(2058),
  IOERR_WRITE(778),
  LOCKED_SHAREDCACHE(262),
  NOTICE_RECOVER_ROLLBACK(539),
  NOTICE_RECOVER_WAL(283),
  OK_LOAD_PERMANENTLY(256),
  READONLY_CANTINIT(1288),
  READONLY_CANTLOCK(520),
  READONLY_DBMOVED(1032),
  READONLY_DIRECTORY(1544),
  READONLY_RECOVERY(264),
  READONLY_ROLLBACK(776),
  WARNING_AUTOINDEX(284),
  // Code not found
  OTHER(-1);

  companion object {
    fun fromCode(code: Int) = values().find { it.code == code } ?: OTHER
  }
}

/** Extracts the [SQLiteResultCode] from the string error. */
fun SQLiteConstraintException.errCode(): SQLiteResultCode {
  val msg = message ?: return SQLiteResultCode.OTHER
  val target = "(code "
  val startIdx = msg.indexOf(target) + target.length
  var idx = startIdx
  while (msg[++idx].isDigit());
  return SQLiteResultCode.fromCode(msg.slice(startIdx until idx).toInt())
}

/** Access property for [Context]. */
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(applicationContext)

/** Access property for [Static]. */
val Static.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(currentCtx.applicationContext)

/** Like [ManagedSQLiteOpenHelper.use], but using [async2] and [CommonPool]. */
fun <T> ManagedSQLiteOpenHelper.useAsync(f: SQLiteDatabase.() -> T): Deferred<T> =
    async2(CommonPool) { this@useAsync.use(f) }
