package com.brandsim.refresh;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.*;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.*;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.TextView;
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
    private static final long SKY_COOLDOWN_MS = 20L * 60L * 1000L; // 20 دقيقة
    private static boolean webViewDataDirConfigured = false;
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

    public static synchronized void ensureWebViewProcessDir(Context ctx) {
        if (webViewDataDirConfigured) return;
        webViewDataDirConfigured = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String proc = Application.getProcessName();
                if (proc != null && !proc.equals(ctx.getPackageName())) {
                    WebView.setDataDirectorySuffix(proc.replace(":", "_").replace(".", "_"));
                }
            } catch (Exception e) {}
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        ensureWebViewProcessDir(this);
        instance = this;
        rootLayout = new FrameLayout(this);
        mainWebView = new WebView(this);
        rootLayout.addView(mainWebView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        attachChannelsControlButton();
        setContentView(rootLayout);
        configureWebView(mainWebView, this);
        mainWebView.loadUrl("file:///android_asset/refresh.html");
        startPermissionSetupFlow();
        ensureWaListenerAlive(this, true);
        startBackgroundRefreshService(this, null, null, null);
        scheduleServiceRestart(this, 2500);
        handleIncomingIntent(getIntent());
    }

    private void attachChannelsControlButton() {
        try {
            TextView gearBtn = new TextView(this);
            gearBtn.setText("⚙️");
            gearBtn.setTextSize(20f);
            gearBtn.setTextColor(Color.parseColor("#00e5ff"));
            gearBtn.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#CC04121F"));
            bg.setCornerRadius(100f);
            bg.setStroke(3, Color.parseColor("#00e5ff"));
            gearBtn.setBackground(bg);

            float density = getResources().getDisplayMetrics().density;
            int size = (int) (46 * density);
            int margin = (int) (14 * density);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins(margin, margin, margin, margin);
            gearBtn.setLayoutParams(lp);
            gearBtn.setOnClickListener(v -> showChannelsConfigDialog());
            rootLayout.addView(gearBtn);
        } catch (Exception e) {}
    }

    private void showChannelsConfigDialog() {
        final boolean[] checkedItems = new boolean[]{
            isChannelEnabled(this, "enable_sms"),
            isChannelEnabled(this, "enable_whatsapp"),
            isChannelEnabled(this, "enable_telegram")
        };
        String[] items = new String[]{
            "استقبال وتحديث رسائل SMS",
            "استقبال وتحديث إشعارات واتساب (WhatsApp)",
            "استقبال وتحديث إشعارات تيليجرام (Telegram)"
        };

        new AlertDialog.Builder(this)
            .setTitle("إعدادات قنوات الاستقبال والصلاحيات")
            .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                checkedItems[which] = isChecked;
                saveAllChannelsState(this, checkedItems[0], checkedItems[1], checkedItems[2]);
            })
            .setPositiveButton("حفظ وإغلاق", (d, w) -> {
                saveAllChannelsState(this, checkedItems[0], checkedItems[1], checkedItems[2]);
                Toast.makeText(this, "تم حفظ إعدادات القنوات بنجاح", Toast.LENGTH_SHORT).show();
            })
            .setNeutralButton("الإعدادات المقيدة والإشعارات", (d, w) -> {
                showRestrictedSettingsGuideDialog();
            })
            .show();
    }

    public static void saveAllChannelsState(Context ctx, boolean sms, boolean wa, boolean tg) {
        try {
            ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).edit()
                .putBoolean("enable_sms", sms)
                .putBoolean("enable_whatsapp", wa)
                .putBoolean("enable_telegram", tg)
                .commit();
            Intent sync = new Intent(ctx.getApplicationContext(), RefreshService.class);
            sync.putExtra("sync_channels", true);
            sync.putExtra("enable_sms", sms);
            sync.putExtra("enable_whatsapp", wa);
            sync.putExtra("enable_telegram", tg);
            startBackgroundRefreshServiceWithIntent(ctx.getApplicationContext(), sync);
        } catch (Exception e) {}
    }

    public static boolean isChannelEnabled(Context ctx, String key) {
        try {
            return ctx.getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).getBoolean(key, true);
        } catch (Exception e) { return true; }
    }

    private void showRestrictedSettingsGuideDialog() {
        new AlertDialog.Builder(this)
            .setTitle("تفعيل الإعدادات المقيدة والعمل بالخلفية")
            .setMessage("لضمان استمرار عمل التطبيق بالخلفية وتلقي إشعارات (واتساب وتيليجرام) ورسائل SMS حتى بعد إغلاقه من شاشة المهام عدة مرات:\n\n"
                + "1️⃣ اضغط على (فتح معلومات التطبيق) ثم اضغط على النقاط الثلاث ⋮ في أعلى الزاوية واختر (السماح بالإعدادات المقيدة / Allow restricted settings).\n\n"
                + "2️⃣ من داخل معلومات التطبيق، فعّل خيار (التشغيل التلقائي / Autostart) وادخل إلى (البطارية / موفر البطارية) واختر (بلا قيود / لا توجد قيود).\n\n"
                + "3️⃣ بعدها اضغط على (تفعيل وصول الإشعارات) وفعّل خيار BRAND SIM.")
            .setPositiveButton("1. فتح معلومات التطبيق", (d, w) -> {
                openAppDetailsSettings();
            })
            .setNeutralButton("2. تفعيل وصول الإشعارات", (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
            })
            .setNegativeButton("إغلاق", null)
            .show();
    }

    private void openAppDetailsSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "فعّل (التشغيل التلقائي) واجعل البطارية (بلا قيود)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
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
                String phone = extractCleanPhone(data.getQueryParameter("phone"), false);
                if (phone != null && !phone.isEmpty() && !isIgnoredPrefixPhone(phone)) {
                    runHeadlessSkyRefresh(this, phone, null, "refresh");
                }
            }
            setIntent(new Intent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        handleIncomingIntent(getIntent());
        startBackgroundRefreshService(this, null, null, null);
        if (hasRuntimePermissions() && !isNotificationListenerEnabled(this)) checkSpecialPermissions();
        else ensureWaListenerAlive(this, true);
    }

    @Override
    protected void onPause() {
        startBackgroundRefreshService(getApplicationContext(), null, null, null);
        ensureWaListenerAlive(getApplicationContext(), false);
        scheduleServiceRestart(getApplicationContext(), 1500);
        super.onPause();
    }

    @Override
    protected void onStop() {
        startBackgroundRefreshService(getApplicationContext(), null, null, null);
        ensureWaListenerAlive(getApplicationContext(), false);
        scheduleServiceRestart(getApplicationContext(), 1200);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        try {
            if (mainWebView != null) {
                if (rootLayout != null) rootLayout.removeView(mainWebView);
                mainWebView.stopLoading();
                mainWebView.destroy();
                mainWebView = null;
            }
        } catch (Exception e) {}
        startBackgroundRefreshService(getApplicationContext(), null, null, null);
        ensureWaListenerAlive(getApplicationContext(), true);
        scheduleServiceRestart(getApplicationContext(), 1000);
        super.onDestroy();
    }

    private static long lastRebindTime = 0;

    public static void ensureWaListenerAlive(Context ctx, boolean forceToggle) {
        try {
            Context appCtx = ctx.getApplicationContext();
            if (!isNotificationListenerEnabled(appCtx)) return;

            long now = SystemClock.elapsedRealtime();
            if (!forceToggle && WhatsAppListener.isConnected && (now - lastRebindTime) < 15000) return;
            lastRebindTime = now;

            ComponentName cn = new ComponentName(appCtx, WhatsAppListener.class);
            PackageManager pm = appCtx.getPackageManager();
            if (forceToggle || !WhatsAppListener.isConnected) {
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            }
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        wv.addJavascriptInterface(new WebAppInterface(ctx.getApplicationContext()), "AndroidBridge");
        wv.setWebChromeClient(new WebChromeClient());
        wv.setWebViewClient(new WebViewClient());
    }

    public void updateUiMessage(final String type, final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mainWebView != null) {
                String cleanBrandMsg = sanitizeBrandText(msg);
                String safeMsg = cleanBrandMsg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
                mainWebView.evaluateJavascript("javascript:if(window.showMessage){window.showMessage('" + type + "','" + safeMsg + "');}", null);
            }
        });
    }

    public static void startBackgroundRefreshServiceWithIntent(Context appCtx, Intent srv) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    appCtx.startForegroundService(srv);
                } catch (Exception e) {
                    try { appCtx.startService(srv); } catch (Exception ignored) {}
                }
            } else {
                appCtx.startService(srv);
            }
        } catch (Exception e) {}
    }

    public static void startBackgroundRefreshService(Context context, String phone, String sender, String mode) {
        try {
            if (phone != null && !phone.isEmpty() && isIgnoredPrefixPhone(phone)) return;
            Context appCtx = context.getApplicationContext();

            if (phone != null && !phone.isEmpty() && RefreshService.instance != null) {
                RefreshService.instance.enqueuePhoneRequest(phone, sender != null ? sender : "", mode != null ? mode : "refresh");
            }

            Intent srv = new Intent(appCtx, RefreshService.class);
            if (phone != null && !phone.isEmpty()) {
                srv.putExtra("sms_target_phone", phone);
                srv.putExtra("sms_sender", sender != null ? sender : "");
                srv.putExtra("sms_mode", mode != null ? mode : "refresh");
            }
            startBackgroundRefreshServiceWithIntent(appCtx, srv);
        } catch (Exception e) {
            if (phone != null && !phone.isEmpty()) {
                RefreshService.runDirectFallbackRefresh(context.getApplicationContext(), phone, sender != null ? sender : "", mode != null ? mode : "refresh");
            }
        }
    }

    public static void scheduleServiceRestart(Context context) {
        scheduleServiceRestart(context, 1500);
    }

    public static void scheduleServiceRestart(Context context, long delayMs) {
        try {
            Context appCtx = context.getApplicationContext();
            Intent restartIntent = new Intent(appCtx, SmsReceiver.class);
            restartIntent.setAction("com.brandsim.refresh.RESTART_SERVICE");
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(appCtx, 777, restartIntent, flags);
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long triggerAt = SystemClock.elapsedRealtime() + Math.max(800, delayMs);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    } catch (Exception e) {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    }
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            }
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

        SharedPreferences prefs = getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && !isNotificationListenerEnabled(this) && !prefs.getBoolean("restricted_settings_prompted", false)) {
            prefs.edit().putBoolean("restricted_settings_prompted", true).apply();
            showRestrictedSettingsSetupPrompt();
            return;
        }

        if (!isNotificationListenerEnabled(this)) promptForNotificationAccess();
        else ensureWaListenerAlive(this, true);
    }

    private void showRestrictedSettingsSetupPrompt() {
        new AlertDialog.Builder(this)
            .setTitle("السماح بالإعدادات المقيدة")
            .setMessage("في أندرويد 13 فما فوق، لتتمكن من تفعيل قراءة إشعارات واتساب وتيليجرام:\n\n"
                + "1️⃣ سيفتح لك التطبيق الآن صفحة (معلومات التطبيق).\n"
                + "2️⃣ اضغط على النقاط الثلاث (⋮) في أعلى الزاوية واختر (السماح بالإعدادات المقيدة).\n"
                + "3️⃣ ارجع للتطبيق لتفعيل إذن الإشعارات.")
            .setPositiveButton("فتح إعدادات التطبيق الآن", (d, w) -> {
                openAppDetailsSettings();
            })
            .setNeutralButton("صفحة الإشعارات مباشرة", (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
            })
            .setNegativeButton("لاحقاً", null)
            .setCancelable(false)
            .show();
    }

    private void promptForNotificationAccess() {
        new AlertDialog.Builder(this)
            .setTitle("تفعيل التحديث التلقائي (واتساب وتيليجرام)")
            .setMessage("يرجى تفعيل إذن الإشعارات لتطبيق BRAND SIM.\n\n(ملاحظة: إذا كان الخيار باهتاً أو مقيداً، اضغط على زر 'فتح الإعدادات المقيدة' أولاً ثم اضغط على ⋮ في الأعلى واختر السماح بالإعدادات المقيدة).")
            .setPositiveButton("تفعيل الإشعارات", (d, w) -> {
                try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {} }
            })
            .setNeutralButton("فتح الإعدادات المقيدة", (d, w) -> {
                openAppDetailsSettings();
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
               t.contains("تفاصيل اشتراك الرقم") || t.contains("تم تحديث الانترنت") ||
               t.contains("مرفوض:") || t.contains("رسائل جديدة") || t.contains("new messages") ||
               t.contains("جاري فحص");
    }

    public static boolean isDetailsRequestSms(String rawText) {
        if (rawText == null) return false;
        return Pattern.compile("(كم ضايل|قديش ضايل|ضايل بالحزم|ضايل بالحزمة|ضايل بالباقة|كم باقي|تفاصيل|تفصيل|تاريخ|انتهاء|إنتهاء|تنتهي|ينتهي|بتنتهي|بنتهي|حزمة|حزمه|باقة|باقه|متى|موعد|استعلام|إستعلام|صلاحية|صلاحيه|اشتراك|إشتراك|بيانات|معلومات)").matcher(rawText).find();
    }

    public static boolean isWeakNetRequestSms(String rawText) {
        if (rawText == null) return false;
        return Pattern.compile("(النت ضعيف|انترنت ضعيف|إنترنت ضعيف|نت ضعيف|النت بطيء|نت بطيء|النت مقطوع|النت فاصل|ما في نت|مافي نت|النت واقف)").matcher(rawText).find();
    }

    public static String detectMessageMode(String rawText) {
        if (rawText == null) return "refresh";
        if (isDetailsRequestSms(rawText)) return "details";
        if (isWeakNetRequestSms(rawText)) return "weak_net";
        // يشمل: حدثلي يا ابو احمد، الارسال رايح يا ابو احمد، أو أي رسالة أخرى تحتوي على رقم هاتف
        return "refresh";
    }

    public static boolean isIgnoredPrefixPhone(String phone) {
        if (phone == null) return false;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("00972") || digits.startsWith("00970")) digits = "0" + digits.substring(5);
        else if ((digits.startsWith("972") || digits.startsWith("970")) && digits.length() >= 11) digits = "0" + digits.substring(3);
        else if (digits.length() == 9 && digits.startsWith("5")) digits = "0" + digits;
        return digits.startsWith("059") || digits.startsWith("056");
    }

    // لا يتم التعامل مع أي رسالة لا تحتوي على رقم هاتف صريح بداخل نص الرسالة
    public static String extractCleanPhone(String rawText, boolean allow059ForDetails) {
        if (rawText == null || rawText.isEmpty() || isIgnoredSystemSms(rawText)) return null;
        StringBuilder sb = new StringBuilder();
        for (char c : rawText.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        String cleaned = sb.toString().replaceAll("[\\s\\-\\.\\(\\)\\+\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "");
        Matcher m = Pattern.compile("(?:00972|00970|972|970|0)?(5\\d{8})").matcher(cleaned);
        while (m.find()) {
            String cand = "0" + m.group(1);
            if (!allow059ForDetails && (cand.startsWith("059") || cand.startsWith("056"))) continue;
            return cand;
        }
        return null;
    }

    // فحص وتطبيق قيد الـ 20 دقيقة لأرقام سكاي
    public static long getSkyRemainingCooldownMs(Context ctx, String cleanPhone) {
        if (cleanPhone == null || cleanPhone.isEmpty()) return 0L;
        try {
            SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences("BrandSimSkyCooldowns", Context.MODE_PRIVATE);
            long lastTime = prefs.getLong("sky_20m_last_" + cleanPhone, 0L);
            if (lastTime <= 0L) return 0L;
            long elapsed = System.currentTimeMillis() - lastTime;
            if (elapsed >= 0L && elapsed < SKY_COOLDOWN_MS) {
                return SKY_COOLDOWN_MS - elapsed;
            }
        } catch (Exception e) {}
        return 0L;
    }

    public static void recordSkyRefreshTimestamp(Context ctx, String cleanPhone) {
        if (cleanPhone == null || cleanPhone.isEmpty()) return;
        try {
            SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences("BrandSimSkyCooldowns", Context.MODE_PRIVATE);
            prefs.edit().putLong("sky_20m_last_" + cleanPhone, System.currentTimeMillis()).commit();
        } catch (Exception e) {}
    }

    public static String formatSkyCooldownMessage(String cleanPhone, long remainingMs) {
        long totalMinutes = Math.max(1L, (remainingMs + 59999L) / 60000L);
        long hours = totalMinutes / 60L;
        long mins = totalMinutes % 60L;
        String timeStr;
        if (hours > 0 && mins > 0) {
            timeStr = hours + " ساعة و " + mins + " دقيقة";
        } else if (hours > 0) {
            timeStr = hours + " ساعة";
        } else {
            timeStr = mins + " دقيقة";
        }
        return "⚠️ عذراً، طلب التحديث للرقم " + cleanPhone + " مرفوض لتكرار الطلب.\nيُسمح بتحديث أرقام براند سيم مرة واحدة فقط كل 20 دقيقة.\nيرجى المحاولة بعد مرور: " + timeStr + ".";
    }

    public static void sendSmsDirect(final Context ctx, final String sender, final String message) {
        if (sender == null || sender.trim().isEmpty() || message == null || message.trim().isEmpty()) return;
        if ("WEB".equalsIgnoreCase(sender) || "XONE1".equalsIgnoreCase(sender)) return;
        if (sender.contains("_tg_") || sender.startsWith("tg_") || "SKY_WEB".equalsIgnoreCase(sender)) return;

        String cleanReply = sanitizeBrandText(message).trim();
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


    public static String sanitizeBrandText(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)Sky\\s*Telecom", "BRAND SIM")
                   .replaceAll("(?i)SkyTelecom", "BRAND SIM")
                   .replace("سكاي تيليكوم", "BRAND SIM")
                   .replace("سكاي تليكوم", "BRAND SIM")
                   .replace("شركة سكاي", "BRAND SIM");
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

        if (t.contains("قبل قليل") || t.contains("فشل") || t.contains("تم تحديث") || t.contains("غير صحيح") || t.contains("غير صالح") || t.contains("غير مسجل") || t.contains("لا يمكن")) {
            return false;
        }
        if (t.contains("بنجاح") && !t.contains("يستغرق") && !t.contains("انتظار")) {
            return false;
        }

        String[] kws = {"يستغرق", "استغراق", "تعاون", "جاري", "معالج", "لحظ", "تحميل", "loading", "please wait", "wait", "processing", "captcha", "كابتشا", "روبوت"};
        for (String kw : kws) if (t.contains(kw)) return true;
        return false;
    }

    private static String lastSkyRunPhone = "";
    private static long lastSkyRunTime = 0;

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender) {
        runHeadlessSkyRefresh(ctx, phone, sender, "refresh");
    }

    @SuppressLint("SetJavaScriptEnabled")
    public static void runHeadlessSkyRefresh(final Context ctx, final String phone, final String sender, final String mode) {
        if (phone == null || isIgnoredPrefixPhone(phone)) return;
        final String cleanPhone = phone.replaceAll("[^0-9]", "");

        // فحص قيد الـ 20 دقيقة لأرقام سكاي قبل إجراء أي طلب
        long remMs = getSkyRemainingCooldownMs(ctx, cleanPhone);
        if (remMs > 0L) {
            String rejectMsg = formatSkyCooldownMessage(cleanPhone, remMs);
            if (sender != null && !sender.trim().isEmpty()) {
                sendSmsDirect(ctx, sender, rejectMsg);
            }
            if (instance != null) {
                instance.updateUiMessage("warning", rejectMsg);
            }
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, rejectMsg, Toast.LENGTH_LONG).show());
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (cleanPhone.equals(lastSkyRunPhone) && (now - lastSkyRunTime) < 10000) return;
        lastSkyRunPhone = cleanPhone;
        lastSkyRunTime = now;

        // تسجيل وقت التحديث فوراً لمنع أي طلب مكرر خلال 20 دقيقة
        recordSkyRefreshTimestamp(ctx, cleanPhone);

        new Handler(Looper.getMainLooper()).post(() -> {
            ensureWebViewProcessDir(ctx.getApplicationContext());
            Toast.makeText(ctx, "جاري تحديث براند سيم بالخلفية: " + cleanPhone, Toast.LENGTH_SHORT).show();
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
                    skyWebView.stopLoading();
                    skyWebView.destroy();
                } catch (Exception e) {}
            };

            final Object skyBridge = new Object() {
                @JavascriptInterface
                public void onSkyResult(final String skyMsg) {
                    if (completed[0]) return;
                    String trimmed = skyMsg != null ? sanitizeBrandText(skyMsg).trim() : "";
                    if (trimmed.isEmpty() || isWaitingText(trimmed)) return;
                    if (!cleanPhone.isEmpty() && cleanPhone.equals(trimmed.replaceAll("[^0-9]", ""))) return;
                    if (trimmed.matches("^0?5\\d{8}$")) return;

                    completed[0] = true;
                    boolean isFail = trimmed.contains("فشل") || trimmed.contains("قبل قليل") || trimmed.contains("غير صحيح") || trimmed.contains("غير صالح") || trimmed.contains("خطأ");
                    String outMsg = "weak_net".equals(mode) ? ("تم تحديث الانترنت\n" + trimmed) : trimmed;
                    if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, outMsg);
                    if (instance != null) {
                        instance.updateUiMessage(isFail ? "warning" : "success", outMsg);
                    }
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(ctx, "نتيجة براند سيم (" + cleanPhone + "): " + outMsg, Toast.LENGTH_LONG).show());
                    new Handler(Looper.getMainLooper()).postDelayed(finishCleanup, 2000);
                }

                @JavascriptInterface
                public void onCaptchaRequired() {}
            };

            skyWebView.addJavascriptInterface(skyBridge, "SkyBridge");
            skyWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                    if (message != null && !isWaitingText(message.trim()) && !completed[0]) {
                        completed[0] = true;
                        String t = sanitizeBrandText(message).trim();
                        boolean isFail = t.contains("فشل") || t.contains("قبل قليل") || t.contains("غير صحيح") || t.contains("خطأ");
                        String outMsg = "weak_net".equals(mode) ? ("تم تحديث الانترنت\n" + t) : t;
                        if (sender != null && !sender.trim().isEmpty()) sendSmsDirect(ctx, sender, outMsg);
                        if (instance != null) {
                            instance.updateUiMessage(isFail ? "warning" : "success", outMsg);
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
                        + "var P='" + cleanPhone + "';"
                        + "function isW(s){if(!s)return true;var t=String(s).trim();if(t.length<=2||t.indexOf(P)!==-1)return true;"
                        + "if(/(قبل قليل|فشل|تم تحديث|غير صحيح|غير صالح|لا يمكن)/i.test(t))return false;"
                        + "if(/بنجاح/i.test(t)&&!/(يستغرق|انتظار)/i.test(t))return false;"
                        + "return /(يستغرق|تعاون|جاري|معالج|لحظ|تحميل|تحديث الرقم|sky telecom|loading|wait|captcha|كابتشا)/i.test(t);}"
                        + "function sendRes(m){if(m&&!isW(m))window.SkyBridge.onSkyResult(String(m).replace(/<[^>]*>/g,' ').replace(/Sky\\s*Telecom/ig,'BRAND SIM').replace(/\\s+/g,' ').trim());}"
                        + "window.alert=function(m){sendRes(m);};"
                        + "if(window.Swal&&window.Swal.fire){var of=window.Swal.fire;window.Swal.fire=function(){try{var a=arguments[0];sendRes(typeof a==='string'?a:((a.title||'')+' '+(a.text||a.html||'')));}catch(e){}return of.apply(this,arguments);};}"
                        + "function chk(){var els=document.querySelectorAll('.swal2-popup,.modal-content,.alert,[role=alert],#result,.result,#message,.message,.bg-red-100,.bg-green-100,.text-red-700,.text-green-700');"
                        + "for(var i=0;i<els.length;i++){var txt=(els[i].innerText||'').trim();if(txt&&!isW(txt)){sendRes(txt);return true;}}"
                        + "var bodyTxt=document.body.innerText||'';"
                        + "var m=bodyTxt.match(/(فشل[\\s\\S]{1,100}?\\)|تم تحديث[\\s\\S]{1,80}|لقد قمت بتحديث الرقم[\\s\\S]{1,60})/i);"
                        + "if(m&&m[1]){sendRes(m[1]);return true;}"
                        + "return false;}"
                        + "var inp=document.querySelector('input[type=tel],input[type=text],input[type=number],input:not([type=hidden])');"
                        + "if(inp){inp.focus();Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set.call(inp,P);"
                        + "inp.dispatchEvent(new Event('input',{bubbles:true}));inp.dispatchEvent(new Event('change',{bubbles:true}));"
                        + "setTimeout(function(){var bs=Array.from(document.querySelectorAll('button,input[type=submit],a.btn'));"
                        + "var b=bs.find(function(x){return /تحديث|ارسال|إرسال|submit|refresh/i.test(x.innerText||x.value||'');})||bs[0];if(b)b.click();"
                        + "var c=0,tm=setInterval(function(){c++;if(chk()||c>360)clearInterval(tm);},500);},700);"
                        + "}"
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
        public void autoRefreshSky(final String phone, final String sender) {
            if (isIgnoredPrefixPhone(phone)) return;
            runHeadlessSkyRefresh(ctx, phone, sender, "refresh");
        }

        @JavascriptInterface
        public void autoRefreshSkyWithMode(final String phone, final String sender, final String mode) {
            if (isIgnoredPrefixPhone(phone)) return;
            runHeadlessSkyRefresh(ctx, phone, sender, mode != null ? mode : "refresh");
        }

        @JavascriptInterface
        public long getSkyCooldownRemainingMs(final String phone) {
            if (phone == null) return 0L;
            return getSkyRemainingCooldownMs(ctx, phone.replaceAll("[^0-9]", ""));
        }

        @JavascriptInterface
        public String getSkyCooldownRejectMsg(final String phone) {
            if (phone == null) return "";
            String clean = phone.replaceAll("[^0-9]", "");
            long rem = getSkyRemainingCooldownMs(ctx, clean);
            return rem > 0L ? formatSkyCooldownMessage(clean, rem) : "";
        }

        @JavascriptInterface
        public void markSkyRefreshed(final String phone) {
            if (phone == null) return;
            recordSkyRefreshTimestamp(ctx, phone.replaceAll("[^0-9]", ""));
        }

        @JavascriptInterface
        public void sendSmsReply(final String sender, final String message) {
            sendSmsDirect(ctx, sender, message);
            if (instance != null) instance.updateUiMessage("success", message);
        }

        @JavascriptInterface
        public void setChannelsConfig(boolean enableSms, boolean enableWa) {
            saveAllChannelsState(ctx, enableSms, enableWa, isChannelEnabled(ctx, "enable_telegram"));
        }

        @JavascriptInterface
        public void setAllChannelsConfig(boolean enableSms, boolean enableWa, boolean enableTg) {
            saveAllChannelsState(ctx, enableSms, enableWa, enableTg);
        }

        @JavascriptInterface
        public boolean isSmsChannelEnabled() { return isChannelEnabled(ctx, "enable_sms"); }

        @JavascriptInterface
        public boolean isWaChannelEnabled() { return isChannelEnabled(ctx, "enable_whatsapp"); }

        @JavascriptInterface
        public boolean isTgChannelEnabled() { return isChannelEnabled(ctx, "enable_telegram"); }
    }

    public static class RefreshService extends Service {
        public static volatile RefreshService instance;
        private static WebView sharedBgWebView;
        private static boolean isBgLoaded = false;
        private static final Queue<String[]> queue = new LinkedList<>();
        private static String lastEnqueuedItemKey = "";
        private static long lastEnqueuedItemTime = 0;
        private PowerManager.WakeLock wakeLock;
        private static final String CHANNEL_ID = "brand_sim_bg_channel_locked";
        public static final int NOTIF_ID = 1;
        private final Handler watchdogHandler = new Handler(Looper.getMainLooper());
        private final Runnable watchdogTask = new Runnable() {
            @Override
            public void run() {
                try {
                    startForegroundNotification();
                    ensureWaListenerAlive(getApplicationContext(), false);
                    if (sharedBgWebView == null) initBgWebView(getApplicationContext());
                    else drainQueue();
                } catch (Exception e) {}
                watchdogHandler.postDelayed(this, 10000);
            }
        };

        @Override
        public void onCreate() {
            super.onCreate();
            instance = this;
            ensureWebViewProcessDir(getApplicationContext());
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), false);
            initBgWebView(getApplicationContext());
            watchdogHandler.removeCallbacks(watchdogTask);
            watchdogHandler.postDelayed(watchdogTask, 10000);
        }

        public static void initBgWebView(final Context appCtx) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    if (sharedBgWebView != null) {
                        if (isBgLoaded) drainQueue();
                        return;
                    }
                    sharedBgWebView = new WebView(appCtx.getApplicationContext());
                    configureWebView(sharedBgWebView, appCtx.getApplicationContext());
                    sharedBgWebView.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String url) {
                            super.onPageFinished(view, url);
                            isBgLoaded = true;
                            drainQueue();
                        }
                        @Override
                        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                            try {
                                if (sharedBgWebView != null) sharedBgWebView.destroy();
                            } catch (Exception e) {}
                            sharedBgWebView = null;
                            isBgLoaded = false;
                            initBgWebView(appCtx.getApplicationContext());
                            return true;
                        }
                    });
                    sharedBgWebView.loadUrl("file:///android_asset/refresh.html");
                } catch (Exception e) {}
            });
        }

        public static void runDirectFallbackRefresh(Context appCtx, String phone, String sender, String mode) {
            if (phone == null || phone.isEmpty() || isIgnoredPrefixPhone(phone)) return;
            enqueueStaticRequest(appCtx.getApplicationContext(), phone, sender, mode);
        }

        public void enqueuePhoneRequest(String phone, String sender, String mode) {
            acquireTempWakeLock();
            enqueueStaticRequest(getApplicationContext(), phone, sender, mode);
        }

        private static void enqueueStaticRequest(Context appCtx, String phone, String sender, String mode) {
            if (phone == null || phone.isEmpty() || isIgnoredPrefixPhone(phone)) return;
            String key = phone + "|" + (sender != null ? sender : "") + "|" + (mode != null ? mode : "refresh");
            long now = SystemClock.elapsedRealtime();
            synchronized (queue) {
                if (key.equals(lastEnqueuedItemKey) && (now - lastEnqueuedItemTime) < 8000) return;
                lastEnqueuedItemKey = key;
                lastEnqueuedItemTime = now;
                queue.add(new String[]{phone, sender != null ? sender : "", mode != null ? mode : "refresh"});
            }
            if (sharedBgWebView == null) initBgWebView(appCtx);
            else drainQueue();
        }

        public static Notification buildLockedNotification(Context ctx) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "BRAND SIM Auto Refresh", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }

            Intent openIntent = new Intent(ctx, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, openIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);

            // في حال تم سحب الإشعار في أندرويد 14+ يقوم النظام فوراً بإرسال هذا الـ Broadcast لإعادة تثبيته بالحال
            Intent restoreIntent = new Intent(ctx, SmsReceiver.class);
            restoreIntent.setAction("com.brandsim.refresh.RESTORE_NOTIFICATION");
            PendingIntent deletePi = PendingIntent.getBroadcast(ctx, 999, restoreIntent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(ctx, CHANNEL_ID) : new Notification.Builder(ctx);
            b.setContentTitle("BRAND SIM يعمل بالخلفية")
             .setContentText("جاهز للتحديث التلقائي الدائم (SMS / واتساب / الموقع)")
             .setSmallIcon(R.mipmap.ic_launcher)
             .setContentIntent(pi)
             .setDeleteIntent(deletePi)
             .setOngoing(true)
             .setAutoCancel(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                b.setCategory(Notification.CATEGORY_SERVICE);
                b.setVisibility(Notification.VISIBILITY_PUBLIC);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            }

            Notification notif = b.build();
            notif.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR | Notification.FLAG_FOREGROUND_SERVICE;
            return notif;
        }

        public void startForegroundNotification() {
            try {
                Notification notif = buildLockedNotification(this);
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(NOTIF_ID, notif);
            } catch (Exception e) {
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) nm.notify(NOTIF_ID, buildLockedNotification(this));
                } catch (Exception ignored) {}
            }
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            instance = this;
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), false);
            if (sharedBgWebView == null) initBgWebView(getApplicationContext());
            if (intent != null && intent.getBooleanExtra("sync_channels", false)) {
                getSharedPreferences("BrandSimConfig", Context.MODE_PRIVATE).edit()
                    .putBoolean("enable_sms", intent.getBooleanExtra("enable_sms", true))
                    .putBoolean("enable_whatsapp", intent.getBooleanExtra("enable_whatsapp", true))
                    .putBoolean("enable_telegram", intent.getBooleanExtra("enable_telegram", true))
                    .commit();
            }
            if (intent != null && intent.hasExtra("sms_target_phone")) {
                String phone = intent.getStringExtra("sms_target_phone");
                if (phone != null && !phone.isEmpty() && !isIgnoredPrefixPhone(phone)) {
                    String sender = intent.getStringExtra("sms_sender");
                    String mode = intent.getStringExtra("sms_mode");
                    enqueuePhoneRequest(phone, sender != null ? sender : "", mode != null ? mode : "refresh");
                }
            } else {
                drainQueue();
            }
            return START_STICKY;
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            startForegroundNotification();
            ensureWaListenerAlive(getApplicationContext(), true);
            scheduleServiceRestart(getApplicationContext(), 800);
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            if (instance == this) instance = null;
            watchdogHandler.removeCallbacksAndMessages(null);
            scheduleServiceRestart(getApplicationContext(), 800);
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

        private static void drainQueue() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!isBgLoaded || sharedBgWebView == null) return;
                synchronized (queue) {
                    while (!queue.isEmpty()) {
                        String[] item = queue.poll();
                        if (item != null) {
                            String phone = item[0].replace("'", "");
                            String sender = item[1].replace("'", "");
                            String mode = item[2].replace("'", "");
                            if (!phone.isEmpty() && isIgnoredPrefixPhone(phone)) continue;
                            sharedBgWebView.evaluateJavascript("javascript:if(window.executeSmsAutoRefresh){window.executeSmsAutoRefresh('" + phone + "','" + sender + "','" + mode + "');}", null);
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
        private static String lastNotifKey = "";
        private static long lastNotifTime = 0;

        @Override
        public void onCreate() {
            super.onCreate();
            instance = this;
            ensureWebViewProcessDir(getApplicationContext());
            RefreshService.initBgWebView(getApplicationContext());
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            instance = this;
            RefreshService.initBgWebView(getApplicationContext());
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
            return START_STICKY;
        }

        @Override
        public void onListenerConnected() {
            super.onListenerConnected();
            instance = this;
            isConnected = true;
            RefreshService.initBgWebView(getApplicationContext());
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
        }

        @Override
        public void onListenerDisconnected() {
            super.onListenerDisconnected();
            isConnected = false;
            if (instance == this) instance = null;
            ensureWaListenerAlive(getApplicationContext(), true);
            scheduleServiceRestart(getApplicationContext(), 1000);
        }

        @Override
        public void onTaskRemoved(Intent rootIntent) {
            super.onTaskRemoved(rootIntent);
            ensureWaListenerAlive(getApplicationContext(), true);
            startBackgroundRefreshService(getApplicationContext(), null, null, null);
            scheduleServiceRestart(getApplicationContext(), 800);
        }

        // إذا حاول أي طفل سحب إشعار BRAND SIM يمين أو شمال، يلتقطه المستمع فوراً ويعيد إظهاره في نفس الجزء من الثانية
        @Override
        public void onNotificationRemoved(StatusBarNotification sbn) {
            super.onNotificationRemoved(sbn);
            try {
                if (sbn != null && getPackageName().equals(sbn.getPackageName()) && sbn.getId() == RefreshService.NOTIF_ID) {
                    if (RefreshService.instance != null) {
                        RefreshService.instance.startForegroundNotification();
                    } else {
                        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.notify(RefreshService.NOTIF_ID, RefreshService.buildLockedNotification(getApplicationContext()));
                    }
                    startBackgroundRefreshService(getApplicationContext(), null, null, null);
                }
            } catch (Exception e) {}
        }

        private boolean isWhatsAppPackage(String pkg) {
            return "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
        }

        private boolean isTelegramPackage(String pkg) {
            if (pkg == null) return false;
            return "org.telegram.messenger".equals(pkg) ||
                   "org.telegram.messenger.web".equals(pkg) ||
                   "org.telegram.messenger.beta".equals(pkg) ||
                   "org.telegram.plus".equals(pkg) ||
                   "tw.nekomimi.nekogram".equals(pkg) ||
                   "org.thunderdog.challegram".equals(pkg) ||
                   pkg.startsWith("org.telegram.");
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (sbn == null) return;
            isConnected = true;
            instance = this;
            try {
                String pkg = sbn.getPackageName();
                boolean isWa = isWhatsAppPackage(pkg);
                boolean isTg = isTelegramPackage(pkg);
                if (!isWa && !isTg) return;

                if (isWa && !isChannelEnabled(this, "enable_whatsapp")) return;
                if (isTg && !isChannelEnabled(this, "enable_telegram")) return;

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
                if (body.isEmpty()) {
                    CharSequence bigTextCs = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                    if (bigTextCs != null) body = bigTextCs.toString().trim();
                }
                if (body.isEmpty() || isIgnoredSystemSms(body)) return;

                if (body.contains("SKY_WEB:")) {
                    String skyPhone = extractCleanPhone(body, false);
                    if (skyPhone != null && !skyPhone.isEmpty() && !isIgnoredPrefixPhone(skyPhone)) {
                        long now = SystemClock.elapsedRealtime();
                        String dedupKey = "NOTIF_SKY_WEB:" + skyPhone;
                        if (dedupKey.equals(lastNotifKey) && (now - lastNotifTime) < 12000) return;
                        lastNotifKey = dedupKey;
                        lastNotifTime = now;
                        try { cancelNotification(sbn.getKey()); } catch (Exception ignored) {}
                        runHeadlessSkyRefresh(getApplicationContext(), skyPhone, null, "refresh");
                        return;
                    }
                }

                if (isTg) return;

                // شرط أساسي: لا تتعامل مع أي رسالة لا تحتوي بداخلها على رقم هاتف
                String mode = detectMessageMode(body);
                String extractedPhone = extractCleanPhone(body, false);
                if (extractedPhone == null || extractedPhone.isEmpty() || isIgnoredPrefixPhone(extractedPhone)) {
                    return;
                }

                WhatsAppReplyAction replyAction = extractReplyAction(notif, sbn.getKey());
                String senderPhoneFromTitle = extractCleanPhone(title, false);
                String senderKey = senderPhoneFromTitle != null ? (senderPhoneFromTitle + "_wa_" + Math.abs(title.hashCode())) : ("wa_" + Math.abs(title.hashCode()));
                if (replyAction != null) {
                    waReplyMap.put(senderKey, replyAction);
                }

                String dedupKey = "WA:" + mode + ":" + extractedPhone + ":" + title + ":" + body;
                long now = SystemClock.elapsedRealtime();
                if (dedupKey.equals(lastNotifKey) && (now - lastNotifTime) < 12000) return;
                lastNotifKey = dedupKey;
                lastNotifTime = now;
                String targetSender = replyAction != null ? senderKey : "";
                RefreshService.runDirectFallbackRefresh(getApplicationContext(), extractedPhone, targetSender, mode);
                startBackgroundRefreshService(getApplicationContext(), extractedPhone, targetSender, mode);
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
                Context appCtx = context.getApplicationContext();
                ensureWebViewProcessDir(appCtx);
                String action = intent.getAction();
                if ("com.brandsim.refresh.RESTORE_NOTIFICATION".equals(action) ||
                    Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                    "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
                    Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                    Intent.ACTION_USER_PRESENT.equals(action) ||
                    "com.brandsim.refresh.RESTART_SERVICE".equals(action)) {
                    try {
                        if (RefreshService.instance != null) {
                            RefreshService.instance.startForegroundNotification();
                        } else {
                            NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
                            if (nm != null) nm.notify(RefreshService.NOTIF_ID, RefreshService.buildLockedNotification(appCtx));
                        }
                    } catch (Exception ignored) {}
                    ensureWaListenerAlive(appCtx, true);
                    RefreshService.initBgWebView(appCtx);
                    startBackgroundRefreshService(appCtx, null, null, null);
                    return;
                }
                if (!isChannelEnabled(appCtx, "enable_sms")) return;
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

                // شرط أساسي: لا تتعامل مع أي رسالة لا تحتوي بداخلها على رقم هاتف
                String mode = detectMessageMode(body);
                String extractedPhone = extractCleanPhone(body, false);
                if (extractedPhone != null && !extractedPhone.isEmpty() && !isIgnoredPrefixPhone(extractedPhone)) {
                    String dedupKey = mode + ":" + extractedPhone + ":" + sender;
                    long now = SystemClock.elapsedRealtime();
                    if (dedupKey.equals(lastProcessedKey) && (now - lastProcessedTime) < 12000) return;
                    lastProcessedKey = dedupKey;
                    lastProcessedTime = now;
                    String replyTarget = (sender == null || sender.contains("XONE") || !sender.matches("^[+0-9]+$")) ? extractedPhone : sender;

                    final PendingResult pr = goAsync();
                    RefreshService.runDirectFallbackRefresh(appCtx, extractedPhone, replyTarget, mode);
                    startBackgroundRefreshService(appCtx, extractedPhone, replyTarget, mode);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { pr.finish(); } catch (Exception ignored) {}
                    }, 8000);
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
