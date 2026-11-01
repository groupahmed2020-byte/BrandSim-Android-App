package com.brandsim.refresh;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
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
import android.widget.Toast;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView mainWebView;
    private boolean isPageLoaded = false;
    private String pendingPhone = null;
    private String pendingSender = null;
    public static MainActivity instance;
    private static final String SKY_URL = "https://rn.sky-5g.net";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        checkSmsPermissions();

        mainWebView = new WebView(this);
        setContentView(mainWebView);
        WebSettings s = mainWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        mainWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        mainWebView.setWebChromeClient(new WebChromeClient());
        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isPageLoaded = true;
                if (pendingPhone != null) {
                    triggerWebRefresh(pendingPhone, pendingSender);
                    pendingPhone = null;
                    pendingSender = null;
                }
            }
        });
        mainWebView.loadUrl("file:///android_asset/refresh.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("sms_target_phone")) {
            String phone = intent.getStringExtra("sms_target_phone");
            String sender = intent.getStringExtra("sms_sender");
            intent.removeExtra("sms_target_phone");
            processSmsRequest(phone, sender);
        }
    }

    public void processSmsRequest(final String phone, final String sender) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isPageLoaded && mainWebView != null) {
                triggerWebRefresh(phone, sender);
            } else {
                pendingPhone = phone;
                pendingSender = sender;
            }
        });
    }

    private void triggerWebRefresh(String phone, String sender) {
        String safeSender = sender == null ? "" : sender.replace("'", "");
        String js = "javascript:if(window.executeSmsAutoRefresh){window.executeSmsAutoRefresh('" + phone + "','" + safeSender + "');}";
        mainWebView.evaluateJavascript(js, null);
    }

    private void checkSmsPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.SEND_SMS
                }, 101);
            }
        }
    }

    // فلتر ذكي يستخرج رقم 05XXXXXXXX ويتجاهل أي نص أو +972 أو شحطات أو مسافات
    public static String extractCleanPhone(String rawText) {
        if (rawText == null || rawText.isEmpty()) return null;
        if (rawText.contains("BRAND SIM") || rawText.contains("تم تحديث وتنشيط الرقم")) return null;

        StringBuilder sb = new StringBuilder();
        for (char c : rawText.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        String cleaned = sb.toString().replaceAll("[\\s\\-\\.\\(\\)\\+\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "");
        Matcher m = Pattern.compile("(?:00972|00970|972|970|0)?(5\\d{8})").matcher(cleaned);
        if (m.find()) {
            return "0" + m.group(1);
        }
        return null;
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void autoRefreshSky(final String phone, final String sender) {
            new Handler(Looper.getMainLooper()).post(() -> openSkyDialog(phone, sender));
        }

        @JavascriptInterface
        public void sendSmsReply(final String sender, final String message) {
            if (sender == null || sender.trim().isEmpty()) return;
            try {
                SmsManager sms = SmsManager.getDefault();
                ArrayList<String> parts = sms.divideMessage(message);
                sms.sendMultipartTextMessage(sender, null, parts, null, null);
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(MainActivity.this, "تم إرسال رسالة تأكيد للزبون: " + sender, Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openSkyDialog(final String phone, final String sender) {
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
            private boolean done = false;
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (done) return;
                done = true;
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

                // إرسال الرد وإغلاق نافذة سكاي تلقائياً بعد 4 ثوانٍ من الضغط
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (sender != null && !sender.trim().isEmpty()) {
                        new WebAppInterface().sendSmsReply(sender, "✅ تم تحديث وتنشيط الرقم " + phone + " بنجاح - BRAND SIM");
                    }
                    if (dialog.isShowing()) dialog.dismiss();
                }, 4500);
            }
        });

        layout.addView(closeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(skyWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        dialog.setContentView(layout);
        dialog.show();
        skyWebView.loadUrl(SKY_URL);
    }

    public static class SmsReceiver extends BroadcastReceiver {
        private static String lastProcessedPhone = "";
        private static long lastProcessedTime = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;
            Object[] pdus = (Object[]) bundle.get("pdus");
            String format = bundle.getString("format");
            if (pdus == null || pdus.length == 0) return;

            StringBuilder fullMsg = new StringBuilder();
            String sender = "";
            for (Object pdu : pdus) {
                SmsMessage sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                    SmsMessage.createFromPdu((byte[]) pdu, format) :
                    SmsMessage.createFromPdu((byte[]) pdu);
                if (sms != null) {
                    if (sender.isEmpty() && sms.getOriginatingAddress() != null) {
                        sender = sms.getOriginatingAddress();
                    }
                    if (sms.getMessageBody() != null) {
                        fullMsg.append(sms.getMessageBody());
                    }
                }
            }

            String extractedPhone = extractCleanPhone(fullMsg.toString());
            if (extractedPhone != null) {
                long now = SystemClock.elapsedRealtime();
                if (extractedPhone.equals(lastProcessedPhone) && (now - lastProcessedTime) < 15000) return;
                lastProcessedPhone = extractedPhone;
                lastProcessedTime = now;

                if (instance != null) {
                    instance.processSmsRequest(extractedPhone, sender);
                } else {
                    Intent launch = new Intent(context, MainActivity.class);
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    launch.putExtra("sms_target_phone", extractedPhone);
                    launch.putExtra("sms_sender", sender);
                    context.startActivity(launch);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mainWebView != null && mainWebView.canGoBack()) mainWebView.goBack();
        else super.onBackPressed();
    }
                            }
        
