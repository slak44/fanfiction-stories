package slak.fanfictionstories.utility

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import slak.fanfictionstories.data.Prefs
import java.io.File

// We don't leak anything here; the 2 static fields stored here are kept always up to date
// That means we can only store a reference to things that are currently in use anyway
// And the moment these change, so should our fields, and the previous references are lost
@SuppressLint("StaticFieldLeak")
/**
 * Provides static access to various resources in [ActivityWithStatic] activities. Using this class
 * when outside such an activity may or may not crash the app, but it will almost certainly break
 * instant run.
 */
object Static {
  var currentActivity: ActivityWithStatic? = null

  private var resProp: Resources? = null
  private var cmProp: ConnectivityManager? = null
  private var cacheDirProp: File? = null
  private var sharedPref: SharedPreferences? = null
  private var defaultPref: SharedPreferences? = null
  private var thisCtx: Context? = null
  private var notificationManager: NotificationManager? = null
  private var jobSchedulerProp: JobScheduler? = null

  val prefs: SharedPreferences get() = sharedPref!!
  val defaultPrefs: SharedPreferences get() = defaultPref!!
  val cacheDir: File get() = cacheDirProp!!
  val res: Resources get() = resProp!!
  val cm: ConnectivityManager get() = cmProp!!
  val currentCtx: Context get() = thisCtx!!
  val notifManager: NotificationManager get() = notificationManager!!
  val jobScheduler: JobScheduler get() = jobSchedulerProp!!

  /**
   * Returns whether or not this class' properties are usable. Static replacement for
   * [android.view.View.isInEditMode], because that is not usable in some places where [Static] is.
   */
  val isInitialized: Boolean
    get() = thisCtx != null

  /** Called by [ActivityWithStatic]. Implementation detail. */
  fun init(context: Context, contextActivity: ActivityWithStatic? = null) {
    currentActivity = contextActivity
    thisCtx = context
    if (resProp == null) resProp = context.applicationContext.resources
    if (cmProp == null) cmProp = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (cacheDirProp == null) cacheDirProp = context.applicationContext.cacheDir
    if (sharedPref == null) sharedPref = context.applicationContext
        .getSharedPreferences(Prefs.PREFS_FILE, Context.MODE_PRIVATE)
    if (defaultPref == null)
      defaultPref = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
    if (notificationManager == null) notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (jobSchedulerProp == null) jobSchedulerProp = context.applicationContext
        .getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
  }
}

/** Helper base class for use with [Static]. */
abstract class ActivityWithStatic : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    Static.init(this, this)
    super.onCreate(savedInstanceState)
  }
}
