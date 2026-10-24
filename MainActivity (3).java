package com.alayman.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslError;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.net.HttpURLConnection;
import java.net.URL;

import androidx.activity.result.ActivityResultLauncher;

/**
 * نافذة وحيدة: شاشة "اتصال بالسيرفر" (إدخال يدوي أو مسح QR) ثم عرض برنامج
 * الأيمن كاملاً عبر WebView بمجرد الاتصال. عنوان السيرفر يُحفظ محلياً على
 * الموبايل، فبعد أول اتصال ناجح يفتح البرنامج مباشرة في كل مرة.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "alayman_prefs";
    private static final String KEY_SERVER = "server_url";

    private View connectScreen;
    private SwipeRefreshLayout swipeRefresh;
    private WebView webView;
    private EditText edtServer;
    private TextView txtStatus;
    private SharedPreferences prefs;

    private final ActivityResultLauncher<ScanOptions> qrLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (result.getContents() != null) {
                    edtServer.setText(result.getContents());
                    tryConnect(result.getContents());
                }
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        connectScreen = findViewById(R.id.connectScreen);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webview);
        edtServer = findViewById(R.id.edtServer);
        txtStatus = findViewById(R.id.txtStatus);
        MaterialButton btnScan = findViewById(R.id.btnScan);
        MaterialButton btnConnect = findViewById(R.id.btnConnect);

        setupWebView();

        btnScan.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("وجّه الكاميرا نحو رمز QR الظاهر في إعدادات البرنامج");
            options.setBeepEnabled(true);
            options.setOrientationLocked(true);
            qrLauncher.launch(options);
        });

        btnConnect.setOnClickListener(v -> {
            String url = edtServer.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                txtStatus.setText(R.string.err_empty);
                return;
            }
            tryConnect(url);
        });

        swipeRefresh.setOnRefreshListener(() -> {
            webView.reload();
            swipeRefresh.setRefreshing(false);
        });
        // صفحات البرنامج تُمرَّر داخلياً (عنصر مستقل داخل الصفحة نفسها) وليس عبر
        // تمرير صفحة الـ WebView بأكملها، فـ SwipeRefreshLayout لا يستطيع يعرف متى
        // المستخدم وصل لأعلى الصفحة فعلياً، فيتعارض مع أي سحب رأسي عادي داخل الجداول
        // والشاشات (يتجمّد أو يحدّث الصفحة بالغلط). البيانات أصلاً تتحدّث تلقائياً لحظياً
        // من السيرفر (live sync)، فلا حاجة فعلية لسحب-للتحديث هنا.
        swipeRefresh.setEnabled(false);

        // اضغط مطوّلاً على الشاشة في أي وقت للرجوع لشاشة تغيير عنوان السيرفر
        webView.setOnLongClickListener(v -> {
            showConnectScreen(null);
            return true;
        });

        String saved = prefs.getString(KEY_SERVER, null);
        if (!TextUtils.isEmpty(saved)) {
            edtServer.setText(saved);
            openServer(saved, true);
        }
    }

    private void setupWebView() {
        // نلغي تأثير "الارتداد" الأصلي عند وصول التمرير لحافة المحتوى، لأنه هو سبب
        // إحساس "التجمّد/التحديث الغلط" عند السحب — الصفحة تُمرَّر من عنصر داخلي
        // بالـ HTML نفسه وليس من WebView مباشرة، فالارتداد الافتراضي يتعارض معها.
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showConnectScreen(getString(R.string.err_connect));
                }
            }

            // يسمح بشهادات ذاتية التوقيع إن استُخدم https محلياً مستقبلاً
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });
    }

    /** يتحقق أن عنوان السيرفر صالح وأن الخادم يستجيب فعلاً قبل فتح الواجهة */
    private void tryConnect(String rawUrl) {
        String url = normalize(rawUrl);
        txtStatus.setText(R.string.checking);
        new Thread(() -> {
            boolean ok = ping(url);
            runOnUiThread(() -> {
                if (ok) {
                    prefs.edit().putString(KEY_SERVER, url).apply();
                    openServer(url, false);
                } else {
                    txtStatus.setText(R.string.err_connect);
                }
            });
        }).start();
    }

    private boolean ping(String baseUrl) {
        try {
            URL u = new URL(baseUrl);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private void openServer(String url, boolean silent) {
        connectScreen.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);
        webView.loadUrl(url + "/");
    }

    private void showConnectScreen(String message) {
        swipeRefresh.setVisibility(View.GONE);
        connectScreen.setVisibility(View.VISIBLE);
        if (message != null) txtStatus.setText(message);
    }

    @Override
    public void onBackPressed() {
        if (swipeRefresh.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (swipeRefresh.getVisibility() == View.VISIBLE) {
            // بدل إغلاق التطبيق مباشرة من الصفحة الرئيسية، نرجع لشاشة الاتصال لتبديل السيرفر عند الحاجة
            super.onBackPressed();
        } else {
            super.onBackPressed();
        }
    }
}
