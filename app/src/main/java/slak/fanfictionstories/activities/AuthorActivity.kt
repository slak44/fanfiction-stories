package slak.fanfictionstories.activities

import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.*
import android.widget.ProgressBar
import androidx.activity.viewModels
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import slak.fanfictionstories.*
import slak.fanfictionstories.data.Prefs
import slak.fanfictionstories.data.database
import slak.fanfictionstories.data.fetchers.Author
import slak.fanfictionstories.data.fetchers.getAuthor
import slak.fanfictionstories.databinding.ActivityAuthorBinding
import slak.fanfictionstories.databinding.FragmentAuthorBioBinding
import slak.fanfictionstories.databinding.FragmentAuthorStoriesBinding
import slak.fanfictionstories.utility.*
import java.util.*

/** Stores the data required for the author page. */
class AuthorViewModel(val author: Author) : ViewModel()

/**
 * An author's detail page. Has his bio, his stories, his favorite stories, his favourite authors, and other user
 * related actions.
 */
class AuthorActivity : CoroutineScopeActivity(), IHasLoadingBar {
  override lateinit var loading: ProgressBar

  private lateinit var binding: ActivityAuthorBinding

  private lateinit var author: Author

  private val viewModel: AuthorViewModel by viewModels { ViewModelFactory(author) }

  /**
   * The [android.support.v4.view.PagerAdapter] that will provide fragments for each of the sections. We use a
   * [FragmentPagerAdapter] derivative, which will keep every loaded fragment in memory. If this becomes too memory
   * intensive, it may be best to switch to a [android.support.v4.app.FragmentStatePagerAdapter].
   */
  private var sectionsPagerAdapter: SectionsPagerAdapter? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityAuthorBinding.inflate(layoutInflater)
    setContentView(binding.root)
    setSupportActionBar(binding.toolbar)
    setLoadingView(binding.toolbar, 1)
    supportActionBar?.setDisplayHomeAsUpEnabled(true)

    Static.wvViewModel.addWebView(binding.rootLayout)

    val (authorName, authorId) = if (intent.action == ACTION_VIEW) {
      val pathSegments = intent?.data?.pathSegments
          ?: throw IllegalArgumentException("Missing intent data")
      val name = pathSegments.getOrNull(2) ?: str(R.string.loading)
      name to pathSegments[1].toLong()
    } else {
      val name = intent.getStringExtra(INTENT_AUTHOR_NAME)
      val id = intent.getLongExtra(INTENT_AUTHOR_ID, -1L)
      require(id != -1L) { "Intent has no author id" }
      name to id
    }

    title = authorName
    binding.container.addOnPageChangeListener(TabLayout.TabLayoutOnPageChangeListener(binding.tabs))
    binding.tabs.addOnTabSelectedListener(TabLayout.ViewPagerOnTabSelectedListener(binding.container))
    showLoading()

    launch(Main) {
      val maybeAuthor = getAuthor(authorId)

      if (maybeAuthor == null) {
        hideLoading()
        return@launch
      }

      author = maybeAuthor
      title = viewModel.author.name
      invalidateOptionsMenu()
      sectionsPagerAdapter = SectionsPagerAdapter(supportFragmentManager)
      binding.container.adapter = sectionsPagerAdapter
      hideLoading()
    }
  }

  /** Ensure the tabs are gone when the loading bar is there. */
  override fun showLoading() {
    super.showLoading()
    binding.tabs.visibility = View.GONE
  }

  /** Ensure the tabs come back when the loading bar goes away. */
  override fun hideLoading() {
    super.hideLoading()
    binding.tabs.visibility = View.VISIBLE
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

  /** A [FragmentPagerAdapter] that returns a fragment corresponding to the tabs. */
  inner class SectionsPagerAdapter(
      fm: FragmentManager
  ) : FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
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
        HtmlFragment.newInstance(html, viewModel.author.imageUrl)
      }
      1 -> StoryListFragment.newInstance(ArrayList(viewModel.author.userStories))
      2 -> StoryListFragment.newInstance(ArrayList(viewModel.author.favoriteStories))
      else -> throw IllegalStateException("getCount returned too many tabs")
    }

    override fun getCount(): Int = 3 // tabs
  }

  /** Fragment that renders HTML in a [android.widget.TextView]. */
  internal class HtmlFragment : Fragment() {
    private lateinit var binding: FragmentAuthorBioBinding

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
      binding = FragmentAuthorBioBinding.inflate(inflater, container, false)
      binding.root.post {
        binding.html.text = Html.fromHtml(requireArguments().getString(ARG_HTML_TEXT),
            Html.FROM_HTML_MODE_LEGACY, null, HrSpan.tagHandlerFactory(binding.root.width))
        binding.html.movementMethod = LinkMovementMethod.getInstance()
        val imageUrl = requireArguments().getString(ARG_HTML_IMG_URL)
        binding.authorImage.visibility = if (imageUrl == null) View.GONE else View.VISIBLE
        binding.authorImage.setOnClickListener {
          (activity as CoroutineScopeActivity).launch {
            Static.currentCtx.showImage(R.string.image_author, imageUrl!!)
          }
        }
      }
      return binding.root
    }

    companion object {
      private const val ARG_HTML_TEXT = "html_text"
      private const val ARG_HTML_IMG_URL = "html_img_url"

      /** Returns a new instance of this fragment for the given HTML text. */
      fun newInstance(html: String, imageUrl: String?): HtmlFragment {
        val fragment = HtmlFragment()
        val args = Bundle()
        args.putString(ARG_HTML_TEXT, html)
        args.putString(ARG_HTML_IMG_URL, imageUrl)
        fragment.arguments = args
        return fragment
      }
    }
  }

  /**
   * Fragment that lists stories using a [RecyclerView] and [StoryAdapter].
   */
  internal class StoryListFragment : Fragment() {
    private lateinit var binding: FragmentAuthorStoriesBinding

    private val viewModel by lazy { ViewModelProvider(this).get(StoryListViewModel::class.java) }

    @UiThread
    private fun initLayout(stories: List<StoryModel>) {
      binding.stories.layoutManager = LinearLayoutManager(context)
      binding.stories.createStorySwipeHelper()
      binding.stories.adapter = StoryAdapter(viewModel)
      if (stories.isEmpty()) {
        binding.noStories.visibility = View.VISIBLE
        binding.orderBy.visibility = View.GONE
        binding.groupBy.visibility = View.GONE
      } else {
        binding.noStories.visibility = View.GONE
        binding.orderBy.visibility = View.VISIBLE
        binding.groupBy.visibility = View.VISIBLE
        viewModel.arrangeStories(stories, Prefs.authorArrangement())
      }
      binding.orderBy.setOnClickListener {
        orderByDialog(requireContext(),
            Prefs.authorOrderStrategy, Prefs.authorOrderDirection) { str, dir ->
          Prefs.authorOrderDirection = dir
          Prefs.authorOrderStrategy = str
          viewModel.arrangeStories(stories, Prefs.authorArrangement())
        }
      }
      binding.groupBy.setOnClickListener { _ ->
        groupByDialog(requireContext(), Prefs.authorGroupStrategy) {
          Prefs.authorGroupStrategy = it
          viewModel.arrangeStories(stories, Prefs.authorArrangement())
        }
      }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
      binding = FragmentAuthorStoriesBinding.inflate(inflater, container, false)
      // FIXME maybe replace storyById with storiesById
      viewModel.launch(Main) {
        val stories = requireArguments().getParcelableArrayList<StoryModel>(ARG_STORIES)!!.map {
          val model = Static.database.storyById(it.storyId).await().orNull() ?: return@map it
          it.progress = model.progress
          it.status = model.status
          return@map it
        }
        initLayout(stories)
        viewModel.defaultStoryListObserver.register()
      }
      return binding.root
    }

    override fun onDestroy() {
      super.onDestroy()
      viewModel.defaultStoryListObserver.unregister()
    }

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
  }

  companion object {
    const val INTENT_AUTHOR_ID = "author_id_intent"
    const val INTENT_AUTHOR_NAME = "author_name_intent"
  }
}
