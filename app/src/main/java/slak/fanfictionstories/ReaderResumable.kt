package slak.fanfictionstories

import android.os.Bundle
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.utility.*

interface ReaderResumable {
  fun updateOnResume(viewModel: StoryListViewModel): Job
  fun enteredReader(storyId: Long)
  fun saveInstanceState(outState: Bundle)
  fun restoreInstanceState(savedInstanceState: Bundle)
}

// FIXME merge this functionality into StoryListViewModel
class ReaderResumer : ReaderResumable {
  companion object {
    private const val LAST_STORY_ID_RESTORE = "last_story_id"
  }

  private var lastStoryId: Optional<Long> = Empty()
  override fun updateOnResume(viewModel: StoryListViewModel): Job = launch(UI) {
    lastStoryId.ifPresent {
      Static.database.storyById(it).await().ifPresent { viewModel.updateStoryModel(it) }
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
    lastStoryId = if (value == -1L) Empty() else value.opt()
  }
}
