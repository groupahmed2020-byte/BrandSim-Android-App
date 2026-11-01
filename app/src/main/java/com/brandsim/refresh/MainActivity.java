package com.brandsim.refresh;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private WebView mainWebView;
    private static final String SKY_URL = "https://rn.sky-5g.net";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        mainWebView = new WebView(this);
        setContentView(mainWebView);
        WebSettings s = mainWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        mainWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        mainWebView.setWebViewClient(new WebViewClient());
        mainWebView.setWebChromeClient(new WebChromeClient());
        mainWebView.loadUrl("file:///android_asset/refresh.html");
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void autoRefreshSky(final String phone) {
            new Handler(Looper.getMainLooper()).post(() -> openSkyDialog(phone));
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openSkyDialog(final String phone) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        Button closeBtn = new Button(this);
        closeBtn.setText("إغلاق بوابة سكاي والعودة ✕");
        closeBtn.setBackgroundColor(Color.parseColor("#0f172a"));
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setOnClickListener(v -> dialog.dismiss());

        WebView skyWebView = new WebView(this);
        WebSettings ss = skyWebView.getSettings();
        ss.setJavaScriptEnabled(true);
        ss.setDomStorageEnabled(true);

        skyWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String js = "javascript:(function(){" +
                    "var attempts=0;" +
                    "var timer=setInterval(function(){" +
                    "attempts++;" +
                    "var inp=document.querySelector('input[type=\"tel\"],input[type=\"text\"],input[type=\"number\"],input:not([type=\"hidden\"])');" +
                    "if(inp){" +
                    "clearInterval(timer);" +
                    "inp.focus();" +
                    "var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
                    "setter.call(inp,'" + phone + "');" +
                    "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                    "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "setTimeout(function(){" +
                    "var btn=document.querySelector('button[type=\"submit\"],input[type=\"submit\"],button');" +
                    "if(btn)btn.click();" +
                    "},500);" +
                    "}" +
                    "if(attempts>15)clearInterval(timer);" +
                    "},400);" +
                    "})();";
                view.evaluateJavascript(js, null);
            }
        });

        layout.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(skyWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        dialog.setContentView(layout);
        dialog.show();
        skyWebView.loadUrl(SKY_URL);
    }

    @Override
    public void onBackPressed() {
        if (mainWebView != null && mainWebView.canGoBack()) mainWebView.goBack();
        else super.onBackPressed();
    }
}
