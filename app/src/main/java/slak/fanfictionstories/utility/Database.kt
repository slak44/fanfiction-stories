package slak.fanfictionstories.utility

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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

  fun upsertStory(model: StoryModel) {
    if (!model.isPersistable()) {
      Log.e("upsertStory", model.toString())
      throw IllegalArgumentException("This model is unfit for the database")
    }
    writableDatabase.transaction {
      try {
        insertOrThrow("stories", *model.toPairs())
      } catch (err: SQLiteConstraintException) {
        // Rethrow if it isn't a unique violation
        if (err.errCode() != SQLiteResultCode.CONSTRAINT_UNIQUE) throw err
        // Unique broken => story already exists, so update it
        update("stories", *model.toPairs())
            .whereSimple("storyId = ?", model.storyId.toString()).exec()
      }
    }
  }

  fun replaceStory(model: StoryModel) {
    if (!model.isPersistable()) {
      Log.e("replaceStory", model.toString())
      throw IllegalArgumentException("This model is unfit for the database")
    }
    writableDatabase.replaceOrThrow("stories", *model.toPairs())
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

fun SQLiteConstraintException.errCode(): SQLiteResultCode {
  val msg = message ?: return SQLiteResultCode.OTHER
  val target = "(code "
  val startIdx = msg.indexOf(target) + target.length
  var idx = startIdx
  while (msg[++idx].isDigit());
  return SQLiteResultCode.fromCode(msg.slice(startIdx until idx).toInt())
}

/**
 * Access property for [Context].
 */
val Context.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(applicationContext)

/**
 * Access property for [Static].
 */
val Static.database: DatabaseHelper
  get() = DatabaseHelper.getInstance(currentCtx.applicationContext)
