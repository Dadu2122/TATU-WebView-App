package com.shreeyog.tatu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // Yahi asli site hai jo pehle TWA (Chrome Custom Tabs) ke through khulti thi.
    // Ab seedha WebView ke andar khulegi, koi Chrome beech mein nahi aayega.
    private val siteUrl = "https://dadu2122.github.io/Digital-Attendance-Register-/"

    // Sirf inhi domains ko WebView ke andar khulne do. Baaki (whatsapp, maps, mailto)
    // bahar wale app mein bhejo.
    private val allowedHosts = setOf(
        "dadu2122.github.io",
        "student-attendance-6d56b-default-rtdb.firebaseio.com",
        "identitytoolkit.googleapis.com",
        "securetoken.googleapis.com",
        "cdn.jsdelivr.net",
        "cdnjs.cloudflare.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "api.qrserver.com",
        "www.gstatic.com"
    )

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var offlineLayout: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.isEnabled = false
        offlineLayout = findViewById(R.id.offlineLayout)

        setupFileChooserLauncher()
        setupPermissionLauncher()
        setupWebView()

        findViewById<Button>(R.id.retryButton).setOnClickListener {
            offlineLayout.visibility = View.GONE
            webView.reload()
        }

        swipeRefresh.setOnRefreshListener { webView.reload() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(siteUrl)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true          // localStorage ke liye zaroori
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportZoom(false)
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.userAgentString = settings.userAgentString + " TatuWebViewApp/1.0"

        // Blob PDF/photo download aur AndroidDownloader interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host ?: return true
                return if (allowedHosts.contains(host)) {
                    false // WebView ke andar hi khulne do
                } else {
                    openExternally(uri)
                    true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                injectDownloadHelper()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    offlineLayout.visibility = View.VISIBLE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 100) progressBar.visibility = View.GONE
            }

            // <input type="file"> (teacher photo / signature upload) ke liye
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback = callback
                val intent = params.createIntent()
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    false
                }
            }

            // Agar site kabhi camera/mic maange (getUserMedia)
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (needsCamera && ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    request.grant(request.resources)
                }
            }
        }

        // Normal (non-blob) http downloads jaise seedha PDF link
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment)
                val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Download shuru ho gaya", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download fail: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * jsPDF / html2canvas jaise tools 'blob:' URL bana kar ek chhupa hua <a download> click
     * karte hain. WebView aise blob downloads khud handle nahi karta, isliye har page load
     * ke baad ek chhota JS hook lagate hain jo blob ko base64 mein convert karke Android
     * interface ko bhej deta hai, wahan se file Downloads folder mein save hoti hai.
     */
    private fun injectDownloadHelper() {
        val js = """
            (function(){
              if (window._tatuHookInstalled) return;
              window._tatuHookInstalled = true;
              document.addEventListener('click', function(e){
                var a = e.target.closest && e.target.closest('a[download]');
                if (!a) return;
                var href = a.getAttribute('href') || '';
                if (href.indexOf('blob:') !== 0) return;
                e.preventDefault();
                var filename = a.getAttribute('download') || 'download';
                fetch(href).then(function(r){ return r.blob(); }).then(function(blob){
                  var reader = new FileReader();
                  reader.onloadend = function(){
                    var base64 = reader.result.split(',')[1];
                    AndroidDownloader.saveBase64File(base64, filename, blob.type || 'application/octet-stream');
                  };
                  reader.readAsDataURL(blob);
                });
              }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Koi app nahi mila is link ke liye", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = if (result.resultCode == Activity.RESULT_OK) result.data else null
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    private fun setupPermissionLauncher() {
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* result WebView ko khud handle karne dete hain */ }
    }

    /** JS se call hone wala interface - blob PDFs/images ko Downloads folder me save karta hai */
    inner class WebAppInterface(private val activity: Activity) {
        @JavascriptInterface
        fun saveBase64File(base64Data: String, filename: String, mimeType: String) {
            runOnUiThread {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: activity.getDir("downloads", MODE_PRIVATE)
                    if (!downloadsDir.exists()) downloadsDir.mkdirs()
                    val file = File(downloadsDir, filename)
                    FileOutputStream(file).use { it.write(bytes) }
                    Toast.makeText(activity, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()

                    // Turant share/open sheet bhi khol do taaki user WhatsApp par bhej sake
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        activity, "$packageName.fileprovider", file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "File share karein"))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Save fail: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
