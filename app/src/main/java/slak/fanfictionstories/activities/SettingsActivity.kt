package slak.fanfictionstories.activities

import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_settings.*
import slak.fanfictionstories.R
import android.preference.PreferenceFragment

class SettingsActivity : AppCompatActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    setSupportActionBar(toolbar)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    fragmentManager.beginTransaction().replace(R.id.prefFragment, MainSettingsFragment()).commit()
  }
}

class MainSettingsFragment : PreferenceFragment() {
  companion object {
    private const val TAG = "MainSettingsFragment"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    addPreferencesFromResource(R.xml.settings_main)
  }
}
