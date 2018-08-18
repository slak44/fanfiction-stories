package slak.fanfictionstories

import android.database.Observable
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.data.database
import slak.fanfictionstories.utility.Static
import java.util.concurrent.LinkedBlockingQueue

/** Describes what happened to the stories in an [StoryChangeEvent]. */
sealed class StoryEventKind {
  /** Stories were added. */
  object New : StoryEventKind()

  /** Stories were modified. */
  object Changed : StoryEventKind()

  /** Stories were removed. */
  object Removed : StoryEventKind()
}

/**
 * Holds what models were affected by an event, and what happened to them.
 * @see StoryEventKind
 * @see StoryEventNotifier.notifyStoryChanged
 * @see IStoryEventObserver
 */
data class StoryChangeEvent(val kind: StoryEventKind, val models: List<StoryModel>)

/**
 * Handles [StoryChangeEvent]s, can be observed by [IStoryEventObserver]s to receive events.
 * @see StoryChangeEvent
 * @see IStoryEventObserver
 */
object StoryEventNotifier : Observable<IStoryEventObserver>() {
  private val evtQueue = LinkedBlockingQueue<StoryChangeEvent>()

  init {
    launch(CommonPool) {
      while (true) {
        val evt = evtQueue.take()
        mObservers.forEach { it.onStoriesChanged(evt) }
      }
    }
  }

  /** @returns whether or not the provided object is registered in this object */
  fun isRegistered(obj: IStoryEventObserver) = obj in mObservers

  /**
   * Notify all observers about a change in stories.
   * @param models the new story data
   */
  fun notifyStoryChanged(models: List<StoryModel>, kind: StoryEventKind) {
    evtQueue.add(StoryChangeEvent(kind, models))
  }

  /**
   * Notify all observers about a change in stories.
   * @param ids fetch the [StoryModel]s referred to by the ids
   * @see notifyStoryChanged
   */
  fun notifyStoryChanged(ids: List<StoryId>, kind: StoryEventKind) = launch(CommonPool) {
    notifyStoryChanged(Static.database.storiesById(ids).await(), kind)
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
   * To be overridden by implementations to receive events. Is called with each received event.
   * @param t the new event that has to be processed by the implementation
   */
  fun onStoriesChanged(t: StoryChangeEvent)
}
