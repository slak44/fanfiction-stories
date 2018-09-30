package slak.fanfictionstories.activities

import android.arch.lifecycle.ViewModel
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.support.annotation.UiThread
import android.support.design.widget.TabLayout
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v7.app.AlertDialog
import android.support.v7.widget.LinearLayoutManager
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.*
import android.widget.ProgressBar
import kotlinx.android.synthetic.main.activity_author.*
import kotlinx.android.synthetic.main.fragment_author_bio.view.*
import kotlinx.android.synthetic.main.fragment_author_stories.view.*
import kotlinx.android.synthetic.main.loading_activity_indeterminate.*
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.Author
import slak.fanfictionstories.data.fetchers.getAuthor
import slak.fanfictionstories.utility.*
import java.util.*

/** Stores the data required for the author page. */
class AuthorViewModel(val author: Author) : ViewModel()

/**
 * An author's detail page. Has his bio, his stories, his favorite stories, his favourite authors, and other user
 * related actions.
 */
class AuthorActivity : CoroutineScopeActivity(), IHasLoadingBar {
  override val loading: ProgressBar
    get() = activityProgressBar

  companion object {
    const val INTENT_AUTHOR_ID = "author_id_intent"
    const val INTENT_AUTHOR_NAME = "author_name_intent"
  }

  private lateinit var viewModel: AuthorViewModel

  /**
   * The [android.support.v4.view.PagerAdapter] that will provide fragments for each of the sections. We use a
   * [FragmentPagerAdapter] derivative, which will keep every loaded fragment in memory. If this becomes too memory
   * intensive, it may be best to switch to a [android.support.v4.app.FragmentStatePagerAdapter].
   */
  private var sectionsPagerAdapter: SectionsPagerAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_author)
    setSupportActionBar(toolbar)
    setLoadingView(toolbar, 1)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    val (authorName, authorId) = if (intent.action == ACTION_VIEW) {
      val pathSegments = intent?.data?.pathSegments
          ?: throw IllegalArgumentException("Missing intent data")
      pathSegments[2] to pathSegments[1].toLong()
    } else {
      val name = intent.getStringExtra(AuthorActivity.INTENT_AUTHOR_NAME)
      val id = intent.getLongExtra(AuthorActivity.INTENT_AUTHOR_ID, -1L)
      if (id == -1L) throw IllegalArgumentException("Intent has no author id")
      name to id
    }

    title = authorName
    container.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(tabs))
    tabs.addOnTabSelectedListener(TabLayout.ViewPagerOnTabSelectedListener(container))
    showLoading()

    launch(UI) {
      viewModel = obtainViewModel(getAuthor(authorId))
      title = viewModel.author.name
      invalidateOptionsMenu()
      sectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)
      container.adapter = sectionsPagerAdapter
      hideLoading()
    }
  }

  /** Ensure the [tabs] are gone when the loading bar is there. */
  override fun showLoading() {
    super.showLoading()
    tabs.visibility = View.GONE
  }

  /** Ensure the [tabs] come back when the loading bar goes away. */
  override fun hideLoading() {
    super.hideLoading()
    tabs.visibility = View.VISIBLE
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_author, menu)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.favoriteAuthor).iconTint(R.color.white, theme)
    // This can be called before the viewModel is initialized
    if (sectionsPagerAdapter != null) {
      if (viewModel.author.favoriteAuthors.isEmpty()) menu.findItem(R.id.favoritedAuthors).isVisible = false
      menu.findItem(R.id.favoriteAuthor).isEnabled = true
      menu.findItem(R.id.followAuthor).isEnabled = true
      menu.findItem(R.id.privateMessage).isEnabled = true
      menu.findItem(R.id.favoritedAuthors).isEnabled = true
    }
    return super.onPrepareOptionsMenu(menu)
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.followAuthor -> {
        // FIXME: follow author
      }
      R.id.favoriteAuthor -> {
        // FIXME: favorite author
      }
      R.id.privateMessage -> {
        // FIXME: send private message
      }
      R.id.favoritedAuthors -> {
        AlertDialog.Builder(this)
            .setTitle(R.string.favorite_authors)
            .setItems(viewModel.author.favoriteAuthors.map { it.second }.toTypedArray()) { d, which ->
              d.dismiss()
              startActivity<AuthorActivity>(
                  INTENT_AUTHOR_NAME to viewModel.author.favoriteAuthors[which].second,
                  INTENT_AUTHOR_ID to viewModel.author.favoriteAuthors[which].first)
            }.show()
      }
      android.R.id.home -> onBackPressed()
      else -> return super.onOptionsItemSelected(item)
    }
    return true
  }

  // FIXME this can just be an object
  /** A [FragmentPagerAdapter] that returns a fragment corresponding to the tabs. */
  inner class SectionsPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {
    override fun getItem(position: Int): Fragment = when (position) {
      0 -> {
        val joined = Prefs.simpleDateFormatter
            .format(Date(viewModel.author.joinedDateSeconds * 1000))
        val updated = Prefs.simpleDateFormatter
            .format(Date(viewModel.author.updatedDateSeconds * 1000))
        val formattedUpdateDate = if (viewModel.author.updatedDateSeconds != 0L)
          str(R.string.bio_profile_update, updated) else ""
        val html = str(R.string.bio_html_prelude,
            str(R.string.bio_joined, joined),
            formattedUpdateDate,
            str(R.string.bio_author_id, viewModel.author.id),
            viewModel.author.bioHtml)
        HtmlFragment.newInstance(html)
      }
      1 -> StoryListFragment.newInstance(ArrayList(viewModel.author.userStories))
      2 -> StoryListFragment.newInstance(ArrayList(viewModel.author.favoriteStories))
      else -> throw IllegalStateException("getCount returned too many tabs")
    }

    override fun getCount(): Int = 3 // tabs
  }

  /** Fragment that renders HTML in a [android.widget.TextView]. */
  internal class HtmlFragment : Fragment() {
    companion object {
      private const val ARG_HTML_TEXT = "html_text"

      /** Returns a new instance of this fragment for the given HTML text. */
      fun newInstance(html: String): HtmlFragment {
        val fragment = HtmlFragment()
        val args = Bundle()
        args.putString(ARG_HTML_TEXT, html)
        fragment.arguments = args
        return fragment
      }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
      val rootView = inflater.inflate(R.layout.fragment_author_bio, container, false)
      rootView.post {
        rootView.html.text = Html.fromHtml(arguments!!.getString(ARG_HTML_TEXT),
            Html.FROM_HTML_MODE_LEGACY, null, HrSpan.tagHandlerFactory(rootView.width))
        rootView.html.movementMethod = LinkMovementMethod.getInstance()
      }
      return rootView
    }
  }

  /**
   * Fragment that lists stories using a [android.support.v7.widget.RecyclerView] and
   * [StoryAdapter].
   */
  internal class StoryListFragment : Fragment() {
    companion object {
      private const val ARG_STORIES = "stories"

      /** Returns a new instance of this fragment for the given stories. */
      fun newInstance(stories: ArrayList<StoryModel>): StoryListFragment {
        val fragment = StoryListFragment()
        val args = Bundle()
        args.putParcelableArrayList(ARG_STORIES, stories)
        fragment.arguments = args
        return fragment
      }
    }

    private lateinit var viewModel: StoryListViewModel

    @UiThread
    private fun initLayout(rootView: View, stories: List<StoryModel>) {
      rootView.stories.layoutManager = LinearLayoutManager(context)
      rootView.stories.createStorySwipeHelper()
      rootView.stories.adapter = StoryAdapter(viewModel)
      if (stories.isEmpty()) {
        rootView.noStories.visibility = View.VISIBLE
        rootView.orderBy.visibility = View.GONE
        rootView.groupBy.visibility = View.GONE
      } else {
        rootView.noStories.visibility = View.GONE
        rootView.orderBy.visibility = View.VISIBLE
        rootView.groupBy.visibility = View.VISIBLE
        viewModel.arrangeStories(stories, Prefs.authorArrangement())
      }
      rootView.orderBy.setOnClickListener {
        orderByDialog(context!!,
            Prefs.authorOrderStrategy, Prefs.authorOrderDirection) { str, dir ->
          Prefs.authorOrderDirection = dir
          Prefs.authorOrderStrategy = str
          viewModel.arrangeStories(stories, Prefs.authorArrangement())
        }
      }
      rootView.groupBy.setOnClickListener { _ ->
        groupByDialog(context!!, Prefs.authorGroupStrategy) {
          Prefs.authorGroupStrategy = it
          viewModel.arrangeStories(stories, Prefs.authorArrangement())
        }
      }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
      viewModel = obtainViewModel()
      val rootView = inflater.inflate(R.layout.fragment_author_stories, container, false)
      // FIXME maybe replace storyById with storiesById
      viewModel.launch(UI) {
        val stories = arguments!!.getParcelableArrayList<StoryModel>(ARG_STORIES)!!.map {
          val model = Static.database.storyById(it.storyId).await().orNull() ?: return@map it
          it.progress = model.progress
          it.status = model.status
          return@map it
        }
        initLayout(rootView, stories)
        viewModel.defaultStoryListObserver.register()
      }
      return rootView
    }

    override fun onDestroy() {
      super.onDestroy()
      viewModel.defaultStoryListObserver.unregister()
    }
  }
}
