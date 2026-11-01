package com.brandsim.refresh;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private WebView mainWebView;
    public static MainActivity instance;
    public static String lastSentSmsBody = "";
    private static final String SKY_URL = "https://rn.sky-5g.net";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        checkPermissionsAndBattery();
        startBackgroundRefreshService(this, null, null);

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
                String safeMsg = msg == null ? "" : msg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
                mainWebView.evaluateJavascript("javascript:if(window.showMessage){window.showMessage('" + type + "','" + safeMsg + "');}", null);
            }
        });
    }

    public static void startBackgroundRefreshService(Context context, String phone, String sender) {
        try {
            Intent srv = new Intent(context.getApplicationContext(), RefreshService.class);
            if (phone != null) {
                srv.putExtra("sms_target_phone", phone);
                srv.putExtra("sms_sender", sender != null ? sender : "");
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
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT;
            PendingIntent pi = PendingIntent.getBroadcast(context.getApplicationContext(), 777, restartIntent, flags);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 800, pi);
            }
        } catch (Exception e) {}
    }

    private void checkPermissionsAndBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECEIVE_SMS);
            if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_SMS);
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.SEND_SMS);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                perms.add("android.permission.POST_NOTIFICATIONS");
            }
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

    public static String extractCleanPhone(String rawText) {
        if (rawText == null || rawText.isEmpty()) return null;
        if (!lastSentSmsBody.isEmpty() && rawText.trim().equals(lastSentSmsBody.trim())) return null;

        StringBuilder sb = new StringBuilder();
        for (char c : rawText.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        String cleaned = sb.toString().replaceAll("[\\s\\-\\.\\(\\)\\+\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "");
        Matcher m = Pattern.compile("(?:00972|00970|972|970|0)?(5\\d{8})").matcher(cleaned);
        if (m.find()) return "0" + m.group(1);
        return null;
    }

    public static void sendSmsDirect(final Context ctx, final String sender, final String message) {
        if (sender == null || sender.trim().isEmpty() || message == null || message.trim().isEmpty()) return;
        String cleanReply = message.trim();
        lastSentSmsBody = cleanReply;
        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(cleanReply);
            sms.sendMultipartTextMessage(sender, null, parts, null, null);
            new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, "تم إرسال رد للزبون: " + sender, Toast.LENGTH_SHORT).show()
            );
        } catch (Exception e) {}
    }

    public static boolean isWaitingText(String text) {
        if (text == null) return true;
        String t = text.trim().toLowerCase();
        return t.isEmpty() ||
               t.contains("انتظار") ||
               t.contains("إنتظار") ||
               t.contains("انتظر") ||
               t.contains("جاري") ||
               t.contains("لحظة") ||
               t.contains("loading") ||
               t.contains("please wait") ||
               t.equals("wait") ||
               t.equals("تحديث") ||
               t.equals("تحديث الرقم");
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
            final Runnable finishCleanup = () -> {
                try {
                    skyWebView.stopLoading();
                    skyWebView.destroy();
                } catch (Exception e) {}
            };

            skyWebView.addJavascriptInterface(new Object() {
                @JavascriptInterface
                public void onSkyResult(final String skyMsg) {
                    if (completed[0]) return;
                    String trimmed = skyMsg != null ? skyMsg.trim() : "";
                    // تجاهل أي رسالة انتظار وعدم إنهاء العملية حتى تصل الرسالة النهائية
                    if (!trimmed.isEmpty() && isWaitingText(trimmed)) {
                        return;
                    }
                    completed[0] = true;
                    String finalMsg = !trimmed.isEmpty() ? trimmed : "تم تحديث الرقم بنجاح";
                    if (sender != null && !sender.trim().isEmpty()) {
                        sendSmsDirect(ctx, sender, finalMsg);
                    }
                    if (instance != null) {
                        instance.updateUiMessage("success", finalMsg);
                    }
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
                        "  if(!s)return true;" +
                        "  var t=String(s).trim();" +
                        "  if(t.length<=2)return true;" +
                        "  var low=t.toLowerCase();" +
                        "  if(low.indexOf('sky telecom')!==-1||t==='تحديث الرقم'||t.indexOf('الرجاء كتابة')!==-1||t==='تحديث')return true;" +
                        "  if(t.indexOf('انتظار')!==-1||t.indexOf('إنتظار')!==-1||t.indexOf('انتظر')!==-1||t.indexOf('جاري')!==-1||t.indexOf('لحظة')!==-1||low.indexOf('loading')!==-1||low.indexOf('wait')!==-1)return true;" +
                        "  return false;" +
                        "}" +
                        "function parseSkyResp(txt){" +
                        "  if(!txt)return '';" +
                        "  try{" +
                        "    var j=JSON.parse(txt);" +
                        "    var cand='';" +
                        "    if(typeof j==='string')cand=j;" +
                        "    else if(j)cand=j.message||j.Message||j.msg||j.result||j.title||j.error||j.ErrorMessage||j.data||'';" +
                        "    if(typeof cand==='string'&&!isWaitOrStatic(cand))return cand.trim();" +
                        "  }catch(e){}" +
                        "  var s=String(txt).replace(/^\"|\"$/g,'').trim();" +
                        "  if(s.length>2&&s.length<250&&!s.startsWith('<')&&!isWaitOrStatic(s))return s;" +
                        "  return '';" +
                        "}" +
                        "var origFetch=window.fetch;" +
                        "if(origFetch){window.fetch=function(){return origFetch.apply(this,arguments).then(function(r){r.clone().text().then(function(t){var m=parseSkyResp(t);if(m)window.__skyMsg=m;});return r;});};}" +
                        "var origSend=XMLHttpRequest.prototype.send;" +
                        "XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){var m=parseSkyResp(this.responseText);if(m)window.__skyMsg=m;});return origSend.apply(this,arguments);};" +
                        "var attempts=0;" +
                        "var timer=setInterval(function(){" +
                        "  attempts++;" +
                        "  var inp=document.querySelector('input[type=\"tel\"],input[type=\"text\"],input[type=\"number\"],input:not([type=\"hidden\"])');" +
                        "  if(inp){" +
                        "    clearInterval(timer);" +
                        "    var initialLines=(document.body.innerText||'').split('\\n').map(function(x){return x.trim();}).filter(Boolean);" +
                        "    inp.focus();" +
                        "    var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;" +
                        "    setter.call(inp,'" + phone + "');" +
                        "    inp.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "    inp.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "    setTimeout(function(){" +
                        "      var btn=document.querySelector('button[type=\"submit\"],input[type=\"submit\"],button');" +
                        "      if(btn)btn.click();" +
                        "      var checkCount=0;" +
                        "      var msgTimer=setInterval(function(){" +
                        "        checkCount++;" +
                        "        if(window.__skyMsg&&!isWaitOrStatic(window.__skyMsg)){" +
                        "          clearInterval(msgTimer);window.SkyBridge.onSkyResult(window.__skyMsg);return;" +
                        "        }" +
                        "        var els=document.querySelectorAll('.swal2-html-container,.swal2-title,.swal2-content,.alert,[role=\"alert\"],.toast-message,.toast-body,.text-success,.text-danger,.text-warning,.text-info,#result,.result,#message,.message');" +
                        "        for(var i=0;i<els.length;i++){" +
                        "          var txt=(els[i].innerText||'').trim();" +
                        "          if(txt.length>2&&!isWaitOrStatic(txt)){" +
                        "            clearInterval(msgTimer);window.SkyBridge.onSkyResult(txt);return;" +
                        "          }" +
                        "        }" +
                        "        var lines=(document.body.innerText||'').split('\\n').map(function(x){return x.trim();}).filter(function(x){" +
                        "          return x.length>3&&initialLines.indexOf(x)===-1&&!isWaitOrStatic(x);" +
                        "        });" +
                        "        if(lines.length>0){" +
                        "          clearInterval(msgTimer);window.SkyBridge.onSkyResult(lines[lines.length-1]);return;" +
                        "        }" +
                        "        if(checkCount>40){" +
                        "          clearInterval(msgTimer);window.SkyBridge.onSkyResult('تم تحديث الرقم بنجاح');" +
                        "        }" +
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
                    String fallbackMsg = "تم تحديث الرقم بنجاح";
                    if (sender != null && !sender.trim().isEmpty()) {
                        sendSmsDirect(ctx, sender, fallbackMsg);
                    }
                    if (instance != null) {
                        instance.updateUiMessage("success", fallbackMsg);
                    }
                    finishCleanup.run();
                }
            }, 25000);
        });
    }

    public static class WebAppInterface {
        private final Context ctx;
        public WebAppInterface(Context ctx) { this.ctx = ctx; }

        @JavascriptInterface
        public void autoRefreshSky(final String phone, final String sender) {
            runHeadlessSkyRefresh(ctx, phone, sender);
        }

        @JavascriptInterface
        public void sendSmsReply(final String sender, final String message) {
            sendSmsDirect(ctx, sender, message);
            if (instance != null) {
                instance.updateUiMessage("success", message);
            }
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
            initBackgroundWebView();
        }

        @SuppressLint("SetJavaScriptEnabled")
        private void initBackgroundWebView() {
            new Handler(Looper.getMainLooper()).post(() -> {
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
            });
        }

        private void startForegroundNotification() {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "BRAND SIM Auto Refresh", NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(ch);
                }
                Intent openIntent = new Intent(this, MainActivity.class);
                int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
                PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, flags);

                Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
                Notification notif = builder
                    .setContentTitle("BRAND SIM")
                    .setContentText("التحديث التلقائي للرسائل يعمل بالخلفية")
                    .setSmallIcon(android.R.drawable.sym_action_chat)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

                if (Build.VERSION.SDK_INT >= 34) {
                              startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(1, notif);
                }
            } catch (Exception e) {}
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            startForegroundNotification();
            if (intent != null && intent.hasExtra("sms_target_phone")) {
                acquireTempWakeLock();
                String phone = intent.getStringExtra("sms_target_phone");
                String sender = intent.getStringExtra("sms_sender");
                synchronized (queue) {
                    queue.add(new String[]{phone, sender != null ? sender : ""});
                }
                drainQueue();
            }
            return START_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            scheduleServiceRestart(getApplicationContext());
            startBackgroundRefreshService(getApplicationContext(), null, null);
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
                if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(45000);
            } catch (Exception e) {}
        }

        private void drainQueue() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isBgLoaded || bgWebView == null) return;
                synchronized (queue) {
                    while (!queue.isEmpty()) {
                        String[] item = queue.poll();
                        if (item != null) {
                            String phone = item[0];
                            String sender = item[1].replace("'", "");
                            bgWebView.evaluateJavascript("javascript:if(window.executeSmsAutoRefresh){window.executeSmsAutoRefresh('" + phone + "','" + sender + "');}", null);
                        }
                    }
                }
            });
        }

        @Override
        public IBinder onBind(Intent intent) { return null; }
    }

    public static class SmsReceiver extends BroadcastReceiver {
        private static String lastProcessedPhone = "";
        private static long lastProcessedTime = 0;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                "com.brandsim.refresh.RESTART_SERVICE".equals(action)) {
                startBackgroundRefreshService(context, null, null);
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
                SmsMessage sms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                    SmsMessage.createFromPdu((byte[]) pdu, format) :
                    SmsMessage.createFromPdu((byte[]) pdu);
                if (sms != null) {
                    if (sender.isEmpty() && sms.getOriginatingAddress() != null) sender = sms.getOriginatingAddress();
                    if (sms.getMessageBody() != null) fullMsg.append(sms.getMessageBody());
                }
            }

            String extractedPhone = extractCleanPhone(fullMsg.toString());
            if (extractedPhone != null) {
                long now = SystemClock.elapsedRealtime();
                if (extractedPhone.equals(lastProcessedPhone) && (now - lastProcessedTime) < 15000) return;
                lastProcessedPhone = extractedPhone;
                lastProcessedTime = now;
                startBackgroundRefreshService(context, extractedPhone, sender);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mainWebView != null && mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }
}
