package org.fable2.recomp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class LauncherActivity extends Activity {
    private static final int PICK_ISO = 1, PICK_ZIP = 2, EXPORT_SAVES = 3, EXPORT_LOGS = 4;
    private Settings settings;
    private TextView status, version;
    private Button playButton, isoButton, zipButton, cancelButton;
    private ProgressBar progress;
    private LinearLayout settingsColumn, welcome;
    private volatile boolean cancelImport, importing;
    private boolean hashing;

    private File root() { return getExternalFilesDir(null); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new Settings(this);
        getWindow().setStatusBarColor(Ui.BACKGROUND);
        getWindow().setNavigationBarColor(Ui.BACKGROUND);
        new Thread(() -> {
            try {
                Settings.installBundledDriver(this);
                if (BuildConfig.DEBUG && getIntent().getBooleanExtra("benchmark", false))
                    runOnUiThread(() -> { if (!isFinishing() && !isDestroyed() && gameReady())
                        startActivity(new Intent(this, GameActivity.class).putExtra("benchmark", true)); });
            }
            catch (IOException error) { runOnUiThread(() -> toast("GPU driver install failed: " + error.getMessage())); }
        }, "DriverInstall").start();
        build();
        refresh();
    }

    @Override protected void onResume() { super.onResume(); if (!importing) refresh(); }

    private void build() {
        int pad = Ui.dp(this, 20);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[] { 0xFF143034, Ui.BACKGROUND, 0xFF070F10 });
        screen.setBackground(bg);
        screen.setPadding(pad, pad, pad, pad);

        // Left: identity, status and the main actions.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        left.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        TextView name = Ui.text(this, "Fable II", 28, Ui.IVORY, true);
        name.setGravity(Gravity.CENTER);
        left.addView(name, Ui.fill(this, 4));
        TextView subtitle = Ui.text(this, "Native recompilation for Android", 14, Ui.GOLD, false);
        subtitle.setGravity(Gravity.CENTER);
        left.addView(subtitle, Ui.fill(this, 2));

        LinearLayout statusCard = Ui.card(this);
        status = Ui.text(this, "", 15, Ui.IVORY, false);
        version = Ui.text(this, "", 13, Ui.MUTED, false);
        version.setPadding(0, Ui.dp(this, 4), 0, 0);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.GOLD));
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        statusCard.addView(status);
        statusCard.addView(version);
        statusCard.addView(progress, Ui.fill(this, 10));
        left.addView(statusCard, Ui.fill(this, 14));

        playButton = Ui.primary(this, "Play");
        playButton.setOnClickListener(v -> play());
        left.addView(playButton, Ui.fill(this, 12));
        left.addView(Ui.row(this, "Skip intro videos",
            "Starts at the title screen without the Microsoft Game Studios and Lionhead logos.",
            Ui.toggle(this, settings.skipIntros(), on -> settings.put("skip_intros", on))));
        cancelButton = Ui.secondary(this, "Cancel import");
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> { cancelImport = true; cancelButton.setEnabled(false); });
        left.addView(cancelButton, Ui.fill(this, 10));
        LinearLayout imports = new LinearLayout(this);
        imports.setOrientation(LinearLayout.HORIZONTAL);
        isoButton = Ui.secondary(this, "Select game ISO");
        isoButton.setOnClickListener(v -> pick(PICK_ISO));
        zipButton = Ui.secondary(this, "Import ZIP");
        zipButton.setOnClickListener(v -> pick(PICK_ZIP));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        imports.addView(isoButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.leftMargin = Ui.dp(this, 10);
        imports.addView(zipButton, half2);
        left.addView(imports, Ui.fill(this, 10));
        Button drivers = Ui.secondary(this, "GPU drivers / downloads");
        drivers.setOnClickListener(v -> startActivity(new Intent(this, DriverActivity.class)));
        left.addView(drivers, Ui.fill(this, 10));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.addView(left);
        screen.addView(leftScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f));

        // Right: welcome (no game yet) or settings.
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setPadding(Ui.dp(this, 28), 0, 0, 0);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        welcome = buildWelcome();
        settingsColumn = buildSettings();
        right.addView(welcome);
        right.addView(settingsColumn);
        rightScroll.addView(right);
        screen.addView(rightScroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f));
        setContentView(screen);
    }

    private LinearLayout buildWelcome() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.title(this, "Welcome"));
        c.addView(Ui.text(this, "To play, you need your own copy of Fable II for Xbox 360 "
            + "(Game of the Year edition: USA/Europe, German, French, Italian, Russian or Spanish).", 15, Ui.IVORY, false));
        TextView steps = Ui.text(this, "\n1.  Make a disc image (.iso) of your game and copy it to this device.\n"
            + "2.  Tap Select game ISO and choose the file.\n"
            + "3.  Wait for the files to be installed (about 7 GB free space is needed), then tap Play.\n\n"
            + "The ISO itself is not modified. After installing you may delete it to free space.", 14, Ui.MUTED, false);
        c.addView(steps);
        return c;
    }

    private LinearLayout buildSettings() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout graphics = Ui.card(this);
        graphics.addView(Ui.title(this, "Graphics"));
        int scale = settings.resolutionScale();
        graphics.addView(Ui.row(this, "Resolution scale",
            "1x is the Xbox 360's native 1120×720. 2x and 3x render more pixels and need a much faster GPU.",
            Ui.segmented(this, new String[] { "1x", "2x", "3x" }, scale - 1, i -> settings.put("resolution_scale", i + 1))));
        int heightIndex = 0;
        for (int i = 0; i < Settings.RENDER_HEIGHTS.length; ++i)
            if (Settings.RENDER_HEIGHTS[i] == settings.renderHeight()) heightIndex = i;
        graphics.addView(Ui.stackedRow(this, "Render resolution",
            "Renders below the native 720p and scales up: faster on the GPU, blurrier image.",
            Ui.segmented(this, new String[] { "720p", "540p", "480p", "360p" }, heightIndex,
                i -> settings.put("render_height", Settings.RENDER_HEIGHTS[i]))));
        graphics.addView(Ui.row(this, "Anti-aliasing (MSAA)",
            "Smoother edges at a large performance cost. Off is recommended on handhelds.",
            Ui.toggle(this, settings.msaa(), on -> settings.put("msaa", on))));
        graphics.addView(Ui.row(this, "Smooth edges (FXAA)",
            "Softens jagged edges and the blocky grass and foliage pattern for about 1% frame rate. Recommended with MSAA off.",
            Ui.toggle(this, settings.fxaa(), on -> settings.put("fxaa", on))));
        graphics.addView(Ui.row(this, "Fine foliage pattern",
            "With MSAA off, draws grass and leaf edges with a finer 4x4 pattern instead of the blocky 2x2 one. No measurable cost.",
            Ui.toggle(this, settings.fineFoliage(), on -> settings.put("fine_foliage", on))));
        graphics.addView(Ui.row(this, "Triple buffering",
            "Allows one more queued frame. May add input latency; leave off unless it improves your experience.",
            Ui.toggle(this, settings.tripleBuffering(), on -> settings.put("triple_buffering", on))));
        graphics.addView(Ui.row(this, "Low latency mode",
            "Stops the game queuing frames ahead of the GPU for less input lag. On some phones (measured on an Exynos/Xclipse one) it cuts the frame rate to a third; off by default.",
            Ui.toggle(this, settings.lowLatency(), on -> settings.put("low_latency", on))));
        column.addView(graphics);

        LinearLayout performance = Ui.card(this);
        performance.addView(Ui.title(this, "Performance"));
        performance.addView(Ui.row(this, "CPU upload cache",
            "Skips re-uploading guest memory pages that did not change and stops write-protecting pages the game rewrites every frame. Less CPU time in the GPU thread.",
            Ui.toggle(this, settings.uploadCache(), on -> settings.put("upload_cache", on))));
        performance.addView(Ui.row(this, "Separate present queue",
            "Presents on its own Vulkan queue so showing a frame never blocks the GPU thread's submissions.",
            Ui.toggle(this, settings.separatePresentQueue(), on -> settings.put("separate_present_queue", on))));
        performance.addView(Ui.row(this, "Fast cores for GPU and audio threads",
            "Keeps the GPU command and audio threads off the slowest CPU cluster and reports the GPU thread's work to Android's performance hints.",
            Ui.toggle(this, settings.fastCores(), on -> settings.put("fast_cores", on))));
        performance.addView(Ui.row(this, "Wake waiting threads on signal",
            "Threads waiting on several events wake when one is signalled instead of checking every millisecond. Turn off together with the options above to run like the original build.",
            Ui.toggle(this, settings.signalWaits(), on -> settings.put("signal_waits", on))));
        performance.addView(Ui.row(this, "Resolve-to-texture shortcut (experimental)",
            "Copies a just-rendered RGBA8 image straight into the texture that reads it back, instead of decoding it from emulated memory again. Unverified: turn off if you see wrong or flickering images.",
            Ui.toggle(this, settings.resolveShortcut(), on -> settings.put("resolve_shortcut", on))));
        performance.addView(Ui.row(this, "Lock-free guest atomics (experimental)",
            "The game's atomic operations rely on the CPU's compare-and-swap alone instead of also queueing all threads on one global lock. Turn off if the game hangs or behaves oddly.",
            Ui.toggle(this, settings.lockFreeAtomics(), on -> settings.put("lock_free_atomics", on))));
        performance.addView(Ui.row(this, "Diagnostic logging",
            "Writes frame timing, late frames, thread CPU, temperature, GPU pass timing and resolve statistics to the log every 5 seconds, plus battery.log. Costs a little performance; leave off for normal play.",
            Ui.toggle(this, settings.diagnosticLogs(), on -> settings.put("diagnostic_logs", on))));
        performance.addView(Ui.row(this, "Thread sampling",
            "With diagnostic logging: records where the busiest thread and the GPU command thread spend their time, and who waits for the emulator's global lock. Makes the log much larger.",
            Ui.toggle(this, settings.threadSamples(), on -> settings.put("thread_samples", on))));
        column.addView(performance, Ui.fill(this, 16));

        LinearLayout controls = Ui.card(this);
        controls.addView(Ui.title(this, "Controls"));
        controls.addView(Ui.row(this, "Touch controls",
            "Auto shows them only when no gamepad is connected.",
            Ui.segmented(this, new String[] { "Auto", "On", "Off" }, settings.touchMode(), i -> settings.put("touch_mode", i))));
        controls.addView(Ui.row(this, "Touch opacity", null,
            Ui.slider(this, 100, Math.round(settings.touchOpacity() * 100), v -> settings.put("touch_opacity", Math.max(0.1f, v / 100f)))));
        controls.addView(Ui.row(this, "Touch button size", null,
            Ui.slider(this, 100, Math.round((settings.touchScale() - 0.6f) / 1.0f * 100),
                v -> settings.put("touch_scale", 0.6f + v / 100f))));
        controls.addView(Ui.row(this, "Vibrate on touch", null,
            Ui.toggle(this, settings.vibration(), on -> settings.put("touch_vibration", on))));
        LinearLayout controlButtons = new LinearLayout(this);
        Button layout = Ui.secondary(this, "Edit touch layout");
        layout.setOnClickListener(v -> startActivity(new Intent(this, TouchLayoutActivity.class)));
        Button remap = Ui.secondary(this, "Remap controller buttons");
        remap.setOnClickListener(v -> startActivity(new Intent(this, RemapActivity.class)));
        controlButtons.addView(layout);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(this, 10);
        controlButtons.addView(remap, gap);
        controls.addView(controlButtons, Ui.fill(this, 6));
        column.addView(controls, Ui.fill(this, 16));

        LinearLayout data = Ui.card(this);
        data.addView(Ui.title(this, "Saves and files"));
        data.addView(Ui.text(this, "Saves, settings and logs are in Android/data/" + BuildConfig.APPLICATION_ID + "/files on "
            + "internal storage. Reinstalling the game files never touches your saves; uninstalling the app deletes them, "
            + "so export a backup first.", 13, Ui.MUTED, false));
        LinearLayout dataButtons = new LinearLayout(this);
        Button export = Ui.secondary(this, "Back up saves");
        export.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "fable2-saves.zip");
            startActivityForResult(intent, EXPORT_SAVES);
        });
        dataButtons.addView(export);
        Button exportLogs = Ui.secondary(this, "Export logs");
        exportLogs.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "fable2-logs.zip");
            startActivityForResult(intent, EXPORT_LOGS);
        });
        LinearLayout.LayoutParams logsGap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        logsGap.leftMargin = Ui.dp(this, 10);
        dataButtons.addView(exportLogs, logsGap);
        data.addView(dataButtons, Ui.fill(this, 10));
        column.addView(data, Ui.fill(this, 16));

        LinearLayout about = Ui.card(this);
        about.addView(Ui.title(this, "About"));
        about.addView(Ui.text(this, "Version " + BuildConfig.VERSION_NAME + ". An unofficial fan project, not affiliated "
            + "with or endorsed by Microsoft or Lionhead Studios. No game content is included: you must own the game. "
            + "Built on the ReXGlue SDK, SDL3 and Mesa Turnip.", 13, Ui.MUTED, false));
        column.addView(about, Ui.fill(this, 16));
        return column;
    }

    private boolean gameReady() {
        File r = root();
        return r != null && new File(r, "default.xex").isFile() && new File(r, "data").isDirectory()
            && new File(r, ".import-complete").isFile();
    }

    private void refresh() {
        boolean ready = gameReady();
        playButton.setEnabled(ready);
        welcome.setVisibility(ready ? View.GONE : View.VISIBLE);
        isoButton.setText(ready ? "Reinstall ISO" : "Select game ISO");
        status.setText(ready ? "Game installed and ready." : "Game not installed yet.");
        status.setTextColor(ready ? Ui.GOOD : Ui.IVORY);
        String hash = getSharedPreferences(Settings.PREFS, MODE_PRIVATE).getString("xex_hash", "");
        if (ready && hash.isEmpty() && !hashing) {
            // Installed by an older build: identify it once in the background.
            hashing = true;
            version.setText("Checking game version\u2026");
            new Thread(() -> {
                try {
                    String h = sha256(new File(root(), "default.xex"));
                    getSharedPreferences(Settings.PREFS, MODE_PRIVATE).edit().putString("xex_hash", h).apply();
                } catch (Exception ignored) {}
                runOnUiThread(() -> { hashing = false; if (!importing) refresh(); });
            }, "XexHash").start();
            playButton.requestFocus();
            return;
        }
        GameVersions.Version v = ready ? GameVersions.find(hash) : null;
        if (!ready) version.setText("Select your Fable II disc image to begin.");
        else if (v == null) { version.setText("Unrecognised game executable (ID " + hash.substring(0, Math.min(12, hash.length())) + "). It may not start; please include this ID when reporting."); version.setTextColor(Ui.WARN); }
        else if (!v.supported) { version.setText(v.name + ": " + v.message); version.setTextColor(Ui.BAD); }
        else { version.setText("Detected: Fable II " + v.name); version.setTextColor(Ui.MUTED); }
        if (ready) playButton.requestFocus(); else isoButton.requestFocus();
    }

    private void play() {
        try { settings.writeGameConfig(root()); }
        catch (IOException error) { toast("Could not save settings: " + error.getMessage()); return; }
        startActivity(new Intent(this, GameActivity.class));
    }

    private void pick(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, request);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_SAVES) { exportSaves(uri); return; }
        if (request == EXPORT_LOGS) { exportLogs(uri); return; }
        if (request == PICK_ISO || request == PICK_ZIP) startImport(uri, request == PICK_ISO);
    }

    private void setImporting(boolean on) {
        importing = on;
        cancelImport = false;
        isoButton.setEnabled(!on); zipButton.setEnabled(!on); playButton.setEnabled(!on && gameReady());
        cancelButton.setEnabled(true);
        cancelButton.setVisibility(on ? View.VISIBLE : View.GONE);
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (on) cancelButton.requestFocus();
    }

    private void showProgress(String text, long done, long total) {
        runOnUiThread(() -> {
            status.setText(text);
            status.setTextColor(Ui.IVORY);
            version.setText(total > 0 ? (done >> 20) + " / " + (total >> 20) + " MiB" : (done >> 20) + " MiB");
            version.setTextColor(Ui.MUTED);
            progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int) (done * 1000 / total));
        });
    }

    private void startImport(Uri uri, boolean iso) {
        setImporting(true);
        showProgress("Preparing…", 0, 0);
        new Thread(() -> {
            String error = null;
            try {
                File target = root();
                if (target == null) throw new IOException("App storage is unavailable");
                new File(target, ".import-complete").delete();
                if (iso) importIso(uri, target); else importZip(uri, target);
                if (!new File(target, "default.xex").isFile() || !new File(target, "data").isDirectory())
                    throw new IOException("The game files were not found (default.xex and data/)");
                showProgress("Verifying game executable…", 0, 0);
                String hash = sha256(new File(target, "default.xex"));
                getSharedPreferences(Settings.PREFS, MODE_PRIVATE).edit().putString("xex_hash", hash).apply();
                if (!new File(target, ".import-complete").createNewFile()) throw new IOException("Cannot mark import complete");
            } catch (InterruptedIOException cancelled) {
                error = "Import cancelled. Select the ISO again to install.";
            } catch (Exception e) {
                error = "Import failed: " + e.getMessage();
            }
            final String message = error;
            runOnUiThread(() -> {
                setImporting(false);
                refresh();
                if (message != null) { status.setText(message); status.setTextColor(Ui.BAD); }
            });
        }, "GameImport").start();
    }

    /** Removes previously installed game files (never saves or settings). */
    private static void removeGameFiles(File target) throws IOException {
        for (String name : new String[] { "default.xex", "data", "nxeart" }) deleteTree(new File(target, name));
        // Older ZIP imports could include the unused system update; remove it if allowed.
        try { deleteTree(new File(target, "$SystemUpdate")); } catch (IOException ignored) {}
    }

    private static void deleteTree(File f) throws IOException {
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) deleteTree(c);
        if (f.exists() && !f.delete()) throw new IOException("Cannot delete " + f.getName());
    }

    private void importIso(Uri uri, File target) throws IOException {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
             FileInputStream stream = new FileInputStream(pfd.getFileDescriptor());
             FileChannel channel = stream.getChannel()) {
            XisoReader reader;
            try { reader = new XisoReader(channel); }
            catch (IOException e) {
                throw new IOException(e.getMessage() + ". If the ISO is on cloud or network storage, copy it to the device first");
            }
            List<XisoReader.Entry> entries = reader.list();
            long total = 0;
            for (XisoReader.Entry e : entries) if (!skipped(e.path)) total += e.size;
            boolean hasXex = false;
            for (XisoReader.Entry e : entries) hasXex |= e.path.equalsIgnoreCase("default.xex");
            if (!hasXex) throw new IOException("This disc image has no default.xex; it is not a game disc");
            long existing = sizeOf(new File(target, "data")) + sizeOf(new File(target, "default.xex"));
            long free = new StatFs(target.getPath()).getAvailableBytes();
            if (free + existing < total + (256L << 20))
                throw new IOException("Not enough free space: " + ((total + (256L << 20)) >> 20) + " MiB needed, "
                    + ((free + existing) >> 20) + " MiB available");
            showProgress("Removing old game files…", 0, 0);
            removeGameFiles(target);
            String prefix = target.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[4 << 20];
            long[] done = { 0 };
            long[] lastUpdate = { 0 };
            final long totalBytes = total;
            for (XisoReader.Entry e : entries) {
                if (skipped(e.path)) continue;
                if (cancelImport) throw new InterruptedIOException();
                File out = new File(target, e.path);
                if (!out.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid path on disc");
                if (e.directory) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create " + e.path);
                    continue;
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + e.path);
                File partial = new File(parent, out.getName() + ".importing");
                final String path = e.path;
                long before = done[0];
                try {
                    reader.extract(e, partial, buffer, () -> {
                        if (cancelImport) throw new RuntimeException(new InterruptedIOException());
                        long now = System.nanoTime();
                        if (now - lastUpdate[0] > 200_000_000L) {
                            lastUpdate[0] = now;
                            showProgress("Installing " + path, before, totalBytes);
                        }
                    });
                } catch (RuntimeException wrapped) {
                    partial.delete();
                    if (wrapped.getCause() instanceof InterruptedIOException) throw (InterruptedIOException) wrapped.getCause();
                    throw wrapped;
                } catch (IOException failure) { partial.delete(); throw failure; }
                if (!partial.renameTo(out)) { partial.delete(); throw new IOException("Cannot save " + e.path); }
                done[0] += e.size;
                showProgress("Installing game files…", done[0], totalBytes);
            }
        }
    }

    // The system update partition is useless to the recompilation.
    private static boolean skipped(String path) { return path.startsWith("$SystemUpdate"); }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long total = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) total += sizeOf(c);
        return total;
    }

    private void importZip(Uri uri, File target) throws IOException {
        String prefix = target.getCanonicalPath() + File.separator;
        long total = 0, lastUpdate = 0;
        long size = -1;
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) { size = pfd.getStatSize(); }
        catch (Exception ignored) {}
        boolean cleared = false;
        try (InputStream source = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source, 1 << 20))) {
            byte[] buffer = new byte[1 << 20];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (cancelImport) throw new InterruptedIOException();
                String name = entry.getName().replace('\\', '/');
                // Only game content can be imported; preserve saves and settings.
                if (!(name.equals("default.xex") || name.startsWith("data/")
                    || name.equals("nxeart") || name.startsWith("nxeart/"))) continue;
                if (!cleared) { removeGameFiles(target); cleared = true; }
                File out = new File(target, name);
                if (!out.getCanonicalPath().startsWith(prefix)) throw new IOException("Invalid archive path");
                if (entry.isDirectory()) {
                    if (!out.isDirectory() && !out.mkdirs()) throw new IOException("Cannot create directory");
                    continue;
                }
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create directory");
                File partial = new File(parent, out.getName() + ".importing");
                try (OutputStream dest = new BufferedOutputStream(new FileOutputStream(partial))) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        if (cancelImport) throw new InterruptedIOException();
                        dest.write(buffer, 0, count); total += count;
                        if (total - lastUpdate > (32 << 20)) {
                            lastUpdate = total;
                            // Compressed size is unknown per entry; show uncompressed progress.
                            showProgress("Installing " + name, total, size > 0 ? Math.max(size, total) : 0);
                        }
                    }
                } catch (IOException error) { partial.delete(); throw error; }
                if (!partial.renameTo(out)) { partial.delete(); throw new IOException("Cannot save " + name); }
                zip.closeEntry();
            }
        }
        if (!cleared) throw new IOException("ZIP must contain default.xex and data/ at its root");
    }

    private static String sha256(File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte b : digest.digest()) s.append(String.format("%02x", b));
        return s.toString();
    }

    private void exportSaves(Uri uri) {
        new Thread(() -> {
            String message;
            try (OutputStream raw = getContentResolver().openOutputStream(uri);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
                File base = root();
                int[] count = { 0 };
                for (String name : new String[] { "saves", "fable_2.toml", "fable2_config.toml" })
                    addToZip(zip, base, new File(base, name), count);
                message = "Backed up " + count[0] + " files.";
            } catch (Exception e) { message = "Backup failed: " + e.getMessage(); }
            final String m = message;
            runOnUiThread(() -> toast(m));
        }, "SaveExport").start();
    }

    // Logs for bug reports: the game's own logs and config (top-level text files, no saves or
    // game data), this app's logcat (apps can read their own lines, including the previous
    // process's "Fatal signal" line), and Android's records of how recent processes ended.
    // For a native crash, getTraceInputStream() is the tombstone (protobuf with readable
    // backtrace strings); for an ANR it is the thread dump.
    private void exportLogs(Uri uri) {
        new Thread(() -> {
            String message;
            try (OutputStream raw = getContentResolver().openOutputStream(uri);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
                File base = root();
                int[] count = { 0 };
                File[] files = base == null ? null : base.listFiles();
                if (files != null) for (File f : files) {
                    String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!f.isFile() || f.length() > (256L << 20)) continue;
                    if (n.endsWith(".log") || n.endsWith(".toml") || n.endsWith(".json") || n.endsWith(".txt"))
                        addToZip(zip, base, f, count);
                }
                // The game's own logs go to logs/ (sequential names); keep the newest few.
                File[] logs = base == null ? null : new File(base, "logs").listFiles();
                if (logs != null) {
                    java.util.Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (int i = 0; i < logs.length && i < 5; ++i)
                        if (logs[i].isFile() && logs[i].length() <= (256L << 20)) addToZip(zip, base, logs[i], count);
                }
                zip.putNextEntry(new ZipEntry("device.txt"));
                zip.write(("app " + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_NAME + "\n"
                    + "device " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + " soc " + android.os.Build.SOC_MODEL + " android " + android.os.Build.VERSION.RELEASE
                    + " sdk " + android.os.Build.VERSION.SDK_INT + "\n"
                    + "gpu " + GpuDetector.renderer() + "\ndriver " + settings.driver() + "\n").getBytes("UTF-8"));
                zip.closeEntry();
                try {
                    Process logcat = new ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
                    zip.putNextEntry(new ZipEntry("logcat.txt"));
                    try (InputStream in = logcat.getInputStream()) {
                        byte[] buffer = new byte[1 << 16];
                        for (int n; (n = in.read(buffer)) > 0; ) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                    ++count[0];
                } catch (IOException e) { /* logcat unavailable */ }
                android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
                List<android.app.ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 10);
                StringBuilder summary = new StringBuilder();
                java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                int index = 0;
                for (android.app.ApplicationExitInfo e : exits) {
                    summary.append(index).append(": ").append(time.format(new java.util.Date(e.getTimestamp())))
                        .append(" reason=").append(e.getReason()).append(" status=").append(e.getStatus())
                        .append(" importance=").append(e.getImportance()).append(" pss_kb=").append(e.getPss())
                        .append(" rss_kb=").append(e.getRss()).append(" process=").append(e.getProcessName())
                        .append(" description=").append(e.getDescription()).append('\n');
                    try (InputStream trace = e.getTraceInputStream()) {
                        if (trace != null) {
                            zip.putNextEntry(new ZipEntry("exit-" + index + "-reason" + e.getReason() + ".trace"));
                            byte[] buffer = new byte[1 << 16];
                            for (int n; (n = trace.read(buffer)) > 0; ) zip.write(buffer, 0, n);
                            zip.closeEntry();
                            ++count[0];
                        }
                    } catch (IOException ignored) {}
                    ++index;
                }
                zip.putNextEntry(new ZipEntry("exit-reasons.txt"));
                zip.write(summary.toString().getBytes("UTF-8"));
                zip.closeEntry();
                message = "Exported " + count[0] + " log files.";
            } catch (Exception e) { message = "Log export failed: " + e.getMessage(); }
            final String m = message;
            runOnUiThread(() -> toast(m));
        }, "LogExport").start();
    }

    private static void addToZip(ZipOutputStream zip, File base, File f, int[] count) throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) addToZip(zip, base, c, count);
            return;
        }
        if (!f.isFile()) return;
        String name = base.toURI().relativize(f.toURI()).getPath();
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) zip.write(buffer, 0, n);
        }
        zip.closeEntry();
        ++count[0];
    }
}
