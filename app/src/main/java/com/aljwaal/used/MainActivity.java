package com.aljwaal.used;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_EXPORT = 501;
    private static final int REQ_IMPORT = 502;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Period, Button> periodButtons = new EnumMap<>(Period.class);

    private UsageRepository repository;
    private Period selected = Period.TODAY;
    private LinearLayout content;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new UsageRepository(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionAndData();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 32));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        root.addView(Ui.text(this, "استخدامي", 29, Ui.TEXT, true));

        TextView subtitle = Ui.text(this, "سجل استخدام الجهاز والتطبيقات", 14, Ui.MUTED, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, 0, 0, Ui.dp(this, 14));
        root.addView(subtitle, subLp);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout periods = new LinearLayout(this);
        periods.setOrientation(LinearLayout.HORIZONTAL);
        periods.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        addPeriod(periods, Period.TODAY, "اليوم");
        addPeriod(periods, Period.YESTERDAY, "أمس");
        addPeriod(periods, Period.THIS_WEEK, "هذا الأسبوع");
        addPeriod(periods, Period.LAST_WEEK, "الأسبوع الماضي");
        hsv.addView(periods);
        root.addView(hsv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 52)));

        status = Ui.text(this, "", 13, Ui.MUTED, false);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 8));
        root.addView(status, stLp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout backup = new LinearLayout(this);
        backup.setOrientation(LinearLayout.HORIZONTAL);
        backup.setGravity(Gravity.CENTER);
        backup.setPadding(0, Ui.dp(this, 18), 0, 0);

        Button export = new Button(this);
        export.setText("تصدير نسخة احتياطية");
        export.setAllCaps(false);
        export.setOnClickListener(v -> exportBackup());

        Button imp = new Button(this);
        imp.setText("استيراد نسخة");
        imp.setAllCaps(false);
        imp.setOnClickListener(v -> importBackup());

        backup.addView(export, new LinearLayout.LayoutParams(0, -2, 1));
        backup.addView(imp, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(backup);

        TextView privacy = Ui.text(this,
                "البيانات تبقى على جهازك. لا يتم قراءة الرسائل أو كلمات المرور أو النصوص التي تكتبها داخل التطبيقات.",
                12, Ui.MUTED, false);
        privacy.setPadding(Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6), 0);
        root.addView(privacy);

        updatePeriodButtons();
    }

    private void addPeriod(LinearLayout parent, Period period, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            selected = period;
            updatePeriodButtons();
            loadData();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 46));
        lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        parent.addView(b, lp);
        periodButtons.put(period, b);
    }

    private void updatePeriodButtons() {
        for (Map.Entry<Period, Button> entry : periodButtons.entrySet()) {
            boolean on = entry.getKey() == selected;
            entry.getValue().setTextColor(on ? Color.WHITE : Ui.TEXT);
            entry.getValue().setBackground(Ui.rounded(this, on ? Ui.PRIMARY : Color.WHITE, 18));
        }
    }

    private void refreshPermissionAndData() {
        if (!repository.hasUsageAccess()) {
            showPermission();
            return;
        }
        loadData();
        executor.execute(() -> repository.archiveRecentDays(14));
    }

    private void showPermission() {
        content.removeAllViews();
        status.setText("يلزم منح إذن «الوصول إلى بيانات الاستخدام» مرة واحدة.");

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "اسمح بقراءة إحصاءات الاستخدام", 18, Ui.TEXT, true));

        TextView body = Ui.text(this,
                "بعد منح الإذن سيقرأ التطبيق مدة استخدام التطبيقات وسجل الجلسات الذي لا يزال Android يحتفظ به، بما في ذلك بيانات سابقة متاحة مثل أمس والأسبوع الماضي.",
                14, Ui.MUTED, false);
        body.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        card.addView(body);

        Button open = new Button(this);
        open.setText("فتح إعدادات Usage Access");
        open.setAllCaps(false);
        open.setOnClickListener(v -> openUsageSettings());
        card.addView(open);
        content.addView(card);
    }

    private void openUsageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void loadData() {
        if (!repository.hasUsageAccess()) {
            showPermission();
            return;
        }
        status.setText("جاري قراءة سجل Android والأرشيف المحلي…");
        content.removeAllViews();
        ProgressBar busy = new ProgressBar(this);
        content.addView(busy, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));

        executor.execute(() -> {
            Summary summary = repository.load(selected);
            main.post(() -> render(summary));
        });
    }

    private void render(Summary summary) {
        content.removeAllViews();

        long total = 0;
        int sessionCount = 0;
        for (AppUsage app : summary.apps) {
            total += app.totalMs;
            sessionCount += app.sessions.size();
        }

        status.setText(summary.apps.isEmpty() ? "لا توجد بيانات استخدام لهذه الفترة." : "تم تحديث الإحصائيات الآن");

        LinearLayout overview = Ui.card(this);
        overview.addView(Ui.text(this, Ui.duration(total), 30, Ui.TEXT, true));
        overview.addView(Ui.text(this, "إجمالي استخدام التطبيقات", 13, Ui.MUTED, false));

        String normalStats = summary.apps.size() + " تطبيقات   •   " + sessionCount + " جلسة";
        if (summary.unlockCount > 0) normalStats += "   •   " + summary.unlockCount + " فتح للجهاز";
        TextView normal = Ui.text(this, normalStats, 14, Ui.TEXT, false);
        normal.setPadding(0, Ui.dp(this, 10), 0, 0);
        overview.addView(normal);

        if (!summary.apps.isEmpty()) {
            TextView top = Ui.text(this, "الأكثر استخدامًا: " + summary.apps.get(0).label, 13, Ui.MUTED, false);
            top.setPadding(0, Ui.dp(this, 6), 0, 0);
            overview.addView(top);
        }
        content.addView(overview);

        TextView heading = Ui.text(this, "استخدام التطبيقات", 20, Ui.TEXT, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.setMargins(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
        content.addView(heading, hp);

        long max = summary.apps.isEmpty() ? 1 : summary.apps.get(0).totalMs;
        for (AppUsage app : summary.apps) content.addView(appRow(app, max));

        if (summary.apps.isEmpty()) {
            content.addView(Ui.text(this,
                    "إذا منحت الإذن للتو، ارجع إلى التطبيق ثم اختر الفترة مرة أخرى.",
                    14, Ui.MUTED, false));
        }
    }

    private View appRow(AppUsage app, long max) {
        LinearLayout card = Ui.card(this);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openDetails(app));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(iconFor(app.packageName));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46));
        iconLp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        top.addView(icon, iconLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(Ui.text(this, app.label, 17, Ui.TEXT, true));

        String detail = Ui.duration(app.totalMs) + "   •   " + app.sessions.size() + " جلسة";
        if (app.lastUsedMs > 0) detail += "   •   آخر استخدام " + Ui.time(app.lastUsedMs);
        texts.addView(Ui.text(this, detail, 12, Ui.MUTED, false));
        top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(top);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.max(1, Math.min(1000,
                (app.totalMs * 1000L) / Math.max(1, max))));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 7));
        bp.setMargins(0, Ui.dp(this, 10), 0, 0);
        card.addView(bar, bp);
        return card;
    }

    private Drawable iconFor(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Exception e) {
            return getDrawable(android.R.drawable.sym_def_app_icon);
        }
    }

    private void openDetails(AppUsage app) {
        Range range = UsageRepository.rangeFor(selected);
        Intent i = new Intent(this, AppDetailActivity.class);
        i.putExtra("package", app.packageName);
        i.putExtra("label", app.label);
        i.putExtra("start", range.start);
        i.putExtra("end", range.end);
        startActivity(i);
    }

    private void exportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "used-backup-" + LocalDate.now() + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();

        executor.execute(() -> {
            try {
                if (requestCode == REQ_EXPORT) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new IOException("تعذر فتح الملف");
                        repository.db.exportJson(out);
                    }
                    main.post(() -> Toast.makeText(this, "تم حفظ النسخة الاحتياطية", Toast.LENGTH_LONG).show());
                } else if (requestCode == REQ_IMPORT) {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("تعذر فتح الملف");
                        repository.db.importJson(in);
                    }
                    main.post(() -> {
                        Toast.makeText(this, "تم استيراد النسخة ودمجها مع السجل", Toast.LENGTH_LONG).show();
                        loadData();
                    });
                }
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "تعذر تنفيذ العملية: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    public static class AppDetailActivity extends Activity {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private LinearLayout root;
        private String packageName;
        private String label;
        private long start;
        private long end;

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            packageName = getIntent().getStringExtra("package");
            label = getIntent().getStringExtra("label");
            start = getIntent().getLongExtra("start", 0);
            end = getIntent().getLongExtra("end", System.currentTimeMillis());
            buildUi();
            loadData();
        }

        private void buildUi() {
            getWindow().setStatusBarColor(Ui.BG);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(Ui.BG);
            root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 28));
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            scroll.addView(root);
            setContentView(scroll);

            TextView back = Ui.text(this, "‹ رجوع", 15, Ui.PRIMARY, true);
            back.setPadding(0, 0, 0, Ui.dp(this, 10));
            back.setOnClickListener(v -> finish());
            root.addView(back);

            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(packageName));
            } catch (Exception e) {
                icon.setImageDrawable(getDrawable(android.R.drawable.sym_def_app_icon));
            }
            header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 58), Ui.dp(this, 58)));

            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            names.addView(Ui.text(this, label, 25, Ui.TEXT, true));
            names.addView(Ui.text(this, packageName, 11, Ui.MUTED, false));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1);
            np.setMargins(Ui.dp(this, 12), 0, 0, 0);
            header.addView(names, np);
            root.addView(header);

            SimpleDateFormat df = new SimpleDateFormat("d MMM yyyy", new Locale("ar"));
            TextView range = Ui.text(this,
                    df.format(new Date(start)) + " — " + df.format(new Date(Math.max(start, end - 1))),
                    13, Ui.MUTED, false);
            range.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
            root.addView(range);

            ProgressBar busy = new ProgressBar(this);
            root.addView(busy, new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)));
        }

        private void loadData() {
            executor.execute(() -> {
                Summary summary = new UsageRepository(this).load(start, end);
                AppUsage found = null;
                for (AppUsage app : summary.apps) {
                    if (app.packageName.equals(packageName)) {
                        found = app;
                        break;
                    }
                }
                AppUsage result = found;
                main.post(() -> render(result));
            });
        }

        private void render(AppUsage app) {
            while (root.getChildCount() > 4) root.removeViewAt(4);
            if (app == null) {
                root.addView(Ui.text(this, "لا توجد بيانات لهذا التطبيق في الفترة المحددة.",
                        14, Ui.MUTED, false));
                return;
            }

            LinearLayout stats = Ui.card(this);
            stats.addView(Ui.text(this, Ui.duration(app.totalMs), 30, Ui.TEXT, true));
            stats.addView(Ui.text(this, "إجمالي الاستخدام", 13, Ui.MUTED, false));
            addStat(stats, "عدد الجلسات", String.valueOf(app.sessions.size()));
            addStat(stats, "أول استخدام", Ui.time(app.firstSessionMs()));
            addStat(stats, "آخر استخدام", Ui.time(Math.max(app.lastUsedMs, app.lastSessionMs())));
            addStat(stats, "أطول جلسة", app.sessions.isEmpty() ? "غير متاح" : Ui.duration(app.longestSessionMs()));
            addStat(stats, "متوسط الجلسة", app.sessions.isEmpty() ? "غير متاح" : Ui.duration(app.averageSessionMs()));
            root.addView(stats);

            TextView h = Ui.text(this, "الجلسات من — إلى", 20, Ui.TEXT, true);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.setMargins(0, Ui.dp(this, 20), 0, Ui.dp(this, 8));
            root.addView(h, hp);

            if (app.sessions.isEmpty()) {
                LinearLayout note = Ui.card(this);
                note.addView(Ui.text(this,
                        "لا توجد تفاصيل جلسات محفوظة لهذه الفترة. Android يحتفظ بالتفاصيل الزمنية الدقيقة مدة أقصر من الإحصاءات المجمعة، لذلك يبدأ «استخدامي» بأرشفتها محليًا من وقت تثبيته.",
                        14, Ui.MUTED, false));
                root.addView(note);
                return;
            }

            int number = 1;
            for (UsageSession session : app.sessions) {
                LinearLayout card = Ui.card(this);
                card.addView(Ui.text(this,
                        Ui.time(session.startMs) + "  ←  " + Ui.time(session.endMs),
                        18, Ui.TEXT, true));
                TextView detail = Ui.text(this,
                        "الجلسة " + number + "   •   " + Ui.duration(session.durationMs())
                                + "   •   " + Ui.dateTime(session.startMs),
                        12, Ui.MUTED, false);
                detail.setPadding(0, Ui.dp(this, 5), 0, 0);
                card.addView(detail);
                root.addView(card);
                number++;
            }
        }

        private void addStat(LinearLayout parent, String key, String value) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, Ui.dp(this, 10), 0, 0);
            row.addView(Ui.text(this, key, 14, Ui.MUTED, false),
                    new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(Ui.text(this, value, 14, Ui.TEXT, true),
                    new LinearLayout.LayoutParams(0, -2, 1));
            parent.addView(row);
        }

        @Override
        protected void onDestroy() {
            executor.shutdown();
            super.onDestroy();
        }
    }

    public static class ArchiveJobService extends JobService {
        private static final int JOB_ID = 24092026;

        public static void schedule(Context context) {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, ArchiveJobService.class))
                    .setPeriodic(6L * 60L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(info);
        }

        @Override
        public boolean onStartJob(JobParameters params) {
            new Thread(() -> {
                try {
                    new UsageRepository(getApplicationContext()).archiveRecentDays(14);
                } finally {
                    jobFinished(params, false);
                }
            }).start();
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters params) {
            return true;
        }
    }

    enum Period { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK }

    static class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    static class UsageSession {
        final String packageName;
        final String label;
        long startMs;
        long endMs;

        UsageSession(String packageName, String label, long startMs, long endMs) {
            this.packageName = packageName;
            this.label = label;
            this.startMs = startMs;
            this.endMs = endMs;
        }

        long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    static class AppUsage {
        final String packageName;
        String label;
        long totalMs;
        long lastUsedMs;
        final List<UsageSession> sessions = new ArrayList<>();

        AppUsage(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }

        long firstSessionMs() {
            long result = Long.MAX_VALUE;
            for (UsageSession s : sessions) result = Math.min(result, s.startMs);
            return result == Long.MAX_VALUE ? 0 : result;
        }

        long lastSessionMs() {
            long result = 0;
            for (UsageSession s : sessions) result = Math.max(result, s.endMs);
            return result;
        }

        long longestSessionMs() {
            long result = 0;
            for (UsageSession s : sessions) result = Math.max(result, s.durationMs());
            return result;
        }

        long averageSessionMs() {
            if (sessions.isEmpty()) return 0;
            long total = 0;
            for (UsageSession s : sessions) total += s.durationMs();
            return total / sessions.size();
        }
    }

    static class Summary {
        final List<AppUsage> apps;
        final int unlockCount;

        Summary(List<AppUsage> apps, int unlockCount) {
            this.apps = apps;
            this.unlockCount = unlockCount;
        }
    }

    static class UsageRepository {
        private final Context context;
        private final UsageStatsManager usage;
        private final PackageManager pm;
        final ArchiveDb db;

        UsageRepository(Context context) {
            this.context = context.getApplicationContext();
            usage = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            pm = context.getPackageManager();
            db = new ArchiveDb(context);
        }

        boolean hasUsageAccess() {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        }

        static Range rangeFor(Period period) {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime today = now.toLocalDate().atStartOfDay(zone);
            ZonedDateTime monday = today.minusDays(today.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());

            switch (period) {
                case YESTERDAY:
                    return new Range(today.minusDays(1).toInstant().toEpochMilli(),
                            today.toInstant().toEpochMilli());
                case THIS_WEEK:
                    return new Range(monday.toInstant().toEpochMilli(), now.toInstant().toEpochMilli());
                case LAST_WEEK:
                    return new Range(monday.minusWeeks(1).toInstant().toEpochMilli(),
                            monday.toInstant().toEpochMilli());
                case TODAY:
                default:
                    return new Range(today.toInstant().toEpochMilli(), now.toInstant().toEpochMilli());
            }
        }

        Summary load(Period period) {
            Range r = rangeFor(period);
            return load(r.start, r.end);
        }

        Summary load(long start, long end) {
            Map<String, AppUsage> map = new LinkedHashMap<>();

            Map<String, UsageStats> direct = usage.queryAndAggregateUsageStats(start, end);
            if (direct != null) {
                for (Map.Entry<String, UsageStats> entry : direct.entrySet()) {
                    long duration = entry.getValue().getTotalTimeInForeground();
                    if (duration <= 0 || !isUserFacing(entry.getKey())) continue;
                    AppUsage app = new AppUsage(entry.getKey(), labelFor(entry.getKey()));
                    app.totalMs = duration;
                    app.lastUsedMs = entry.getValue().getLastTimeUsed();
                    map.put(entry.getKey(), app);
                }
            }

            Map<String, ArchivedTotal> archived = db.totals(dayStart(start), dayExclusiveEnd(end));
            for (Map.Entry<String, ArchivedTotal> entry : archived.entrySet()) {
                if (!isUserFacingOrArchived(entry.getKey())) continue;
                AppUsage app = map.get(entry.getKey());
                if (app == null) {
                    app = new AppUsage(entry.getKey(),
                            safeLabel(entry.getValue().label, entry.getKey()));
                    map.put(entry.getKey(), app);
                }
                app.totalMs = Math.max(app.totalMs, entry.getValue().durationMs);
                app.lastUsedMs = Math.max(app.lastUsedMs, entry.getValue().lastUsedMs);
            }

            List<UsageSession> sessions = new ArrayList<>(db.sessions(start, end));
            sessions.addAll(querySessions(start, end));
            sessions = mergeSessions(sessions, start, end);

            for (UsageSession session : sessions) {
                if (!isUserFacingOrArchived(session.packageName)) continue;
                AppUsage app = map.get(session.packageName);
                if (app == null) {
                    app = new AppUsage(session.packageName,
                            safeLabel(session.label, labelFor(session.packageName)));
                    map.put(session.packageName, app);
                }
                app.sessions.add(session);
                app.lastUsedMs = Math.max(app.lastUsedMs, session.endMs);
                if (app.totalMs == 0) {
                    long sum = 0;
                    for (UsageSession s : app.sessions) sum += s.durationMs();
                    app.totalMs = sum;
                }
            }

            List<AppUsage> apps = new ArrayList<>(map.values());
            apps.removeIf(a -> a.totalMs < 1000);
            apps.sort((a, b) -> Long.compare(b.totalMs, a.totalMs));
            return new Summary(apps, countUnlocks(start, end));
        }

        void archiveRecentDays(int days) {
            if (!hasUsageAccess()) return;
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);

            for (int i = Math.max(0, days - 1); i >= 0; i--) {
                long start = today.minusDays(i).atStartOfDay(zone).toInstant().toEpochMilli();
                long theoreticalEnd = today.minusDays(i).plusDays(1)
                        .atStartOfDay(zone).toInstant().toEpochMilli();
                long end = Math.min(System.currentTimeMillis(), theoreticalEnd);
                if (end <= start) continue;

                Map<String, UsageStats> stats = usage.queryAndAggregateUsageStats(start, end);
                if (stats != null) {
                    for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
                        long duration = entry.getValue().getTotalTimeInForeground();
                        if (duration <= 0 || !isUserFacing(entry.getKey())) continue;
                        db.upsertDaily(start, entry.getKey(), labelFor(entry.getKey()),
                                duration, entry.getValue().getLastTimeUsed());
                    }
                }

                for (UsageSession session : querySessions(start, end)) {
                    if (isUserFacing(session.packageName)) db.insertSession(session);
                }
            }
        }

        private List<UsageSession> querySessions(long start, long end) {
            if (end <= start) return Collections.emptyList();

            List<UsageSession> result = new ArrayList<>();
            Map<String, Long> active = new HashMap<>();
            UsageEvents events = usage.queryEvents(start, end);
            if (events == null) return result;

            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                long time = event.getTimeStamp();
                String pkg = event.getPackageName();

                if (type == 1 && pkg != null) {
                    active.putIfAbsent(pkg, time);
                } else if (type == 2 && pkg != null) {
                    Long sessionStart = active.remove(pkg);
                    if (sessionStart != null && time > sessionStart) {
                        result.add(new UsageSession(pkg, labelFor(pkg), sessionStart, time));
                    } else if (time > start && time - start <= 6L * 60L * 60L * 1000L) {
                        result.add(new UsageSession(pkg, labelFor(pkg), start, time));
                    }
                } else if (type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    closeAll(active, result, time);
                }
            }

            closeAll(active, result, Math.min(end, System.currentTimeMillis()));
            return mergeSessions(result, start, end);
        }

        private void closeAll(Map<String, Long> active, List<UsageSession> out, long end) {
            for (Map.Entry<String, Long> entry : new ArrayList<>(active.entrySet())) {
                if (end > entry.getValue()) {
                    out.add(new UsageSession(entry.getKey(), labelFor(entry.getKey()),
                            entry.getValue(), end));
                }
            }
            active.clear();
        }

        private List<UsageSession> mergeSessions(List<UsageSession> input, long start, long end) {
            Map<String, List<UsageSession>> grouped = new HashMap<>();
            for (UsageSession session : input) {
                long a = Math.max(start, session.startMs);
                long b = Math.min(end, session.endMs);
                if (b <= a) continue;
                grouped.computeIfAbsent(session.packageName, k -> new ArrayList<>())
                        .add(new UsageSession(session.packageName,
                                safeLabel(session.label, labelFor(session.packageName)), a, b));
            }

            List<UsageSession> out = new ArrayList<>();
            for (List<UsageSession> list : grouped.values()) {
                list.sort(Comparator.comparingLong(x -> x.startMs));
                UsageSession current = null;
                for (UsageSession next : list) {
                    if (current == null) {
                        current = next;
                    } else if (next.startMs <= current.endMs + 1500) {
                        current.endMs = Math.max(current.endMs, next.endMs);
                    } else {
                        out.add(current);
                        current = next;
                    }
                }
                if (current != null) out.add(current);
            }
            out.sort(Comparator.comparingLong(x -> x.startMs));
            return out;
        }

        private int countUnlocks(long start, long end) {
            int count = 0;
            UsageEvents events = usage.queryEvents(start, end);
            if (events == null) return 0;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.KEYGUARD_HIDDEN) count++;
            }
            return count;
        }

        private boolean isUserFacing(String pkg) {
            if (pkg == null || pkg.equals(context.getPackageName())
                    || "com.android.systemui".equals(pkg)) return false;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                return pm.getLaunchIntentForPackage(pkg) != null
                        || (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                        || "com.android.settings".equals(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        private boolean isUserFacingOrArchived(String pkg) {
            if (pkg == null || pkg.equals(context.getPackageName())
                    || "com.android.systemui".equals(pkg)) return false;
            return isUserFacing(pkg) || !db.latestLabel(pkg).equals(pkg);
        }

        private String labelFor(String pkg) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(info);
                return label == null ? pkg : label.toString();
            } catch (Exception e) {
                return db.latestLabel(pkg);
            }
        }

        private String safeLabel(String label, String fallback) {
            return label == null || label.trim().isEmpty() ? fallback : label;
        }

        private long dayStart(long millis) {
            ZoneId z = ZoneId.systemDefault();
            return Instant.ofEpochMilli(millis).atZone(z).toLocalDate()
                    .atStartOfDay(z).toInstant().toEpochMilli();
        }

        private long dayExclusiveEnd(long millis) {
            ZoneId z = ZoneId.systemDefault();
            ZonedDateTime value = Instant.ofEpochMilli(millis).atZone(z);
            ZonedDateTime start = value.toLocalDate().atStartOfDay(z);
            if (millis > start.toInstant().toEpochMilli()) start = start.plusDays(1);
            return start.toInstant().toEpochMilli();
        }
    }

    static class ArchivedTotal {
        long durationMs;
        long lastUsedMs;
        String label;
    }

    static class ArchiveDb extends SQLiteOpenHelper {
        private static final String DB_NAME = "usage_archive.db";

        ArchiveDb(Context context) {
            super(context, DB_NAME, null, 1);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE daily_usage (" +
                    "day_start INTEGER NOT NULL," +
                    "package_name TEXT NOT NULL," +
                    "app_label TEXT," +
                    "duration_ms INTEGER NOT NULL," +
                    "last_used_ms INTEGER NOT NULL," +
                    "PRIMARY KEY(day_start, package_name))");

            db.execSQL("CREATE TABLE sessions (" +
                    "package_name TEXT NOT NULL," +
                    "app_label TEXT," +
                    "start_ms INTEGER NOT NULL," +
                    "end_ms INTEGER NOT NULL," +
                    "PRIMARY KEY(package_name, start_ms, end_ms))");

            db.execSQL("CREATE INDEX idx_sessions_time ON sessions(start_ms, end_ms)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

        synchronized void upsertDaily(long dayStart, String pkg, String label,
                                      long duration, long lastUsed) {
            ContentValues values = new ContentValues();
            values.put("day_start", dayStart);
            values.put("package_name", pkg);
            values.put("app_label", label);
            values.put("duration_ms", duration);
            values.put("last_used_ms", lastUsed);
            getWritableDatabase().insertWithOnConflict("daily_usage", null, values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        }

        synchronized void insertSession(UsageSession session) {
            if (session.endMs <= session.startMs) return;
            ContentValues values = new ContentValues();
            values.put("package_name", session.packageName);
            values.put("app_label", session.label);
            values.put("start_ms", session.startMs);
            values.put("end_ms", session.endMs);
            getWritableDatabase().insertWithOnConflict("sessions", null, values,
                    SQLiteDatabase.CONFLICT_IGNORE);
        }

        synchronized Map<String, ArchivedTotal> totals(long start, long end) {
            Map<String, ArchivedTotal> result = new HashMap<>();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT package_name, MAX(app_label), SUM(duration_ms), MAX(last_used_ms) " +
                            "FROM daily_usage WHERE day_start>=? AND day_start<? GROUP BY package_name",
                    new String[]{String.valueOf(start), String.valueOf(end)})) {
                while (c.moveToNext()) {
                    ArchivedTotal total = new ArchivedTotal();
                    total.label = c.getString(1);
                    total.durationMs = c.getLong(2);
                    total.lastUsedMs = c.getLong(3);
                    result.put(c.getString(0), total);
                }
            }
            return result;
        }

        synchronized List<UsageSession> sessions(long start, long end) {
            List<UsageSession> result = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT package_name, app_label, start_ms, end_ms FROM sessions " +
                            "WHERE end_ms>? AND start_ms<? ORDER BY start_ms",
                    new String[]{String.valueOf(start), String.valueOf(end)})) {
                while (c.moveToNext()) {
                    long s = Math.max(start, c.getLong(2));
                    long e = Math.min(end, c.getLong(3));
                    if (e > s) {
                        result.add(new UsageSession(c.getString(0), c.getString(1), s, e));
                    }
                }
            }
            return result;
        }

        synchronized String latestLabel(String pkg) {
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT app_label FROM daily_usage WHERE package_name=? " +
                            "AND app_label IS NOT NULL ORDER BY day_start DESC LIMIT 1",
                    new String[]{pkg})) {
                if (c.moveToFirst()) {
                    String value = c.getString(0);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
            return pkg;
        }

        synchronized void exportJson(OutputStream output) throws IOException {
            JsonWriter w = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            w.setIndent("  ");
            w.beginObject();
            w.name("format").value("used-backup-v1");
            w.name("exportedAt").value(System.currentTimeMillis());

            w.name("dailyUsage").beginArray();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT day_start, package_name, app_label, duration_ms, last_used_ms " +
                            "FROM daily_usage ORDER BY day_start", null)) {
                while (c.moveToNext()) {
                    w.beginObject();
                    w.name("dayStart").value(c.getLong(0));
                    w.name("package").value(c.getString(1));
                    w.name("label").value(c.getString(2));
                    w.name("duration").value(c.getLong(3));
                    w.name("lastUsed").value(c.getLong(4));
                    w.endObject();
                }
            }
            w.endArray();

            w.name("sessions").beginArray();
            try (Cursor c = getReadableDatabase().rawQuery(
                    "SELECT package_name, app_label, start_ms, end_ms FROM sessions ORDER BY start_ms",
                    null)) {
                while (c.moveToNext()) {
                    w.beginObject();
                    w.name("package").value(c.getString(0));
                    w.name("label").value(c.getString(1));
                    w.name("start").value(c.getLong(2));
                    w.name("end").value(c.getLong(3));
                    w.endObject();
                }
            }
            w.endArray();
            w.endObject();
            w.flush();
        }

        synchronized void importJson(InputStream input) throws IOException {
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                JsonReader r = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                r.beginObject();
                while (r.hasNext()) {
                    String name = r.nextName();
                    if ("dailyUsage".equals(name)) {
                        r.beginArray();
                        while (r.hasNext()) importDaily(r, db);
                        r.endArray();
                    } else if ("sessions".equals(name)) {
                        r.beginArray();
                        while (r.hasNext()) importSession(r, db);
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        private void importDaily(JsonReader r, SQLiteDatabase db) throws IOException {
            long day = 0, duration = 0, lastUsed = 0;
            String pkg = "", label = "";
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "dayStart": day = r.nextLong(); break;
                    case "package": pkg = readString(r); break;
                    case "label": label = readString(r); break;
                    case "duration": duration = r.nextLong(); break;
                    case "lastUsed": lastUsed = r.nextLong(); break;
                    default: r.skipValue();
                }
            }
            r.endObject();

            if (!pkg.isEmpty() && day > 0) {
                ContentValues v = new ContentValues();
                v.put("day_start", day);
                v.put("package_name", pkg);
                v.put("app_label", label);
                v.put("duration_ms", duration);
                v.put("last_used_ms", lastUsed);
                db.insertWithOnConflict("daily_usage", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }

        private void importSession(JsonReader r, SQLiteDatabase db) throws IOException {
            long start = 0, end = 0;
            String pkg = "", label = "";
            r.beginObject();
            while (r.hasNext()) {
                String name = r.nextName();
                switch (name) {
                    case "package": pkg = readString(r); break;
                    case "label": label = readString(r); break;
                    case "start": start = r.nextLong(); break;
                    case "end": end = r.nextLong(); break;
                    default: r.skipValue();
                }
            }
            r.endObject();

            if (!pkg.isEmpty() && end > start) {
                ContentValues v = new ContentValues();
                v.put("package_name", pkg);
                v.put("app_label", label);
                v.put("start_ms", start);
                v.put("end_ms", end);
                db.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        }

        private String readString(JsonReader r) throws IOException {
            if (r.peek() == JsonToken.NULL) {
                r.nextNull();
                return "";
            }
            return r.nextString();
        }
    }

    static final class Ui {
        static final int PRIMARY = Color.rgb(49, 94, 251);
        static final int TEXT = Color.rgb(24, 30, 45);
        static final int MUTED = Color.rgb(101, 109, 128);
        static final int BG = Color.rgb(246, 247, 251);

        static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        static GradientDrawable rounded(Context context, int color, int radius) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(color);
            d.setCornerRadius(dp(context, radius));
            return d;
        }

        static LinearLayout card(Context context) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(context, 16), dp(context, 15), dp(context, 16), dp(context, 15));
            card.setBackground(rounded(context, Color.WHITE, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(context, 6), 0, dp(context, 6));
            card.setLayoutParams(lp);
            return card;
        }

        static TextView text(Context context, String value, float size, int color, boolean bold) {
            TextView t = new TextView(context);
            t.setText(value == null ? "" : value);
            t.setTextSize(size);
            t.setTextColor(color);
            t.setGravity(Gravity.RIGHT);
            t.setTextDirection(View.TEXT_DIRECTION_RTL);
            if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            return t;
        }

        static String duration(long millis) {
            long seconds = Math.max(0, millis / 1000);
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long remain = seconds % 60;
            if (hours > 0) return hours + " س " + minutes + " د";
            if (minutes > 0) return minutes + " د " + remain + " ث";
            return remain + " ث";
        }

        static String time(long millis) {
            if (millis <= 0) return "—";
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
        }

        static String dateTime(long millis) {
            if (millis <= 0) return "—";
            return new SimpleDateFormat("EEE d MMM • HH:mm", new Locale("ar"))
                    .format(new Date(millis));
        }
    }
}
