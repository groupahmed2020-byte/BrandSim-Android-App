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

        mainWebView = new WebView(this);
        setContentView(mainWebView);
        configureWebView(mainWebView, this);
        mainWebView.loadUrl("file:///android_asset/refresh.html");

        startPermissionSetupFlow();
        ensureWaListenerAlive(this, true);
        startBackgroundRefreshService(this, null, null, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    // دالة الإنعاش التلقائي لخدمة إشعارات واتساب (تحاكي إيقاف وتشغيل المفتاح برمجياً)
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
            "loading", "please wait", "wait", "processing"
        };
        for (String kw : waitKeywords) {
            if (t.contains(kw)) return true;
        }
        return false;
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender) {
        new Handler(Looper.getMainLooper()).post(() -> {
            final WebView skyWebView = new WebView(ctx.getApplicationContext());
            skyWebView.layout(0, 0, 1080, 1920);
            WebSettings ss = skyWebView.getSettings();
            ss.setJavaScriptEnabled(true);
            ss.setDomStorageEnabled(true);
            ss.setDatabaseEnabled(true);
            ss.setAllowFileAccess(true);
            ss.setAllowContentAccess(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ss.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            ss.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

            final boolean[] completed = {false};
            final Runnable finishCleanup = () -> {
                try {
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
                        instance.updateUiMessage("success", finalMsg);
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
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
                                if (instance != null) instance.updateUiMessage("success", t);
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
                    sb.append("  var waitKeywords=['يستغرق','استغراق','دقيق','دقايق','تعاون','انتظار','انتظر','جاري','معالج','لحظ','يرجي','رجاء','تحميل','loading','please wait','wait','processing'];");
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
                    sb.append("if(window.XMLHttpRequest&&!window.__xhrHooked){window.__xhrHooked=true;var origSend=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{var m=parseSkyResp(this.responseText);if(m)window.SkyBridge.onSkyResult(m);}catch(e){}});return origSend.apply(this,arguments);};}");
                    sb.append("function checkDomResult(){");
                    sb.append("  var popups=document.querySelectorAll('.swal2-popup,.modal-content,.modal-dialog,.alert,[role=\\'alert\\'],#result,.result,#message,.message');");
                    sb.append("  for(var i=0;i<popups.length;i++){");
                    sb.append("    var p=popups[i];");
                    sb.append("    if(p.offsetParent===null&&p.offsetWidth===0&&p.offsetHeight===0)continue;");
                    sb.append("    var fullText=(p.innerText||p.textContent||'').trim();");
                    sb.append("    if(!fullText||isWaitOrStatic(fullText))continue;");
                    sb.append("    var lines=fullText.split(String.fromCharCode(10)).map(function(x){return x.trim();}).filter(function(x){return x.length>2&&!isWaitOrStatic(x)&&x!=='OK'&&x!=='موافق'&&x!=='إلغاء'&&x!=='الغاء';});");
                    sb.append("    if(lines.length>0){window.SkyBridge.onSkyResult(lines.join(' - '));return true;}");
                    sb.append("  }");
                    sb.append("  return false;");
                    sb.append("}");
                    sb.append("try{var mo=new MutationObserver(function(){checkDomResult();});mo.observe(document.body||document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}");
                    sb.append("var inp=document.querySelector('input[type=\\'tel\\'],input[type=\\'text\\'],input[type=\\'number\\'],input:not([type=\\'hidden\\'])');");
                    sb.append("if(inp){");
                    sb.append("  var initialLines=(document.body.innerText||'').split(String.fromCharCode(10)).map(function(x){return x.trim();}).filter(Boolean);");
                    sb.append("  inp.focus();");
                    sb.append("  Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(inp,window.__skyTargetPhone);");
                    sb.append("  inp.dispatchEvent(new Event('input',{bubbles:true}));");
                    sb.append("  inp.dispatchEvent(new Event('change',{bubbles:true}));");
                    sb.append("  setTimeout(function(){");
                    sb.append("    var btns=Array.from(document.querySelectorAll('button, input[type=\\'submit\\'], a.btn, div.btn'));");
                    sb.append("    var submitBtn=btns.find(function(b){return /تحديث|ارسال|إرسال|submit|refresh/i.test(b.innerText||b.value||'');})||btns[0];");
                    sb.append("    if(submitBtn)submitBtn.click();");
                    sb.append("    var checkCount=0,msgTimer=setInterval(function(){");
                    sb.append("      checkCount++;");
                    sb.append("      if(checkDomResult()){clearInterval(msgTimer);return;}");
                    sb.append("      var lines=(document.body.innerText||'').split(String.fromCharCode(10)).map(function(x){return x.trim();}).filter(function(x){return x.length>2&&initialLines.indexOf(x)===-1&&!isWaitOrStatic(x);});");
                    sb.append("      if(lines.length>0){clearInterval(msgTimer);window.SkyBridge.onSkyResult(lines[lines.length-1]);return;}");
                    sb.append("      if(checkCount>400){clearInterval(msgTimer);}");
                    sb.append("    },500);");
                    sb.append("  },600);");
                    sb.append("}else{checkDomResult();}");
                    sb.append("})();");

                    view.evaluateJavascript(sb.toString(), null);
                }
            });

            skyWebView.loadUrl(SKY_URL);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!completed[0]) {
                    completed[0] = true;
                    skyWebView.evaluateJavascript("(function(){ return (document.body ? document.body.innerText : ''); })();", new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String val) {
                            String finalRealMsg = "";
                            if (val != null && !val.equals("null")) {
                                String unquoted = val.replaceAll("^\"|\"$", "").replace("\\n", "\n").replace("\\\"", "\"").trim();
                                for (String line : unquoted.split("\n")) {
                                    String l = line.trim();
                                    if (!isWaitingText(l) && !l.equals(phone) && l.length() > 3 && !l.toLowerCase().contains("sky telecom") && !l.contains("تحديث الرقم")) {
                                        finalRealMsg = l;
                                    }
                                }
                            }
                            String sendText = !finalRealMsg.isEmpty() ? finalRealMsg : "تم إرسال طلب التحديث لبوابة سكاي";
                            if (sender != null && !sender.trim().isEmpty()) {
                                sendSmsDirect(ctx, sender, sendText);
                            }
                            if (instance != null) {
                                instance.updateUiMessage("success", sendText);
                            }
                            finishCleanup.run();
                        }
                    });
                }
            }, 210000);
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
        private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
        private static final String CHANNEL_ID = "brand_sim_bg_channel";

        private final Runnable waWatchdog = new Runnable() {
            @Override
            public void run() {
                ensureWaListenerAlive(getApplicationContext(), false);
                watchdogHandler.postDelayed(this, 120000); // فحص كل دقيقتين
            }
        };

        @Override
        public void onCreate() {
            super.onCreate();
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), false);
            watchdogHandler.postDelayed(waWatchdog, 60000);

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
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            watchdogHandler.removeCallbacksAndMessages(null);
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
        public void onListenerConnected() {
            super.onListenerConnected();
            instance = this;
            isConnected = true;
        }

        @Override
        public void onListenerDisconnected() {
            super.onListenerDisconnected();
            isConnected = false;
            if (instance == this) instance = null;
            ensureWaListenerAlive(getApplicationContext(), true);
        }

        @Override
        public void onDestroy() {
            isConnected = false;
            if (instance == this) instance = null;
            super.onDestroy();
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (sbn == null) return;
            isConnected = true;
            instance = this;
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
                String title = titleCs != null ? titleCs.toString().trim() : "";

                String body = "";
                CharSequence[] textLines = notif.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (textLines != null && textLines.length > 0) {
                    CharSequence lastLine = textLines[textLines.length - 1];
                    if (lastLine != null) body = lastLine.toString().trim();
                }
                if (body.isEmpty()) {
                    CharSequence textCs = notif.extras.getCharSequence(Notification.EXTRA_TEXT);
                    if (textCs != null) body = textCs.toString().trim();
                }
                if (body.isEmpty()) {
                    CharSequence bigTextCs = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    if (bigTextCs != null) {
                        String[] lines = bigTextCs.toString().trim().split("\n");
                        body = lines[lines.length - 1].trim();
                    }
                }

                if (body.isEmpty() || isIgnoredSystemSms(body)) return;

                WhatsAppReplyAction replyAction = extractReplyAction(notif, sbn.getKey());
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
                    startBackgroundRefreshService(getApplicationContext(), extractedPhone, senderKey, mode);
                }
            } catch (Exception e) {}
        }

        private WhatsAppReplyAction extractReplyAction(Notification notif, String sbnKey) {
            try {
                if (notif.actions != null) {
                    for (Notification.Action action : notif.actions) {
                        if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0 && action.actionIntent != null) {
                            return new WhatsAppReplyAction(action.actionIntent, action.getRemoteInputs(), sbnKey);
                        }
                    }
                }
                Notification.WearableExtender wearable = new Notification.WearableExtender(notif);
                for (Notification.Action action : wearable.getActions()) {
                    if (action != null && action.getRemoteInputs() != null && action.getRemoteInputs().length > 0 && action.actionIntent != null) {
                        return new WhatsAppReplyAction(action.actionIntent, action.getRemoteInputs(), sbnKey);
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

                SharedPreferences sp = context.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
                if (!sp.getBoolean("enable_sms", true)) {
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
