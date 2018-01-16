package slak.fanfictionstories.utility

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import slak.fanfictionstories.fetchers.CategoryFetcher
import java.io.File

/**
 * Provides static access to various resources in [ActivityWithStatic] activities. Using this class
 * when outside such an activity may or may not crash the app, but it will almost certainly break
 * instant run.
 */
object Static {
  var resProp: Resources? = null
    private set
  var cmProp: ConnectivityManager? = null
    private set
  var cacheDirProp: File? = null
    private set
  var sharedPref: SharedPreferences? = null
    private set

  val prefs: SharedPreferences
    get() = sharedPref!!
  val cacheDir: File
    get() = cacheDirProp!!
  val res: Resources
    get() = resProp!!
  val cm: ConnectivityManager
    get() = cmProp!!

  fun init(context: Context) {
    if (resProp == null) resProp = context.applicationContext.resources
    if (cmProp == null) cmProp = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (cacheDirProp == null) cacheDirProp = context.applicationContext.cacheDir
    if (sharedPref == null) sharedPref = context.applicationContext
        .getSharedPreferences(Prefs.PREFS_FILE, Context.MODE_PRIVATE)
  }
}

/**
 * Helper base class for use with [Static]. Also runs various static initializers.
 */
abstract class ActivityWithStatic : AppCompatActivity() {
  private var hasCache: Boolean = false
  private var hasExHandler: Boolean = false
  override fun onCreate(savedInstanceState: Bundle?) {
    Static.init(this)
    if (!hasCache) {
      CategoryFetcher.Cache.deserialize()
      hasCache = true
    }
    if (!hasExHandler) {
      Thread.setDefaultUncaughtExceptionHandler {
        thread, throwable -> Log.e("UNCAUGHT DEFAULT", thread.toString(), throwable)
      }
      hasExHandler = true
    }
    super.onCreate(savedInstanceState)
  }
}
