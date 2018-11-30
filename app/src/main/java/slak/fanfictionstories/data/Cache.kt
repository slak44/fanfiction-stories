package slak.fanfictionstories.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import slak.fanfictionstories.utility.Empty
import slak.fanfictionstories.utility.Optional
import slak.fanfictionstories.utility.Static
import slak.fanfictionstories.utility.opt
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

/** @see Cache */
typealias ExpirationEpoch = Long
/** @see Cache */
typealias CacheMap<T> = ConcurrentHashMap<String, Pair<T, ExpirationEpoch>>

/**
 * A simple cache implementation that stores items of type [T], in memory and on disk (in a file).
 *
 * Relies on a [ConcurrentHashMap] with [String] keys, and on [Serializable].
 * @param name this will show up in debug messages, and is useful to tell multiple instances apart
 * @param cacheTimeMs how long to keep things in the map, in milliseconds
 * @see [java.util.concurrent.TimeUnit.MILLISECONDS]
 * @see [CacheMap]
 */
class Cache<T : Serializable>(val name: String, val cacheTimeMs: Long) {
  private var cache = CacheMap<T>()
  private val cacheMapFile = File(Static.cacheDir, "$name.cached-map")
  @Suppress("PrivatePropertyName")
  private val TAG = "Cache[$name]"

  /**
   * Read the serialized cache on disk and load it into memory, if possible. Does not try too hard.
   * @see serialize
   */
  fun deserialize() = GlobalScope.launch(Dispatchers.Default) {
    if (!cacheMapFile.exists()) return@launch
    val objIn = ObjectInputStream(FileInputStream(cacheMapFile))
    try {
      // I serialize it however I like, I deserialize it however I like, so stfu
      @Suppress("Unchecked_Cast")
      val array = objIn.readObject() as CacheMap<T>
      cache = array
      purge()
    } catch (ex: Throwable) {
      // Ignore errors with the cache; don't crash the app because of it
      cacheMapFile.delete()
      Log.d(TAG, "Cache load error, deleting cache")
    } finally {
      objIn.close()
    }
  }

  /** Check the expiration date of each item, and remove it if expired. */
  fun purge() = GlobalScope.launch(Dispatchers.Default) {
    cache.entries.removeIf { isExpired(it.value.second) }
    serialize()
  }

  /** Write the current serialized state of the cache to disk. */
  fun serialize() = GlobalScope.launch(Dispatchers.IO) {
    val objOut = ObjectOutputStream(FileOutputStream(cacheMapFile))
    objOut.writeObject(cache)
    objOut.close()
  }

  /** Update or set the value for a key in this cache. Resets expiration date for that key. */
  fun update(key: String, data: T) {
    cache[key] = data to System.currentTimeMillis() + cacheTimeMs
    serialize()
  }

  /**
   * Attempt to get the cached value for a given key. If it doesn't exist or is expired, [Empty] is
   * returned instead.
   */
  fun hit(key: String): Optional<T> {
    if (cache[key] == null) {
      Log.d(TAG, "Cache missed: $key")
      return Empty()
    }
    if (isExpired(cache[key]!!.second)) {
      // Cache expired; remove and return nothing
      Log.d(TAG, "Cache expired: $key")
      cache.remove(key)
      serialize()
      return Empty()
    }
    Log.d(TAG, "Cache hit: $key")
    return cache[key]!!.first.opt()
  }

  /** Clears both the in-memory map, and deletes the file on disk holding the cache. */
  fun clear() {
    cache = CacheMap()
    val deleted = cacheMapFile.delete()
    if (!deleted) {
      Log.wtf(TAG, "Failed to clear disk cache; memory and disk caches are now inconsistent")
    }
  }

  companion object {
    /** Checks if a given timestamp is past the current timestamp. */
    fun isExpired(date: ExpirationEpoch): Boolean = System.currentTimeMillis() > date
  }
}
