package slak.fanfictionstories.activities

import android.content.SharedPreferences
import android.os.Bundle
import androidx.fragment.app.commit
import androidx.preference.EditTextPreference
import com.takisoft.preferencex.PreferenceFragmentCompat
import slak.fanfictionstories.R
import slak.fanfictionstories.databinding.ActivitySettingsBinding
import slak.fanfictionstories.scheduleUpdate
import slak.fanfictionstories.utility.ActivityWithStatic
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.str

/** The preferences activity, for user-controlled settings. */
class SettingsActivity : ActivityWithStatic(), SharedPreferences.OnSharedPreferenceChangeListener {
  private lateinit var binding: ActivitySettingsBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivitySettingsBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportFragmentManager.commit {
      replace(R.id.prefFragment, MainSettingsFragment())
    }
    Static.defaultPrefs.registerOnSharedPreferenceChangeListener(this)
  }

  override fun onDestroy() {
    super.onDestroy()
    Static.defaultPrefs.unregisterOnSharedPreferenceChangeListener(this)
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
    if (key != str(R.string.key_option_update_time)) return
    Static.jobScheduler.cancelAll()
    scheduleUpdate()
  }
}

internal class MainSettingsFragment : PreferenceFragmentCompat() {
  override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.settings_main)
    val provider = EditTextPreference.SimpleSummaryProvider.getInstance()
    findPreference<EditTextPreference>(str(R.string.key_option_font))!!.summaryProvider = provider
    findPreference<EditTextPreference>(str(R.string.key_option_size))!!.summaryProvider = provider
  }
}
