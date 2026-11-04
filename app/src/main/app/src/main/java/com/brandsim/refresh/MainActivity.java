package com.brandsim.refresh;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.*;
import android.view.Window;
import android.webkit.*;
import android.widget.Toast;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;

public class MainActivity extends Activity {
    private WebView mainWebView;
    public static MainActivity instance;
    public static String lastSentSmsBody = "";
    private static final String SKY_URL = "https://rn.sky-5g.net";

    public static final Map<String, WhatsAppReplyAction> waReplyMap = new ConcurrentHashMap<>();

    public static class WhatsAppReplyAction {
        public final PendingIntent pendingIntent;
        public final RemoteInput[] remoteInputs;
        public WhatsAppReplyAction(PendingIntent pi, RemoteInput[] ri) {
            this.pendingIntent = pi;
            this.remoteInputs = ri;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        checkPermissionsAndBattery();
        startBackgroundRefreshService(this, null, null, null);
        mainWebView = new WebView(this);
        setContentView(mainWebView);
        configureWebView(mainWebView, this);
        mainWebView.loadUrl("file:///android_asset/refresh.html");
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        scheduleServiceRestart(getApplicationContext());
        super.onDestroy();
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void configureWebView(WebView wv, Context ctx) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        wv.addJavascriptInterface(new WebAppInterface(ctx.getApplicationContext()), "AndroidBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient());
    }

    public void updateUiMessage(final String type, final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mainWebView != null) {
                String safeMsg = msg == null ? "" : msg.replace("\", "\\").replace("'", "\'").replace("
", "\n");
                mainWebView.evaluateJavascript("javascript:if(window.showMessage){window.showMessage('" + type + "','" + safeMsg + "');}", null);
            }
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

    public boolean isNotificationListenerEnabled() {
        try {
            String pkgName = getPackageName();
            final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(pkgName);
        } catch (Exception e) {
            return false;
        }
    }

    private void checkPermissionsAndBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_SMS);
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.SEND_SMS);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) perms.add("android.permission.POST_NOTIFICATIONS");
            if (!perms.isEmpty()) requestPermissions(perms.toArray(new String[0]), 101);
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {}
        }
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
        String cleaned = sb.toString().replaceAll("[\s\-\.\(\)\+\u200B-\u200F\u202A-\u202E\u2066-\u2069]", "");
        Matcher m = Pattern.compile("(?:00972|00970|972|970|0)?(5\d{8})").matcher(cleaned);
        while (m.find()) {
            String candidate = "0" + m.group(1);
            if (candidate.startsWith("059") && !allow059ForDetails) continue;
            return candidate;
        }
        return null;
    }

    public static void sendSmsDirect(final Context ctx, final String sender, final String message) {
        if (sender == null || sender.trim().isEmpty() || message == null || message.trim().isEmpty()) return;
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
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم الرد عبر واتساب بنجاح", Toast.LENGTH_SHORT).show());
                return;
            } catch (Exception e) {}
        }

        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(cleanReply);
            sms.sendMultipartTextMessage(sender, null, parts, null, null);
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "تم إرسال رد SMS للزبون: " + sender, Toast.LENGTH_SHORT).show());
        } catch (Exception e) {}
    }

    public static boolean isWaitingText(String text) {
        if (text == null) return true;
        String t = text.trim().toLowerCase();
        return t.isEmpty() || t.contains("انتظار") || t.contains("إنتظار") || t.contains("انتظر") ||
               t.contains("جاري") || t.contains("لحظة") || t.contains("يستغرق") || t.contains("دقيقة") ||
               t.contains("دقائق") || t.contains("لتعاونكم") || t.contains("تعاونكم") ||
               t.contains("loading") || t.contains("please wait") || t.equals("wait") ||
               t.equals("تحديث") || t.equals("تحديث الرقم");
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender) {
        new Handler(Looper.getMainLooper()).post(() -> {
            final WebView skyWebView = new WebView(ctx.getApplicationContext());
            skyWebView.layout(0, 0, 1080, 1920);
            WebSettings ss = skyWebView.getSettings();
            ss.setJavaScriptEnabled(true);
            ss.setDomStorageEnabled(true);
            ss.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            final boolean[] completed = {false};
            final Runnable finishCleanup = () -> { try { skyWebView.stopLoading(); skyWebView.destroy(); } catch (Exception e) {} };

            skyWebView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onSkyResult(final String skyMsg) {
                    if (completed[0]) return;
                    String trimmed = skyMsg != null ? skyMsg.trim() : "";
                    if (!trimmed.isEmpty() && isWaitingText(trimmed)) return;
                    completed[0] = true;
                    String finalMsg = !trimmed.isEmpty() ? trimmed : "تم تحديث الرقم بنجاح";
                    if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, finalMsg);
                    if (instance != null) instance.updateUiMessage("success", finalMsg);
                    new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 1000);
                }
            }, "SkyBridge");

            skyWebView.setWebViewClient(new WebViewClient() {
                private boolean injected = false;
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (injected) return;
                    injected = true;
                    String js = "javascript:(function(){" +
                        "window.__skyMsg='';" +
                        "function isWaitOrStatic(s){" +
                        "  if(!s)return true;var t=String(s).trim();if(t.length<=2)return true;var low=t.toLowerCase();" +
                        "  if(low.indexOf('sky telecom')!==-1||t==='تحديث الرقم'||t.indexOf('الرجاء كتابة')!==-1||t==='تحديث')return true;" +
                        "  if(t.indexOf('انتظار')!==-1||t.indexOf('إنتظار')!==-1||t.indexOf('انتظر')!==-1||t.indexOf('جاري')!==-1||t.indexOf('لحظة')!==-1||t.indexOf('يستغرق')!==-1||t.indexOf('دقيقة')!==-1||t.indexOf('دقائق')!==-1||t.indexOf('لتعاونكم')!==-1||t.indexOf('تعاونكم')!==-1||low.indexOf('loading')!==-1||low.indexOf('wait')!==-1)return true;" +
                        "  return false;" +
                        "}" +
                        "function parseSkyResp(txt){" +
                        "  if(!txt)return '';" +
                        "  try{var j=JSON.parse(txt);var cand=typeof j==='string'?j:(j.message||j.Message||j.msg||j.result||j.title||j.error||j.ErrorMessage||j.data||'');if(typeof cand==='string'&&!isWaitOrStatic(cand))return cand.trim();}catch(e){}" +
                        "  var s=String(txt).replace(/^\"|\"$/g,'').trim();return (s.length>2&&s.length<250&&!s.startsWith('<')&&!isWaitOrStatic(s))?s:'';" +
                        "}" +
                        "var origFetch=window.fetch;if(origFetch){window.fetch=function(){return origFetch.apply(this,arguments).then(function(r){r.clone().text().then(function(t){var m=parseSkyResp(t);if(m)window.__skyMsg=m;});return r;});};}" +
                        "var origSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){var m=parseSkyResp(this.responseText);if(m)window.__skyMsg=m;});return origSend.apply(this,arguments);};" +
                        "var attempts=0,timer=setInterval(function(){" +
                        "  attempts++;var inp=document.querySelector('input[type=\'tel\'],input[type=\'text\'],input[type=\'number\'],input:not([type=\'hidden\'])');" +
                        "  if(inp){" +
                        "    clearInterval(timer);var initialLines=(document.body.innerText||'').split('\n').map(function(x){return x.trim();}).filter(Boolean);" +
                        "    inp.focus();Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(inp,'" + phone + "');" +
                        "    inp.dispatchEvent(new Event('input',{bubbles:true}));inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "    setTimeout(function(){" +
                        "      var btn=document.querySelector('button[type=\'submit\'],input[type=\'submit\'],button');if(btn)btn.click();" +
                        "      var checkCount=0,msgTimer=setInterval(function(){" +
                        "        checkCount++;if(window.__skyMsg&&!isWaitOrStatic(window.__skyMsg)){clearInterval(msgTimer);window.SkyBridge.onSkyResult(window.__skyMsg);return;}" +
                        "        var els=document.querySelectorAll('.swal2-html-container,.swal2-title,.swal2-content,.alert,[role=\'alert\'],.toast-message,.toast-body,.text-success,.text-danger,.text-warning,.text-info,#result,.result,#message,.message');" +
                        "        for(var i=0;i<els.length;i++){var elLines=(els[i].innerText||'').split('\n').map(function(x){return x.trim();}).filter(function(x){return x.length>2&&!isWaitOrStatic(x);});if(elLines.length>0){clearInterval(msgTimer);window.SkyBridge.onSkyResult(elLines[elLines.length-1]);return;}}" +
                        "        var lines=(document.body.innerText||'').split('\n').map(function(x){return x.trim();}).filter(function(x){return x.length>3&&initialLines.indexOf(x)===-1&&!isWaitOrStatic(x);});" +
                        "        if(lines.length>0){clearInterval(msgTimer);window.SkyBridge.onSkyResult(lines[lines.length-1]);return;}" +
                        "        if(checkCount>420){clearInterval(msgTimer);window.SkyBridge.onSkyResult('تم تحديث الرقم بنجاح');}" +
                        "      },500);" +
                        "    },500);" +
                        "  }" +
                        "  if(attempts>15){clearInterval(timer);window.SkyBridge.onSkyResult('تم إرسال طلب التحديث لسكاي');}" +
                        "},400);" +
                        "})();";
                    view.evaluateJavascript(js, null);
                }
            });
            skyWebView.loadUrl(SKY_URL);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!completed[0]) {
                    completed[0] = true;
                    if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, "تم تحديث الرقم بنجاح");
                    if (instance != null) instance.updateUiMessage("success", "تم تحديث الرقم بنجاح");
                    finishCleanup.run();
                }
            }, 220000);
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
            SharedPreferences sp = ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
            sp.edit().putBoolean("enable_sms", enableSms).putBoolean("enable_whatsapp", enableWa).apply();
        }

        @JavascriptInterface
        public boolean isSmsChannelEnabled() {
            SharedPreferences sp = ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
            return sp.getBoolean("enable_sms", true);
        }

        @JavascriptInterface
        public boolean isWaChannelEnabled() {
            SharedPreferences sp = ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
            return sp.getBoolean("enable_whatsapp", true);
        }
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
                Notification notif = b.setContentTitle("BRAND SIM").setContentText("التحديث التلقائي للواتساب والرسائل يعمل بالخلفية").setSmallIcon(android.R.drawable.sym_action_chat).setContentIntent(pi).setOngoing(true).build();
                if (Build.VERSION.SDK_INT >= 34) startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(1, notif);
            } catch (Exception e) {}
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForegroundNotification();
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
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
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
        private static String lastWaKey = "";
        private static long lastWaTime = 0;

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (sbn == null) return;
            try {
                SharedPreferences sp = getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
                if (!sp.getBoolean("enable_whatsapp", true)) {
                    return;
                }

                String pkg = sbn.getPackageName();
                if (!"com.whatsapp.w4b".equals(pkg) && !"com.whatsapp".equals(pkg)) return;

                Notification notif = sbn.getNotification();
                if (notif == null || notif.extras == null) return;

                if ((notif.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

                CharSequence titleCs = notif.extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence textCs = notif.extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence bigTextCs = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                String title = titleCs != null ? titleCs.toString().trim() : "";
                String body = bigTextCs != null ? bigTextCs.toString().trim() : (textCs != null ? textCs.toString().trim() : "");

                if (body.isEmpty() || isIgnoredSystemSms(body)) return;

                WhatsAppReplyAction replyAction = extractReplyAction(notif);
                if (replyAction == null) return;

                boolean isDetails = isDetailsRequestSms(body);
                String extractedPhone = extractCleanPhone(body, isDetails);
                String senderPhoneFromTitle = extractCleanPhone(title, true);

                if (extractedPhone == null && isDetails && senderPhoneFromTitle != null) {
                    extractedPhone = "";
                }

                if (extractedPhone != null) {
                    String mode = isDetails ? "details" : "refresh";
                    String dedupKey = "WA:" + mode + ":" + extractedPhone + ":" + title + ":" + body;
                    long now = SystemClock.elapsedRealtime();
                    if (dedupKey.equals(lastWaKey) && (now - lastWaTime) < 12000) return;
                    lastWaKey = dedupKey;
                    lastWaTime = now;

                    String senderKey;
                    if (senderPhoneFromTitle != null) {
                        senderKey = senderPhoneFromTitle + "_wa_" + Math.abs(title.hashCode());
                    } else {
                        senderKey = "wa_" + Math.abs(title.hashCode());
                    }

                    waReplyMap.put(senderKey, replyAction);
                    cancelNotification(sbn.getKey());
                    startBackgroundRefreshService(getApplicationContext(), extractedPhone, senderKey, mode);
                }
            } catch (Exception e) {}
        }

        private WhatsAppReplyAction extractReplyAction(Notification notif) {
            try {
                if (notif.actions != null) {
                    for (Notification.Action action : notif.actions) {
                        if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0 && action.actionIntent != null) {
                            return new WhatsAppReplyAction(action.actionIntent, action.getRemoteInputs());
                        }
                    }
                }
                Notification.WearableExtender wearable = new Notification.WearableExtender(notif);
                for (Notification.Action action : wearable.getActions()) {
                    if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0 && action.actionIntent != null) {
                        return new WhatsAppReplyAction(action.actionIntent, action.getRemoteInputs());
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
                SharedPreferences sp = context.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
                if (!sp.getBoolean("enable_sms", true)) {
                    return;
                }

                String action = intent.getAction();
                if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || "com.brandsim.refresh.RESTART_SERVICE".equals(action)) {
                    startBackgroundRefreshService(context, null, null, null);
                    return;
                }
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

                boolean isDetails = isDetailsRequestSms(body);
                String extractedPhone = extractCleanPhone(body, isDetails);
                if (extractedPhone == null && isDetails && sender != null && !sender.isEmpty()) {
                    extractedPhone = "";
                }
                if (extractedPhone != null) {
                    String mode = isDetails ? "details" : "refresh";
                    String dedupKey = mode + ":" + extractedPhone + ":" + sender;
                    long now = SystemClock.elapsedRealtime();
                    if (dedupKey.equals(lastProcessedKey) && (now - lastProcessedTime) < 12000) return;
                    lastProcessedKey = dedupKey;
                    lastProcessedTime = now;
                    startBackgroundRefreshService(context, extractedPhone, sender, mode);
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
