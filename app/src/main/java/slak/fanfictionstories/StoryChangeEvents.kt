package slak.fanfictionstories

import android.database.Observable
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/** Describes what happened to some models. */
sealed class StoriesChangeEvent {
  abstract val models: List<StoryModel>

  /** Stories were added. */
  data class New(override val models: List<StoryModel>) : StoriesChangeEvent()

  /** Stories were modified. */
  data class Changed(override val models: List<StoryModel>) : StoriesChangeEvent()

  /** Stories were removed. */
  data class Removed(override val models: List<StoryModel>) : StoriesChangeEvent()
}

/** Handles [StoriesChangeEvent]s, can be observed by [IStoryEventObserver]s to receive events. */
object StoryEventNotifier : Observable<IStoryEventObserver>() {
  /** @returns whether or not the provided object is registered in this object */
  fun isRegistered(obj: IStoryEventObserver) = obj in mObservers

  /**
   * Notify all observers about a change in stories.
   * @param event the data to send to the observers
   */
  @AnyThread
  fun notify(event: StoriesChangeEvent) = GlobalScope.launch(Main) {
    mObservers.forEach { it.onStoriesChanged(event) }
  }
}

/** Implementors will receive events about changes in the list of stories. */
interface IStoryEventObserver {
  /** Start receiving events. Is a no-op if already registered. */
  fun register() {
    if (StoryEventNotifier.isRegistered(this)) return
    StoryEventNotifier.registerObserver(this)
  }

  /** Stop receiving events. */
  fun unregister() {
    StoryEventNotifier.unregisterObserver(this)
  }

  /**
   * To be overridden by implementations to receive events. Is called with each received event. Runs on the UI thread.
   * @param t the new event that has to be processed by the implementation
   */
  @UiThread
  fun onStoriesChanged(t: StoriesChangeEvent)
}
