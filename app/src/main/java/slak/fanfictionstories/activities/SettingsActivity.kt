package slak.fanfictionstories.activities

import android.content.SharedPreferences
import android.os.Bundle
import com.takisoft.fix.support.v7.preference.PreferenceFragmentCompat
import kotlinx.android.synthetic.main.activity_settings.*
import slak.fanfictionstories.R
import slak.fanfictionstories.StoriesApplication
import slak.fanfictionstories.utility.ActivityWithStatic
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.str

class SettingsActivity : ActivityWithStatic(), SharedPreferences.OnSharedPreferenceChangeListener {
  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
    if (key != str(R.string.key_option_update_time)) return
    Static.jobScheduler.cancelAll()
    StoriesApplication.scheduleInitUpdate()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportFragmentManager.beginTransaction().replace(R.id.prefFragment, MainSettingsFragment()).commit()
    Static.defaultPrefs.registerOnSharedPreferenceChangeListener(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    Static.defaultPrefs.unregisterOnSharedPreferenceChangeListener(this)
  }
}

class MainSettingsFragment : PreferenceFragmentCompat() {
  override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.settings_main)
  }
}
