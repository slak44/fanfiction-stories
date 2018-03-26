package slak.fanfictionstories.utility

import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import java.io.*
import java.util.concurrent.ConcurrentHashMap

typealias ExpirationEpoch = Long
typealias CacheMap<T> = ConcurrentHashMap<String, Pair<T, ExpirationEpoch>>

/**
 * A simple cache implementation that stores items of type [T], in memory and on disk (in a file).
 *
 * Relies on a [ConcurrentHashMap] with [String] keys, and on [Serializable].
 */
class Cache<T : Serializable>(val name: String, val cacheTimeMs: ExpirationEpoch) {
  private var cache = CacheMap<T>()
  private val cacheMapFile = File(Static.cacheDir, "$name.cached-map")
  private val TAG = "Cache[$name]"

  /**
   * Read the serialized cache on disk and load it into memory, if possible. Does not try too hard.
   */
  fun deserialize() = async2(CommonPool) {
    if (!cacheMapFile.exists()) {
      return@async2
    }
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

  /**
   * Check the expiration date of each item, and remove it if expired.
   */
  fun purge() = launch(CommonPool) {
    cache.entries.removeIf { isExpired(it.value.second) }
  }

  /**
   * Write the current serialized state of the cache to disk.
   */
  fun serialize() = launch(CommonPool) {
    val objOut = ObjectOutputStream(FileOutputStream(cacheMapFile))
    objOut.writeObject(cache)
    objOut.close()
  }

  /**
   * Update or set the value for a key in this cache. Resets expiration date for that key.
   */
  fun update(key: String, data: T) {
    cache[key] = data to System.currentTimeMillis() + cacheTimeMs
    serialize()
  }

  /**
   * Attempt to get the cached value for a given key. If it doesn't exist or is expired, [Empty] is
   * returned instead.
   */
  fun hit(key: String): Optional2<T> {
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
    return cache[key]!!.first.opt2()
  }

  /**
   * Clears both the in-memory map, and deletes the file on disk holding the cache.
   */
  fun clear() {
    cache = CacheMap()
    val deleted = cacheMapFile.delete()
    if (!deleted) {
      Log.wtf(TAG, "Failed to clear disk cache; memory and disk caches are now inconsistent")
    }
  }

  companion object {
    /**
     * Checks if a given timestamp is past the current timestamp.
     */
    fun isExpired(date: ExpirationEpoch): Boolean = System.currentTimeMillis() > date
  }
}
