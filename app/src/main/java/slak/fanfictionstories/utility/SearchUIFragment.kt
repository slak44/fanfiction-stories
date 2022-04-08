package slak.fanfictionstories.utility

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.annotation.UiThread
import androidx.fragment.app.Fragment
import slak.fanfictionstories.R
import slak.fanfictionstories.databinding.FragmentSearchUiBinding

/** An activity that implements this interface can be searched using [SearchUIFragment]. */
interface ISearchableActivity {
  /** Get the index of the current highlight. */
  fun getCurrentHighlight(): Int

  /** Get the total match count. */
  fun getMatchCount(): Int

  /** Scroll the highlight at [idx] into view. */
  fun navigateToHighlight(idx: Int)

  /** Search for [query] and update the stored matches. */
  fun setSearchQuery(query: String)

  /** Change the currently highlighted match. */
  fun updateCurrentHighlight(idx: Int)

  /** Highlight the found matches, and the currently selected one. */
  fun highlightMatches()

  /** Remove all highlights. */
  fun clearHighlights()

  /** Do stuff when the search UI hides itself. */
  fun onHide()
}

fun ISearchableActivity.hasMatches() = getMatchCount() > 0
fun ISearchableActivity.hasNoMatches() = !hasMatches()

data class Area(val startPosition: Int, val length: Int) {
  val endPosition: Int get() = startPosition + length
}

/** This fragment provides a search UI. Its activity MUST implement [ISearchableActivity]. */
class SearchUIFragment : Fragment() {
  companion object {
    private const val TAG = "SearchHighlighter"
    private const val RESTORE_LAYOUT_VISIBILITY = "search_is_visible"
  }

  private lateinit var sActivity: ISearchableActivity
  private lateinit var binding: FragmentSearchUiBinding

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    val a = activity as? ISearchableActivity
        ?: throw IllegalStateException("Activity must implement SearchableActivity")
    sActivity = a

    binding = FragmentSearchUiBinding.inflate(inflater, container, false)
    Log.v(TAG, "Search UI inflated")
    binding.searchNextMatchBtn.setOnClickListener {
      if (sActivity.hasNoMatches()) return@setOnClickListener
      val newCurrent = if (sActivity.getCurrentHighlight() + 1 != sActivity.getMatchCount()) {
        sActivity.getCurrentHighlight() + 1
      } else {
        0
      }
      sActivity.navigateToHighlight(newCurrent)
      updateMatchText(newCurrent + 1)
      sActivity.updateCurrentHighlight(newCurrent)
    }
    binding.searchPrevMatchBtn.setOnClickListener {
      if (sActivity.hasNoMatches()) return@setOnClickListener
      val newCurrent = if (sActivity.getCurrentHighlight() > 0) {
        sActivity.getCurrentHighlight() - 1
      } else {
        sActivity.getMatchCount() - 1
      }
      sActivity.navigateToHighlight(newCurrent)
      updateMatchText(newCurrent + 1)
      sActivity.updateCurrentHighlight(newCurrent)
    }
    binding.searchCloseBtn.setOnClickListener {
      binding.searchLayout.visibility = View.GONE
      hideSoftKeyboard(binding.searchLayout.windowToken)
      sActivity.clearHighlights()
      sActivity.onHide()
    }
    return binding.root
  }

  override fun onViewStateRestored(savedInstanceState: Bundle?) {
    super.onViewStateRestored(savedInstanceState)
    if (savedInstanceState != null) {
      binding.searchLayout.visibility = savedInstanceState.getInt(RESTORE_LAYOUT_VISIBILITY, View.GONE)
    }
    binding.editorSearch.setOnEditorActionListener { _, actionId, _ ->
      if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
      searchAndHighlight()
      return@setOnEditorActionListener true
    }
    binding.editorSearch.addTextChangedListener(object : TextWatcher {
      override fun afterTextChanged(s: Editable) {}
      override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
      override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        // Searching for stuff less than ~3 chars long gets so many matches that we lag hard
        // If the user wants to search for something like that, he can press search manually
        if (s.length < 3) {
          // If characters were removed, we're going from 3+ letters to less than that
          // And we'd like to not have existing highlights loiter around, so clear them
          if (before > count) {
            binding.editorSearchMatches.text = ""
            sActivity.clearHighlights()
          }
          return
        }
        searchAndHighlight()
      }
    })
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt(RESTORE_LAYOUT_VISIBILITY, binding.searchLayout.visibility)
  }

  @UiThread
  private fun searchAndHighlight() {
    val toFind = binding.editorSearch.text.toString()
    sActivity.setSearchQuery(toFind)
    if (toFind.isEmpty()) {
      // Empty query means there are no matches, but that's obvious, so just leave this text empty
      binding.editorSearchMatches.text = ""
      return
    }
    sActivity.highlightMatches()
    if (sActivity.hasMatches()) {
      updateMatchText(1)
      sActivity.navigateToHighlight(0)
    } else {
      binding.editorSearchMatches.text = str(R.string.no_matches_found)
    }
  }

  @UiThread
  private fun updateMatchText(newCurrent: Int) {
    binding.editorSearchMatches.text =
        str(R.string.found_x_matches_current_y, sActivity.getMatchCount(), newCurrent)
  }

  /** Make the searching UI visible, and bring it into focus. */
  @UiThread
  fun show() {
    binding.searchLayout.visibility = View.VISIBLE
    binding.editorSearch.requestFocus()
    sActivity.highlightMatches()
  }

  /** Call after reinitializing this fragment. Return true if visible. */
  @UiThread
  fun restoreState(): Boolean {
    if (binding.searchLayout.visibility != View.VISIBLE) return false
    sActivity.highlightMatches()
    if (sActivity.hasMatches()) updateMatchText(sActivity.getCurrentHighlight() + 1)
    return true
  }
}
