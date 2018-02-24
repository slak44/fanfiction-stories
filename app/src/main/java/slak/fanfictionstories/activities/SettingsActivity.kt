package slak.fanfictionstories.activities

import android.os.Bundle
import com.takisoft.fix.support.v7.preference.PreferenceFragmentCompat
import kotlinx.android.synthetic.main.activity_settings.*
import slak.fanfictionstories.R
import slak.fanfictionstories.utility.ActivityWithStatic

class SettingsActivity : ActivityWithStatic() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    supportFragmentManager.beginTransaction().replace(R.id.prefFragment, MainSettingsFragment()).commit()
  }
}

class MainSettingsFragment : PreferenceFragmentCompat() {
  override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
    addPreferencesFromResource(R.xml.settings_main)
  }
}
