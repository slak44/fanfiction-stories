package slak.fanfictionstories

import android.os.Parcel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Test
import slak.fanfictionstories.data.fetchers.ParserUtils
import java.time.Instant

private val chapterTitles = listOf("Test name", "test name 2")
private val character1 = "Test K."
private val character2 = "OC"
private val character3 = "Random Name"

private val dbRow = listOf(
    "title" to "My test story",
    "author" to "John Doe",
    "authorId" to 9999999L,
    "summary" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut" +
        "labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud",
    "category" to "Games",
    "canon" to "Test Canon",
    "language" to "English",
    "genres" to "Adventure/Drama",
    "characters" to "$character1, [$character2, $character3]",
    "rating" to "T",
    "reviews" to 1234L,
    "favorites" to 50L,
    "follows" to 975L,
    "status" to "local",
    "chapterTitles" to chapterTitles.joinToString(ParserUtils.CHAPTER_TITLE_SEPARATOR),
    "chapterCount" to 2L,
    "currentChapter" to 1L,
    "isComplete" to 0L,
    "scrollProgress" to 100.0,
    "scrollAbsolute" to 999999.0,
    "wordCount" to 12345L,
    "publishTime" to 1444959620L,
    "updateTime" to 0L,
    "storyId" to 999845938L,
    "addedTime" to Instant.now().toEpochMilli(),
    "lastReadTime" to 0L,
    "imageUrl" to ""
)

class StoryModelTests {
  @Test
  fun `Create StoryModel from database row successfully`() {
    val storyModel = StoryModel.fromPairs(dbRow)
    assert(storyModel.isPersistable())
  }

  @Test
  fun `StoryModel should be Parcelable`() {
    val storyModel = StoryModel.fromPairs(dbRow)

    val parcel = mockk<Parcel>()
    every { parcel.writeLong(any()) } returns Unit
    every { parcel.writeInt(any()) } returns Unit
    every { parcel.writeString(any()) } returns Unit
    every { parcel.writeDouble(any()) } returns Unit

    storyModel.writeToParcel(parcel, 0)
  }

  @Test
  fun `StoryModel should parse chapter titles`() {
    val storyModel = StoryModel.fromPairs(dbRow)

    Assert.assertEquals(chapterTitles, storyModel.chapterTitles())
  }

  @Test
  fun `StoryModel should parse character names`() {
    val storyModel = StoryModel.fromPairs(dbRow)

    Assert.assertEquals(listOf(character1, character2, character3), storyModel.characterList())
  }
}
