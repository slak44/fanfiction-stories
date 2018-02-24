package slak.fanfictionstories.utility

import android.util.Log
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.launch
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

typealias ExpirationEpoch = Long
typealias CacheMap<T> = ConcurrentHashMap<String, Pair<T, ExpirationEpoch>>

class Cache<T : Serializable>(val name: String, val cacheTimeMs: ExpirationEpoch) {
  private var cache = CacheMap<T>()
  private val cacheMapFile = File(Static.cacheDir, "$name.cached-map")
  private val TAG = "Cache[$name]"

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

  fun purge() = launch(CommonPool) {
    cache.entries.removeIf { isExpired(it.value.second) }
  }

  fun serialize() = launch(CommonPool) {
    val objOut = ObjectOutputStream(FileOutputStream(cacheMapFile))
    objOut.writeObject(cache)
    objOut.close()
  }

  fun update(key: String, data: T) {
    cache[key] = data to System.currentTimeMillis() + cacheTimeMs
    serialize()
  }

  fun isExpired(date: ExpirationEpoch): Boolean = System.currentTimeMillis() > date

  fun hit(key: String): Optional<T> {
    if (cache[key] == null) {
      Log.d(TAG, "Cache missed: $key")
      return Optional.empty()
    }
    if (isExpired(cache[key]!!.second)) {
      // Cache expired; remove and return nothing
      Log.d(TAG, "Cache expired: $key")
      cache.remove(key)
      serialize()
      return Optional.empty()
    }
    Log.d(TAG, "Cache hit: $key")
    return cache[key]!!.first.opt()
  }

  fun clear() {
    cache = CacheMap()
    serialize()
  }
}
