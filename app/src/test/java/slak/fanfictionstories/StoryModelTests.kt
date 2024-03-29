package slak.fanfictionstories

import android.os.Parcel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import slak.fanfictionstories.data.fetchers.Genre
import slak.fanfictionstories.data.fetchers.ParserUtils
import java.lang.ClassCastException
import java.time.Instant

private val chapterTitles = listOf("Test name", "test name 2")
private const val character1 = "Test K."
private const val character2 = "OC"
private const val character3 = "Random Name"
private const val character4 = "Name A./Name B."
private const val character5 = "Char 5"
private const val character6 = "Char 6"

private const val genre1 = "Adventure"
private const val genre2 = "Drama"

private val dbRow = listOf(
    "title" to "My test story",
    "author" to "John Doe",
    "authorId" to 9999999L,
    "summary" to "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut" +
        "labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud",
    "category" to "Games",
    "canon" to "Test Canon",
    "language" to "English",
    "genres" to "$genre1/$genre2",
    "characters" to "$character1, [$character2, $character3] $character4, [$character5, $character6]",
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
  fun `Convert StoryModel to database row and back successfully`() {
    val storyModel = StoryModel.fromPairs(dbRow)
    assert(storyModel.isPersistable())
    assertEquals(dbRow.sortedBy { it.first }, storyModel.toPairs().sortedBy { it.first })
  }

  @Test
  fun `Do not create model with missing data`() {
    val copy = dbRow.toMap().toMutableMap()
    copy.remove("chapterTitles")

    assertThrows(NoSuchElementException::class.java) {
      StoryModel.fromPairs(copy.toList())
    }
  }

  @Test
  fun `Do not create model with malformed data`() {
    val copy = dbRow.toMap().toMutableMap()
    copy["authorId"] = -123L

    assertThrows(IllegalArgumentException::class.java) {
      StoryModel.fromPairs(copy.toList())
    }

    copy["authorId"] = "lol not a long"

    assertThrows(ClassCastException::class.java) {
      StoryModel.fromPairs(copy.toList())
    }
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

    assertEquals(chapterTitles, storyModel.chapterTitles())
  }

  @Test
  fun `StoryModel should parse character names`() {
    val storyModel = StoryModel.fromPairs(dbRow)

    val characters = listOf(character1, character2, character3, character4, character5, character6)
    assertEquals(characters, storyModel.characterList())
  }

  @Test
  fun `StoryModel should parse genre name`() {
    val storyModel = StoryModel.fromPairs(dbRow)

    val genres = listOf(genre1, genre2).map { Genre.fromString(it) }
    assertEquals(genres, storyModel.genreList())
  }
}
