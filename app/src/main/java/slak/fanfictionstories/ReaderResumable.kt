package slak.fanfictionstories

import android.os.Bundle
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.database
import slak.fanfictionstories.utility.ifPresent2
import slak.fanfictionstories.utility.opt
import java.util.*

interface ReaderResumable {
  fun updateOnResume(adapter: StoryAdapter): Job
  fun enteredReader(storyId: Long)
  fun saveInstanceState(outState: Bundle)
  fun restoreInstanceState(savedInstanceState: Bundle)
}

class ReaderResumer : ReaderResumable {
  companion object {
    private const val LAST_STORY_ID_RESTORE = "last_story_id"
  }

  private var lastStoryId: Optional<Long> = Optional.empty()
  override fun updateOnResume(adapter: StoryAdapter): Job = launch(UI) {
    lastStoryId.ifPresent2 {
      Static.database.storyById(it).await().ifPresent { adapter.updateStoryModel(it) }
    }
  }

  override fun enteredReader(storyId: Long) {
    lastStoryId = storyId.opt()
  }

  override fun saveInstanceState(outState: Bundle) {
    outState.putLong(LAST_STORY_ID_RESTORE, lastStoryId.orElse(-1L))
  }

  override fun restoreInstanceState(savedInstanceState: Bundle) {
    val value = savedInstanceState.getLong(LAST_STORY_ID_RESTORE)
    lastStoryId = if (value == -1L) Optional.empty() else value.opt()
  }
}
