package slak.fanfictionstories.utility

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import slak.fanfictionstories.fetchers.CategoryFetcher
import java.io.File

/**
 * Provides static access to various resources in [ActivityWithStatic] activities. Using this class
 * when outside such an activity may or may not crash the app, but it will almost certainly break
 * instant run.
 */
object Static {
  var res: Resources? = null
    private set
  var cm: ConnectivityManager? = null
    private set
  var cacheDir: File? = null
    private set
  var sharedPref: SharedPreferences? = null
    private set

  fun init(context: Context) {
    if (res == null) res = context.resources
    if (cm == null)
      cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (cacheDir == null) cacheDir = context.cacheDir
    if (sharedPref == null)
      sharedPref = context.getSharedPreferences(Prefs.PREFS_FILE, Context.MODE_PRIVATE)
  }
}

/**
 * Helper base class for use with [Static].
 */
abstract class ActivityWithStatic : AppCompatActivity() {
  private var cacheInited: Boolean = false
  override fun onCreate(savedInstanceState: Bundle?) {
    Static.init(this)
    // Re-create cache
    if (!cacheInited) {
      CategoryFetcher.Cache.deserialize()
      cacheInited = true
    }
    super.onCreate(savedInstanceState)
  }
}
