package slak.fanfictionstories

import android.util.Log
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import slak.fanfictionstories.data.Cache
import slak.fanfictionstories.utility.Static
import java.io.File
import java.io.Serializable

private data class TestData(val data: String, val number: Int) : Serializable

class CacheTests {
  private lateinit var testCache: Cache<TestData>

  @Before
  fun createCache() {
    testCache = Cache("test-data-cache", 100)
  }

  @After
  fun deleteCacheFile() {
    testCache.clear()
  }

  @Test
  fun `Populate cache with data`() {
    runBlocking {
      testCache.update("asd", TestData("testing", 123))
      testCache.update("dfgh", TestData("dfjg", 456))
      testCache.update("fghj", TestData("sdfhgdh", 4767))
      testCache.update("glk", TestData("jfhgdkghjf", 780034))
      testCache.update("jhsfdg", TestData("ghdkg", 24567))
    }
  }

  @Test
  fun `Retrieve cached data`() {
    runBlocking {
      val testData = TestData("testing", 123)
      testCache.update("asd", testData)
      val data = testCache.hit("asd").orNull()

      requireNotNull(data)
      assertEquals(testData, data)
    }
  }

  @Test
  fun `Cache returns latest data`() {
    runBlocking {
      testCache.update("asd", TestData("testing", 123))

      delay(10)

      val testData = TestData("newer data", 312)
      testCache.update("asd", testData)

      val data = testCache.hit("asd").orNull()

      requireNotNull(data)
      assertEquals(testData, data)
    }
  }

  @Test
  fun `Cache returns nothing after expiration time`() {
    runBlocking {
      testCache.update("asd", TestData("testing", 123))

      delay(testCache.cacheTimeMs)

      val data = testCache.hit("asd").orNull()

      assertNull(data)
    }
  }

  @Test
  fun `Cache deserialization works`() {
    runBlocking {
      val testData = TestData("testing", 123)
      testCache.update("asd", testData)

      createCache()

      val data = testCache.hit("asd").orNull()
      assertNull(data)

      testCache.deserialize().join()

      val dataDeserialized = testCache.hit("asd").orNull()
      assertEquals(testData, dataDeserialized)
    }
  }

  @Test
  fun `Cache is thread-safe for reading`() {
    runBlocking {
      val testData = TestData("testing", 123)
      testCache.update("asd", testData)

      for (i in 0..10) {
        launch {
          val data = testCache.hit("asd").orNull()
          assertEquals(testData, data)
        }
      }
    }
  }

  companion object {
    @JvmStatic
    @BeforeClass
    fun mockCacheDir() {
      mockkStatic(Log::class)
      every { Log.d(any(), any()) } returns 0

      mockkObject(Static)
      every { Static.cacheDir } returns File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    @JvmStatic
    @AfterClass
    fun unmockCacheDir() {
      unmockkObject(Static)
    }
  }
}