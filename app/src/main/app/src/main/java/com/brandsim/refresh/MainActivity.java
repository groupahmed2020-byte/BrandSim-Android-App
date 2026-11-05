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
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
                if (phone != null && !phone.isEmpty()) {
                    runHeadlessSkyRefresh(this, phone, null);
                }
            }
            setIntent(new Intent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handleIncomingIntent(getIntent());

        if (hasRuntimePermissions() && !isNotificationListenerEnabled(this)) {
            checkSpecialPermissions();
        } else {
            ensureWaListenerAlive(this, false);
        }
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(cn);
            }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
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
                if (skyWv.getParent() != null) {
                    ((ViewGroup) skyWv.getParent()).removeView(skyWv);
                }
                rootLayout.addView(skyWv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                skyWv.bringToFront();
                Toast.makeText(this, "⚠️ يرجى تخطي الكابتشا لإكمال تحديث رقم سكاي تلقائياً", Toast.LENGTH_LONG).show();
            } catch (Exception e) {}
        });
    }

    public void detachSkyCaptchaView(final WebView skyWv) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (rootLayout != null && skyWv != null && skyWv.getParent() == rootLayout) {
                    rootLayout.removeView(skyWv);
                }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(srv);
            } else {
                context.getApplicationContext().startService(srv);
            }
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
            String pkgName = ctx.getPackageName();
            final String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(pkgName);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startPermissionSetupFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_SMS);
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.SEND_SMS);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.POST_NOTIFICATIONS");
            }
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
        if (requestCode == 101) {
            checkSpecialPermissions();
        }
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

        if (!isNotificationListenerEnabled(this)) {
            promptForNotificationAccess();
        } else {
            ensureWaListenerAlive(this, true);
        }
    }

    private void promptForNotificationAccess() {
        new AlertDialog.Builder(this)
            .setTitle("تفعيل الرد التلقائي لواتساب")
            .setMessage("يحتاج تطبيق BRAND SIM لإذن قراءة الإشعارات للرد التلقائي على طلبات التنشيط عبر واتساب.\n\nاضغط 'متابعة' ثم فعّل المفتاح بجانب BRAND SIM.")
            .setPositiveButton("متابعة", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    try {
                        Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                        startActivity(fallback);
                    } catch (Exception ignored) {}
                }
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
        if (rawText == null) return false;
        return Pattern.compile("(تفاصيل|تفصيل|تاريخ|انتهاء|إنتهاء|تنتهي|ينتهي|بتنتهي|بنتهي|حزمة|حزمه|باقة|باقه|متى|موعد|استعلام|إستعلام|صلاحية|صلاحيه|اشتراك|إشتراك|بيانات|معلومات)").matcher(rawText).find();
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
        String firstCandidate = null;
        while (m.find()) {
            String candidate = "0" + m.group(1);
            if (firstCandidate == null) firstCandidate = candidate;
            if (!candidate.startsWith("059") && !candidate.startsWith("056")) {
                return candidate;
            }
        }
        return firstCandidate;
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
                for (RemoteInput remoteIn : waAction.remoteInputs) {
                    localBundle.putCharSequence(remoteIn.getResultKey(), cleanReply);
                }
                RemoteInput.addResultsToIntent(waAction.remoteInputs, localIntent, localBundle);
                waAction.pendingIntent.send(ctx, 0, localIntent);
                if (WhatsAppListener.instance != null && waAction.sbnKey != null) {
                    try { WhatsAppListener.instance.cancelNotification(waAction.sbnKey); } catch (Exception ignored) {}
                }
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم الرد عبر واتساب بنجاح", Toast.LENGTH_SHORT).show());
                return;
            } catch (Exception e) {
                return;
            }
        }

        if (sender.contains("_wa_") || sender.startsWith("wa_")) return;
        if (!sender.matches("^[+0-9]{7,15}$")) return;

        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(cleanReply);
            sms.sendMultipartTextMessage(sender, null, parts, null, null);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم إرسال رد SMS للزبون: " + sender, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {}
    }

    public static boolean isWaitingText(String text) {
        if (text == null) return true;
        String t = text.replaceAll("[\u064B-\u065F\u0670]", "")
                       .replaceAll("[إأآا]", "ا")
                       .replace('ى', 'ي')
                       .replace('ة', 'ه')
                       .replaceAll("[\\s\\-_.,!؟:()]+", " ")
                       .toLowerCase().trim();
        if (t.length() <= 2) return true;
        String numOnly = t.replaceAll("[^0-9]", "");
        if (numOnly.length() >= 9 && numOnly.length() <= 12 && t.length() <= 15) return true;
        if (t.equals("تحديث") || t.equals("تحديث الرقم") || t.equals("ارسال") || t.equals("بحث") || t.equals("sky telecom")) return true;
        String[] waitKeywords = {
            "يستغرق", "استغراق", "دقيق", "دقايق", "تعاون", "انتظار", "انتظر", 
            "جاري", "معالج", "لحظ", "يرجي", "رجاء", "تحميل",
            "loading", "please wait", "wait", "processing", "captcha", "كابتشا", "روبوت"
        };
        for (String kw : waitKeywords) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(ctx, "🔄 جاري تحديث رقم سكاي بالخلفية: " + phone, Toast.LENGTH_SHORT).show();
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

                    String cleanPhoneOnly = phone != null ? phone.replaceAll("[^0-9]", "") : "";
                    String cleanMsgOnly = trimmed.replaceAll("[^0-9]", "");
                    if (!cleanPhoneOnly.isEmpty() && cleanPhoneOnly.equals(cleanMsgOnly)) return;
                    if (trimmed.matches("^0?5\\d{8}$")) return;

                    completed[0] = true;
                    final String finalMsg = trimmed;
                    if (sender != null && !sender.trim().isEmpty()) {
                        sendSmsDirect(ctx, sender, finalMsg);
                    }
                    if (instance != null) {
                        instance.detachSkyCaptchaView(skyWebView);
                        instance.updateUiMessage("success", finalMsg);
                    }
                    new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(ctx, "✅ اكتمل تحديث سكاي (" + phone + "): " + finalMsg, Toast.LENGTH_LONG).show()
                    );
                    new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
                }

                @JavascriptInterface
                public void onCaptchaRequired() {
                    if (completed[0]) return;
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (instance != null) {
                            instance.attachSkyCaptchaView(skyWebView);
                        } else {
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
                    if (message != null && !message.trim().isEmpty()) {
                        String t = message.trim();
                        if (!isWaitingText(t)) {
                            if (!completed[0]) {
                                completed[0] = true;
                                if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, t);
                                if (instance != null) {
                                    instance.detachSkyCaptchaView(skyWebView);
                                    instance.updateUiMessage("success", t);
                                }
                                new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
                            }
                        }
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
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (completed[0]) return;
                    StringBuilder sb = new StringBuilder();
                    sb.append("javascript:(function(){");
                    sb.append("window.__skyTargetPhone='").append(phone).append("';");
                    sb.append("function isWaitOrStatic(s){");
                    sb.append("  if(!s)return true;");
                    sb.append("  var str=String(s).replace(/[\\u064B-\\u065F\\u0670]/g,'').replace(/[إأآا]/g,'ا').replace(/ى/g,'ي').replace(/ة/g,'ه').replace(/[\\s\\-_.,!؟:()]+/g,' ').toLowerCase().trim();");
                    sb.append("  if(str.length<=2)return true;");
                    sb.append("  var numOnly=str.replace(/[^0-9]/g,'');");
                    sb.append("  if(numOnly===(window.__skyTargetPhone||'').replace(/[^0-9]/g,'')||(numOnly.length>=9&&numOnly.length<=12&&str.length<=15))return true;");
                    sb.append("  if(str==='تحديث'||str==='تحديث الرقم'||str==='ارسال'||str==='إرسال'||str==='بحث'||str==='sky telecom')return true;");
                    sb.append("  var waitKeywords=['يستغرق','استغراق','دقيق','دقايق','تعاون','انتظار','انتظر','جاري','معالج','لحظ','يرجي','رجاء','تحميل','loading','please wait','wait','processing','captcha','كابتشا'];");
                    sb.append("  for(var i=0;i<waitKeywords.length;i++){if(str.indexOf(waitKeywords[i])!==-1)return true;}");
                    sb.append("  return false;");
                    sb.append("}");
                    sb.append("function parseSkyResp(txt){");
                    sb.append("  if(!txt)return '';");
                    sb.append("  try{");
                    sb.append("    var j=JSON.parse(txt);");
                    sb.append("    var cand=typeof j==='string'?j:(j.message||j.Message||j.msg||j.Msg||j.result||j.title||j.error||j.ErrorMessage||j.data||'');");
                    sb.append("    if(typeof cand==='string'&&!isWaitOrStatic(cand))return cand.trim();");
                    sb.append("  }catch(e){}");
                    sb.append("  var s=String(txt).replace(/^[\\\"\\s]+|[\\\"\\s]+$/g,'').trim();");
                    sb.append("  return (s.length>2&&s.length<300&&!s.startsWith('<')&&!isWaitOrStatic(s))?s:'';");
                    sb.append("}");
                    sb.append("window.alert=function(m){if(m&&!isWaitOrStatic(m))window.SkyBridge.onSkyResult(String(m).trim());};");
                    sb.append("if(window.Swal&&window.Swal.fire){var oldFire=window.Swal.fire;window.Swal.fire=function(){try{var a=arguments[0];var txt=typeof a==='string'?a:(a?(a.title||'')+' '+(a.text||a.html||''):'');if(txt&&!isWaitOrStatic(txt)){var cleanTxt=txt.replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim();window.SkyBridge.onSkyResult(cleanTxt);}}catch(e){}return oldFire.apply(this,arguments);};}");
                    sb.append("if(window.swal){var oldSwal=window.swal;window.swal=function(){try{var a=arguments[0];var txt=typeof a==='string'?a:(a?(a.title||'')+' '+(a.text||''):'');if(txt&&!isWaitOrStatic(txt)){var cleanTxt=txt.replace(/<[^>]*>/g,' ').replace(/\\s+/g,' ').trim();window.SkyBridge.onSkyResult(cleanTxt);}}catch(e){}return oldSwal.apply(this,arguments);};}");
                    sb.append("if(window.fetch&&!window.__fetchHooked){window.__fetchHooked=true;var origFetch=window.fetch;window.fetch=function(){return origFetch.apply(this,arguments).then(function(r){try{r.clone().text().then(function(t){var m=parseSkyResp(t);if(m)window.SkyBridge.onSkyResult(m);});}catch(e){}return r;});};}");
                    sb.append("if(window.XMLHttpRequest&&!window.__xhrHooked){window.__xhrHooked=true;var origSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){this.addEventListener('loa