package com.brandsim.refresh;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.*;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

public class MainActivity extends Activity {
    private WebView mainWebView;
    private FrameLayout rootLayout;
    public static MainActivity instance;
    public static String lastSentSmsBody = "";
    private static final String SKY_URL = "https://rn.sky-5g.net";
    public static final Map<String, WhatsAppReplyAction> waReplyMap = new ConcurrentHashMap<>();

    public static class WhatsAppReplyAction {
        public final PendingIntent pendingIntent;
        public final RemoteInput[] remoteInputs;
        public final String sbnKey;
        public WhatsAppReplyAction(PendingIntent pi, RemoteInput[] ri, String key) {
            this.pendingIntent = pi;
            this.remoteInputs = ri;
            this.sbnKey = key;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        instance = this;
        rootLayout = new FrameLayout(this);
        mainWebView = new WebView(this);
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(rootLayout);
        configureWebView(mainWebView, this);
        mainWebView.loadUrl("file:///android_asset/refresh.html");
        startPermissionSetupFlow();
        ensureWaListenerAlive(this, true);
        startBackgroundRefreshService(this, null, null, null);
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && "brandsim".equals(data.getScheme())) {
                String phone = data.getQueryParameter("phone");
                if (phone != null && !phone.isEmpty()) runHeadlessSkyRefresh(this, phone, null);
            }
            setIntent(new Intent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleIncomingIntent(getIntent());
        if (hasRuntimePermissions() && !isNotificationListenerEnabled(this)) checkSpecialPermissions();
        else ensureWaListenerAlive(this, false);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        scheduleServiceRestart(getApplicationContext());
        super.onDestroy();
    }

    public static void ensureWaListenerAlive(Context ctx, boolean forceToggle) {
        try {
            Context appCtx = ctx.getApplicationContext();
            if (!isNotificationListenerEnabled(appCtx)) return;
            if (!forceToggle && WhatsAppListener.isConnected) return;
            ComponentName cn = new ComponentName(appCtx, WhatsAppListener.class);
            PackageManager pm = appCtx.getPackageManager();
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) NotificationListenerService.requestRebind(cn);
        } catch (Exception e) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void configureWebView(WebView wv, Context ctx) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.addJavascriptInterface(new WebAppInterface(ctx.getApplicationContext()), "AndroidBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient());
    }

    public void updateUiMessage(final String type, final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mainWebView != null) {
                String safeMsg = msg == null ? "" : msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                mainWebView.evaluateJavascript("javascript:if(window.showMessage){window.showMessage('" + type + "','" + safeMsg + "');}", null);
            }
        });
    }

    public void attachSkyCaptchaView(final WebView skyWv) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (rootLayout == null || skyWv == null) return;
                if (skyWv.getParent() != null) ((ViewGroup) skyWv.getParent()).removeView(skyWv);
                rootLayout.addView(skyWv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                skyWv.bringToFront();
                Toast.makeText(this, "يرجى تخطي الكابتشا لإكمال تحديث رقم سكاي تلقائياً", Toast.LENGTH_LONG).show();
            } catch (Exception e) {}
        });
    }

    public void detachSkyCaptchaView(final WebView skyWv) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (rootLayout != null && skyWv != null && skyWv.getParent() == rootLayout) rootLayout.removeView(skyWv);
            } catch (Exception e) {}
        });
    }

    public static void startBackgroundRefreshService(Context context, String phone, String sender, String mode) {
        try {
            Intent srv = new Intent(context.getApplicationContext(), RefreshService.class);
            if (phone != null) {
                srv.putExtra("sms_target_phone", phone);
                srv.putExtra("sms_sender", sender != null ? sender : "");
                srv.putExtra("sms_mode", mode != null ? mode : "refresh");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.getApplicationContext().startForegroundService(srv);
            else context.getApplicationContext().startService(srv);
        } catch (Exception e) {}
    }

    public static void scheduleServiceRestart(Context context) {
        try {
            Intent restartIntent = new Intent(context.getApplicationContext(), SmsReceiver.class);
            restartIntent.setAction("com.brandsim.refresh.RESTART_SERVICE");
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT;
            PendingIntent pi = PendingIntent.getBroadcast(context.getApplicationContext(), 777, restartIntent, flags);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 800, pi);
        } catch (Exception e) {}
    }

    public static boolean isNotificationListenerEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(ctx.getPackageName());
        } catch (Exception e) { return false; }
    }

    private boolean hasRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void startPermissionSetupFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_SMS);
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.SEND_SMS);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) perms.add("android.permission.POST_NOTIFICATIONS");
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), 101);
                return;
            }
        }
        checkSpecialPermissions();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) checkSpecialPermissions();
    }

    private void checkSpecialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                }
            } catch (Exception e) {}
        }
        if (!isNotificationListenerEnabled(this)) promptForNotificationAccess();
        else ensureWaListenerAlive(this, true);
    }

    private void promptForNotificationAccess() {
        new AlertDialog.Builder(this)
            .setTitle("تفعيل الرد التلقائي لواتساب")
            .setMessage("يرجى تفعيل إذن الإشعارات لتطبيق BRAND SIM.")
            .setPositiveButton("متابعة", (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
            })
            .setNegativeButton("لاحقاً", null)
            .setCancelable(false)
            .show();
    }

    public static boolean isIgnoredSystemSms(String rawText) {
        if (rawText == null || rawText.isEmpty()) return true;
        String t = rawText.trim();
        if (!lastSentSmsBody.isEmpty() && t.equals(lastSentSmsBody.trim())) return true;
        return t.contains("حاول الاتصال") || t.contains("اتصال بك") || t.contains("أصبح متاح") ||
               t.contains("متاح حاليا") || t.contains("موجود") || t.contains("غير مسجل في منظومة") ||
               t.contains("قد يستغرق الرد") || t.contains("تاريخ انتهاء الحزمة:") ||
               t.contains("رسائل جديدة") || t.contains("new messages") || t.contains("جاري فحص");
    }

    public static boolean isDetailsRequestSms(String rawText) {
        return rawText != null && Pattern.compile("(تفاصيل|تفصيل|تاريخ|انتهاء|إنتهاء|تنتهي|ينتهي|بتنتهي|بنتهي|حزمة|حزمه|باقة|باقه|متى|موعد|استعلام|إستعلام|صلاحية|صلاحيه|اشتراك|إشتراك|بيانات|معلومات)").matcher(rawText).find();
    }

    public static String extractCleanPhone(String rawText, boolean allow059ForDetails) {
        if (isIgnoredSystemSms(rawText)) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : rawText.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        String cleaned = sb.toString().replaceAll("[\\s\\-\\.\\(\\)\\+\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "");
        Matcher m = Pattern.compile("(?:00972|00970|972|970|0)?(5\\d{8})").matcher(cleaned);
        String first = null;
        while (m.find()) {
            String cand = "0" + m.group(1);
            if (first == null) first = cand;
            if (!cand.startsWith("059") && !cand.startsWith("056")) return cand;
        }
        return first;
    }

    public static void sendSmsDirect(final Context ctx, final String sender, final String message) {
        if (sender == null || sender.trim().isEmpty() || message == null || message.trim().isEmpty()) return;
        if ("WEB".equalsIgnoreCase(sender) || "XONE1".equalsIgnoreCase(sender)) return;
        String cleanReply = message.trim();
        lastSentSmsBody = cleanReply;

        WhatsAppReplyAction waAction = waReplyMap.get(sender);
        if (waAction != null) {
            try {
                Intent localIntent = new Intent();
                Bundle localBundle = new Bundle();
                for (RemoteInput remoteIn : waAction.remoteInputs) localBundle.putCharSequence(remoteIn.getResultKey(), cleanReply);
                RemoteInput.addResultsToIntent(waAction.remoteInputs, localIntent, localBundle);
                waAction.pendingIntent.send(ctx, 0, localIntent);
                if (WhatsAppListener.instance != null && waAction.sbnKey != null) {
                    try { WhatsAppListener.instance.cancelNotification(waAction.sbnKey); } catch (Exception ignored) {}
                }
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم الرد عبر واتساب بنجاح", Toast.LENGTH_SHORT).show());
                return;
            } catch (Exception e) { return; }
        }
        if (sender.contains("_wa_") || sender.startsWith("wa_") || !sender.matches("^[+0-9]{7,15}$")) return;
        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(cleanReply);
            sms.sendMultipartTextMessage(sender, null, parts, null, null);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم إرسال رد SMS: " + sender, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {}
    }

    public static boolean isWaitingText(String text) {
        if (text == null) return true;
        String t = text.replaceAll("[\u064B-\u065F\u0670]", "")
                       .replaceAll("[إأآا]", "ا")
                       .replace('ى', 'ي')
                       .replace('ة', 'ه')
                       .replaceAll("[\\s\\-_.,!؟:()]+", " ")
                       .toLowerCase()
                       .trim();
        if (t.length() <= 2) return true;
        String numOnly = t.replaceAll("[^0-9]", "");
        if (numOnly.length() >= 9 && numOnly.length() <= 12 && t.length() <= 15) return true;
        if (t.equals("تحديث") || t.equals("تحديث الرقم") || t.equals("ارسال") || t.equals("بحث") || t.equals("sky telecom")) return true;

        // استثناء رسائل النتيجة الفعلية (نجاح أو فشل أو تحديث سابق) لكي لا تُعامل كرسائل انتظار
        if (t.contains("قبل قليل") || t.contains("فشل") || t.contains("بنجاح") || t.contains("تم تحديث") || t.contains("غير صحيح") || t.contains("غير صالح") || t.contains("غير مسجل") || t.contains("لا يمكن")) {
            return false;
        }

        String[] kws = {"يستغرق", "استغراق", "تعاون", "جاري", "معالج", "لحظ", "تحميل", "loading", "please wait", "wait", "processing", "captcha", "كابتشا", "روبوت"};
        for (String kw : kws) if (t.contains(kw)) return true;
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(ctx, "جاري تحديث سكاي بالخلفية: " + phone, Toast.LENGTH_SHORT).show();
            final WebView skyWebView = new WebView(ctx.getApplicationContext());
            skyWebView.layout(0, 0, 1080, 1920);
            WebSettings ss = skyWebView.getSettings();
            ss.setJavaScriptEnabled(true);
            ss.setDomStorageEnabled(true);
            ss.setDatabaseEnabled(true);
            ss.setAllowFileAccess(true);
            ss.setAllowContentAccess(true);
            CookieManager.getInstance().setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ss.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
                CookieManager.getInstance().setAcceptThirdPartyCookies(skyWebView, true);
            }
            ss.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            final boolean[] completed = {false};
            final Runnable finishCleanup = () -> {
                try {
                    if (instance != null) instance.detachSkyCaptchaView(skyWebView);
                    skyWebView.stopLoading();
                    skyWebView.destroy();
                } catch (Exception e) {}
            };

            final Object skyBridge = new Object() {
                @JavascriptInterface
                public void onSkyResult(final String skyMsg) {
                    if (completed[0]) return;
                    String trimmed = skyMsg != null ? skyMsg.trim() : "";
                    if (trimmed.isEmpty() || isWaitingText(trimmed)) return;
                    String cleanPhone = phone != null ? phone.replaceAll("[^0-9]", "") : "";
                    if (!cleanPhone.isEmpty() && cleanPhone.equals(trimmed.replaceAll("[^0-9]", ""))) return;
                    if (trimmed.matches("^0?5\\d{8}$")) return;

                    completed[0] = true;
                    boolean isFail = trimmed.contains("فشل") || trimmed.contains("قبل قليل") || trimmed.contains("غير صحيح") || trimmed.contains("غير صالح") || trimmed.contains("خطأ");
                    if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, trimmed);
                    if (instance != null) {
                        instance.detachSkyCaptchaView(skyWebView);
                        instance.updateUiMessage(isFail ? "warning" : "success", trimmed);
                    }
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "نتيجة سكاي (" + phone + "): " + trimmed, Toast.LENGTH_LONG).show());
                    new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
                }

                @JavascriptInterface
                public void onCaptchaRequired() {
                    if (completed[0]) return;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (instance != null) instance.attachSkyCaptchaView(skyWebView);
                        else {
                            Intent openAct = new Intent(ctx, MainActivity.class);
                            openAct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(openAct);
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                if (instance != null) instance.attachSkyCaptchaView(skyWebView);
                            }, 800);
                        }
                    });
                }
            };

            skyWebView.addJavascriptInterface(skyBridge, "SkyBridge");
            skyWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                    if (message != null && !isWaitingText(message.trim()) && !completed[0]) {
                        completed[0] = true;
                        String t = message.trim();
                        boolean isFail = t.contains("فشل") || t.contains("قبل قليل") || t.contains("غير صحيح") || t.contains("خطأ");
                        if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, t);
                        if (instance != null) {
                            instance.detachSkyCaptchaView(skyWebView);
                            instance.updateUiMessage(isFail ? "warning" : "success", t);
                        }
                        new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
                    }
                    result.confirm();
                    return true;
                }
                @Override
                public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                    result.confirm();
                    return true;
                }
            });

            skyWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) { handler.proceed(); }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (completed[0]) return;
                    String js = "javascript:(function(){"
                        + "var P='" + phone + "';"
                        + "function isW(s){if(!s)return true;var t=String(s).trim();if(t.length<=2||t.indexOf(P)!==-1)return true;"
                        + "if(/(قبل قليل|فشل|بنجاح|تم تحديث|غير صحيح|غير صالح|لا يمكن)/i.test(t))return false;"
                        + "return /(يستغرق|تعاون|جاري|معالج|لحظ|تحميل|تحديث الرقم|sky telecom|loading|wait|captcha|كابتشا)/i.test(t);}"
                        + "function sendRes(m){if(m&&!isW(m))window.SkyBridge.onSkyResult(String(m).replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim());}"
                        + "window.alert=function(m){sendRes(m);};"
                        + "if(window.Swal&&window.Swal.fire){var of=window.Swal.fire;window.Swal.fire=function(){try{var a=arguments[0];sendRes(typeof a==='string'?a:((a.title||'')+' '+(a.text||a.html||'')));}catch(e){}return of.apply(this,arguments);};}"
                        + "function chk(){var els=document.querySelectorAll('.swal2-popup,.modal-content,.alert,[role=alert],#result,.result,#message,.message,.bg-red-100,.bg-green-100,.text-red-700,.text-green-700');"
                        + "for(var i=0;i<els.length;i++){var txt=(els[i].innerText||'').trim();if(txt&&!isW(txt)){sendRes(txt);return true;}}"
                        + "var bodyTxt=document.body.innerText||'';"
                        + "var m=bodyTxt.match(/(فشل[\\s\\S]{1,100}?\\)|تم تحديث[\\s\\S]{1,80}|لقد قمت بتحديث الرقم[\\s\\S]{1,60})/i);"
                        + "if(m&&m[1]){sendRes(m[1]);return true;}"
                        + "return false;}"
                        + "function hasRealCaptcha(){var cap=document.querySelector('.g-recaptcha,.cf-turnstile,iframe[src*=recaptcha],iframe[src*=turnstile],iframe[src*=hcaptcha]');"
                        + "if(!cap)return false;var r=cap.getBoundingClientRect();return r.width>10&&r.height>10;}"
                        + "var inp=document.querySelector('input[type=tel],input[type=text],input[type=number],input:not([type=hidden])');"
                        + "if(inp){inp.focus();Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(inp,P);"
                        + "inp.dispatchEvent(new Event('input',{bubbles:true}));inp.dispatchEvent(new Event('change',{bubbles:true}));"
                        + "if(hasRealCaptcha()&&!window.__capShown){window.__capShown=true;setTimeout(function(){if(!chk())window.SkyBridge.onCaptchaRequired();},3000);}"
                        + "setTimeout(function(){var bs=Array.from(document.querySelectorAll('button,input[type=submit],a.btn'));"
                        + "var b=bs.find(function(x){return /تحديث|ارسال|إرسال|submit|refresh/i.test(x.innerText||x.value||'');})||bs[0];if(b)b.click();"
                        + "var c=0,tm=setInterval(function(){c++;if(chk()||c>360)clearInterval(tm);if(c===16&&hasRealCaptcha()&&!window.__capShown){window.__capShown=true;window.SkyBridge.onCaptchaRequired();}},500);},700);"
                        + "}else{if(!chk()&&hasRealCaptcha())setTimeout(function(){window.SkyBridge.onCaptchaRequired();},2500);}"
                        + "})();";
                    view.evaluateJavascript(js, null);
                }
            });

            skyWebView.loadUrl(SKY_URL);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!completed[0]) {
                    completed[0] = true;
                    finishCleanup.run();
                }
            }, 200000);
        });
    }

    public static class WebAppInterface {
        private final Context ctx;
        public WebAppInterface(Context ctx) { this.ctx = ctx; }
        @JavascriptInterface
        public void autoRefreshSky(final String phone, final String sender) { runHeadlessSkyRefresh(ctx, phone, sender); }
        @JavascriptInterface
        public void sendSmsReply(final String sender, final String message) {
            sendSmsDirect(ctx, sender, message);
            if (instance != null) instance.updateUiMessage("success", message);
        }
        @JavascriptInterface
        public void setChannelsConfig(boolean enableSms, boolean enableWa) {
            ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).edit().putBoolean("enable_sms", enableSms).putBoolean("enable_whatsapp", enableWa).apply();
        }
        @JavascriptInterface
        public boolean isSmsChannelEnabled() { return ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).getBoolean("enable_sms", true); }
        @JavascriptInterface
        public boolean isWaChannelEnabled() { return ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).getBoolean("enable_whatsapp", true); }
    }

    public static class RefreshService extends Service {
        private WebView bgWebView;
        private boolean isBgLoaded = false;
        private final Queue<String[]> queue = new LinkedList<>();
        private PowerManager.WakeLock wakeLock;
        private static final String CHANNEL_ID = "brand_sim_bg_channel";

        @Override
        public void onCreate() {
            super.onCreate();
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), false);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    bgWebView = new WebView(getApplicationContext());
                    configureWebView(bgWebView, getApplicationContext());
                    bgWebView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            super.onPageFinished(view, url);
                            isBgLoaded = true;
                            drainQueue();
                        }
                    });
                    bgWebView.loadUrl("file:///android_asset/refresh.html");
                } catch (Exception e) {}
            });
        }

        private void startForegroundNotification() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                    nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "BRAND SIM Auto Refresh", NotificationManager.IMPORTANCE_LOW));
                }
                Intent openIntent = new Intent(this, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
                Notification notif = b.setContentTitle("BRAND SIM").setContentText("التحديث التلقائي يعمل بالخلفية").setSmallIcon(android.R.drawable.sym_action_chat).setContentIntent(pi).setOngoing(true).build();
                if (Build.VERSION.SDK_INT >= 34) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(1, notif);
            } catch (Exception e) {}
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), false);
            if (intent != null && intent.hasExtra("sms_target_phone")) {
                acquireTempWakeLock();
                String phone = intent.getStringExtra("sms_target_phone");
                String sender = intent.getStringExtra("sms_sender");
                String mode = intent.getStringExtra("sms_mode");
                synchronized (queue) { queue.add(new String[]{phone != null ? phone : "", sender != null ? sender : "", mode != null ? mode : "refresh"}); }
                drainQueue();
            }
            return START_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            scheduleServiceRestart(getApplicationContext());
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            scheduleServiceRestart(getApplicationContext());
            super.onDestroy();
        }

        private void acquireTempWakeLock() {
            try {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrandSim::SmsWakeLock");
                }
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(240000);
            } catch (Exception e) {}
        }

        private void drainQueue() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isBgLoaded || bgWebView == null) return;
                synchronized (queue) {
                    while (!queue.isEmpty()) {
                        String[] item = queue.poll();
                        if (item != null) {
                            String phone = item[0].replace("'", "");
                            String sender = item[1].replace("'", "");
                            String mode = item[2].replace("'", "");
                            bgWebView.evaluateJavascript("javascript:if(window.executeSmsAutoRefresh){window.executeSmsAutoRefresh('" + phone + "','" + sender + "','" + mode + "');}", null);
                        }
                    }
                }
            });
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    public static class WhatsAppListener extends NotificationListenerService {
        public static WhatsAppListener instance;
        public static volatile boolean isConnected = false;
        private static String lastWaKey = "";
        private static long lastWaTime = 0;

        @Override
        public void onListenerConnected() { super.onListenerConnected(); instance = this; isConnected = true; }

        @Override
        public void onListenerDisconnected() {
            super.onListenerDisconnected();
            isConnected = false;
            if (instance == this) instance = null;
            ensureWaListenerAlive(getApplicationContext(), true);
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (sbn == null) return;
            isConnected = true;
            instance = this;
            try {
                if (!getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).getBoolean("enable_whatsapp", true)) return;
                String pkg = sbn.getPackageName();
                if (!"com.whatsapp.w4b".equals(pkg) && !"com.whatsapp".equals(pkg)) return;
                Notification notif = sbn.getNotification();
                if (notif == null || notif.extras == null || (notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

                CharSequence titleCs = notif.extras.getCharSequence(Notification.EXTRA_TITLE);
                String title = titleCs != null ? titleCs.toString().trim() : "";
                String body = "";
                CharSequence[] lines = notif.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0 && lines[lines.length - 1] != null) body = lines[lines.length - 1].toString().trim();
                if (body.isEmpty()) {
                    CharSequence textCs = notif.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (textCs != null) body = textCs.toString().trim();
                }
                if (body.isEmpty() || isIgnoredSystemSms(body)) return;

                WhatsAppReplyAction replyAction = extractReplyAction(notif, sbn.getKey());
                if (replyAction == null) return;

                boolean isDetails = isDetailsRequestSms(body);
                String extractedPhone = extractCleanPhone(body, isDetails);
                String senderPhoneFromTitle = extractCleanPhone(title, true);
                if (extractedPhone == null && isDetails && senderPhoneFromTitle != null) extractedPhone = "";

                if (extractedPhone != null) {
                    String mode = isDetails ? "details" : "refresh";
                    String dedupKey = "WA:" + mode + ":" + extractedPhone + ":" + title + ":" + body;
                    long now = SystemClock.elapsedRealtime();
                    if (dedupKey.equals(lastWaKey) && (now - lastWaTime) < 12000) return;
                    lastWaKey = dedupKey;
                    lastWaTime = now;
                    String senderKey = senderPhoneFromTitle != null ? (senderPhoneFromTitle + "_wa_" + Math.abs(title.hashCode())) : ("wa_" + Math.abs(title.hashCode()));
                    waReplyMap.put(senderKey, replyAction);
                    startBackgroundRefreshService(getApplicationContext(), extractedPhone, senderKey, mode);
                }
            } catch (Exception e) {}
        }

        private WhatsAppReplyAction extractReplyAction(Notification notif, String sbnKey) {
            try {
                if (notif.actions != null) {
                    for (Notification.Action a : notif.actions) {
                        if (a != null && a.getRemoteInputs() != null && a.getRemoteInputs().length > 0 && a.actionIntent != null) {
                            return new WhatsAppReplyAction(a.actionIntent, a.getRemoteInputs(), sbnKey);
                        }
                    }
                }
                for (Notification.Action a : new Notification.WearableExtender(notif).getActions()) {
                    if (a != null && a.getRemoteInputs() != null && a.getRemoteInputs().length > 0 && a.actionIntent != null) {
                        return new WhatsAppReplyAction(a.actionIntent, a.getRemoteInputs(), sbnKey);
                    }
                }
            } catch (Exception e) {}
            return null;
        }
    }

    public static class SmsReceiver extends BroadcastReceiver {
        private static String lastProcessedKey = "";
        private static long lastProcessedTime = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            try {
                String action = intent.getAction();
                if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || "com.brandsim.refresh.RESTART_SERVICE".equals(action)) {
                    ensureWaListenerAlive(context, true);
                    startBackgroundRefreshService(context, null, null, null);
                    return;
                }
                if (!context.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).getBoolean("enable_sms", true)) return;
                if (!"android.provider.Telephony.SMS_RECEIVED".equals(action)) return;
                Bundle bundle = intent.getExtras();
                if (bundle == null) return;
                Object[] pdus = (Object[]) bundle.get("pdus");
                String format = bundle.getString("format");
                if (pdus == null || pdus.length == 0) return;

                StringBuilder fullMsg = new StringBuilder();
                String sender = "";
                for (Object pdu : pdus) {
                    SmsMessage sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? SmsMessage.createFromPdu((byte[]) pdu, format) : SmsMessage.createFromPdu((byte[]) pdu);
                    if (sms != null) {
                        if (sender.isEmpty() && sms.getOriginatingAddress() != null) sender = sms.getOriginatingAddress();
                        if (sms.getMessageBody() != null) fullMsg.append(sms.getMessageBody());
                    }
                }
                String body = fullMsg.toString();
                if (isIgnoredSystemSms(body)) return;

                if (body.contains("SKY_WEB:")) {
                    String skyPhone = extractCleanPhone(body, false);
                    if (skyPhone != null && !skyPhone.isEmpty()) {
                        long now = SystemClock.elapsedRealtime();
                        String dedupKey = "SKY_WEB:" + skyPhone;
                        if (dedupKey.equals(lastProcessedKey) && (now - lastProcessedTime) < 12000) return;
                        lastProcessedKey = dedupKey;
                        lastProcessedTime = now;
                        runHeadlessSkyRefresh(context.getApplicationContext(), skyPhone, skyPhone);
                        return;
                    }
                }

                boolean isDetails = isDetailsRequestSms(body);
                String extractedPhone = extractCleanPhone(body, isDetails);
                if (extractedPhone == null && isDetails && sender != null && !sender.isEmpty()) extractedPhone = "";
                if (extractedPhone != null) {
                    String mode = isDetails ? "details" : "refresh";
                    String dedupKey = mode + ":" + extractedPhone + ":" + sender;
                    long now = SystemClock.elapsedRealtime();
                    if (dedupKey.equals(lastProcessedKey) && (now - lastProcessedTime) < 12000) return;
                    lastProcessedKey = dedupKey;
                    lastProcessedTime = now;
                    String replyTarget = (sender == null || sender.contains("XONE") || !sender.matches("^[+0-9]+$")) ? extractedPhone : sender;
                    startBackgroundRefreshService(context, extractedPhone, replyTarget, mode);
                }
            } catch (Exception e) {}
        }
    }

    @Override
    public void onBackPressed() {
        if (mainWebView != null && mainWebView.canGoBack()) mainWebView.goBack();
        else moveTaskToBack(true);
    }
}
