package slak.fanfictionstories

import android.annotation.SuppressLint
import android.app.Application
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slak.fanfictionstories.data.fetchers.RATE_LIMIT_MS
import slak.fanfictionstories.data.fetchers.URL_TAG
import slak.fanfictionstories.data.fetchers.networkContext
import slak.fanfictionstories.data.fetchers.waitForNetwork
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private object HTMLJSInterface {
  lateinit var lastHTML: String

  @JavascriptInterface
  @Suppress("unused")
  fun setInnerHTML(data: String) {
    Log.v(URL_TAG, "Received extracted HTML from @JSInterface")
    lastHTML = data
  }
}

private object FFWebClient : WebViewClient() {
  var currentCallback: ((url: String) -> Unit)? = null

  override fun onPageFinished(view: WebView?, url: String) {
    if (currentCallback != null) {
      Log.v(URL_TAG, "Page load finished for: $url")
      currentCallback?.invoke(url)
    }
  }
}

class WVViewModel(application: Application) : AndroidViewModel(application) {
  // This lives as long as the application, it's fine
  @SuppressLint("StaticFieldLeak")
  val webView = WebView(application)

  val defaultUserAgent: String = webView.settings.userAgentString

  private val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/99.0.4844.73 Safari/537.36"

  init {
    webView.webViewClient = FFWebClient

    // Clearly needed here
    @SuppressLint("SetJavaScriptEnabled")
    webView.settings.javaScriptEnabled = true
    webView.addJavascriptInterface(HTMLJSInterface, "Interface")

    webView.loadUrl("https://www.fanfiction.net")

    webView.visibility = View.GONE
  }

  @UiThread
  private fun cancel() {
    Log.v(URL_TAG, "Load cancelled, stop loading webview")
    webView.stopLoading()
    FFWebClient.currentCallback = null
    webView.loadUrl("https://www.fanfiction.net")
  }

  @UiThread
  fun addWebView(target: ViewGroup) {
    if (FFWebClient.currentCallback != null) {
      cancel()
    }
    if (webView.parent != null) {
      (webView.parent as ViewGroup).removeView(webView)
    }
    target.addView(webView, MATCH_PARENT, 700)
  }

  @UiThread
  private suspend fun getWebDocument(url: String, userAgent: String) = suspendCoroutine<String> { continuation ->
    webView.settings.userAgentString = userAgent
    FFWebClient.currentCallback = cb@ { loadedUrl ->
      // Cloudflare... don't ask...
      val js = """
        (function() {
          window.speechSynthesis = {
            getVoices: () => [],
            set onvoiceschanged(cb) {
              setTimeout(() => {cb({})}, 67);
            }
          };
          Interface.setInnerHTML(document.documentElement.outerHTML);
        })()
      """.trimIndent()
      webView.evaluateJavascript(js) {
        val html = HTMLJSInterface.lastHTML
        if ("Just a moment..." in html || "Cloudflare" in html) {
          Log.v(URL_TAG, "Waiting for additional redirect")
          GlobalScope.launch(Main) {
            webView.visibility = View.VISIBLE
            delay(5000)
            webView.visibility = View.GONE
          }
          return@evaluateJavascript
        }
        FFWebClient.currentCallback = null
        continuation.resume(html)
      }
    }

    webView.loadUrl(url)
  }

  /**
   * Wait for the document at the given [url] to load, and return the HTML contents.
   *
   * Call [onError] for every error, and retry if failed.
   */
  @AnyThread
  suspend fun patientlyFetchDocument(
      url: String,
      userAgent: String = desktopUserAgent,
      onError: (t: Throwable) -> Unit
  ): String = withContext(networkContext) {
    waitForNetwork()
    delay(RATE_LIMIT_MS)
    Log.v(URL_TAG, "Waited for rate limit, retrying...")

    return@withContext try {
      withContext(Main) {
        val text = getWebDocument(url, userAgent)
        Notifications.ERROR.cancel()
        text
      }
    } catch (t: Throwable) {
      onError(t)
      Log.e(URL_TAG, "Failed to fetch url ($url)", t)
      patientlyFetchDocument(url, userAgent, onError)
    }
  }
}