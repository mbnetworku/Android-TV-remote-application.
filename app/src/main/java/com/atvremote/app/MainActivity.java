package com.atvremote.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import android.widget.FrameLayout;

import com.atvremote.app.ui.DpadView;
import com.atvremote.app.ui.Feedback;
import com.atvremote.app.ui.TouchpadView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.\\-]+(:\\d{1,5})?$");

    private SharedPreferences prefs;
    private DeviceStore store;
    /** One controller per saved TV, so every TV stays connected and switching is instant. */
    private final Map<String, TvController> controllers = new HashMap<>();
    private TvController tv; // the selected TV, null when none is saved
    private final NetworkScanner autoScanner = new NetworkScanner();
    private boolean scanning;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View statusDot, deviceButton;
    private TextView deviceName, deviceStatus, banner, pageCount;
    private LinearLayout pageDots;
    private DpadView dpad;
    private TouchpadView touchpad;
    private TextView padMode;
    private long lastHint;
    private Runnable pickerRefresh; // refreshes the open TV picker, if any

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        store = new DeviceStore(prefs);

        statusDot = findViewById(R.id.statusDot);
        deviceButton = findViewById(R.id.deviceButton);
        deviceName = findViewById(R.id.deviceName);
        deviceStatus = findViewById(R.id.deviceStatus);
        banner = findViewById(R.id.banner);
        pageCount = findViewById(R.id.pageCount);
        pageDots = findViewById(R.id.pageDots);
        dpad = findViewById(R.id.dpad);
        touchpad = findViewById(R.id.touchpad);
        padMode = findViewById(R.id.btnPadMode);

        Feedback.init(this, prefs);
        setUpHeaderSwipe();

        bindKey(R.id.btnPower, KeyEvent.KEYCODE_POWER);
        bindKey(R.id.btnBack, KeyEvent.KEYCODE_BACK);
        bindKey(R.id.btnHome, KeyEvent.KEYCODE_HOME);
        bindKey(R.id.btnMenu, KeyEvent.KEYCODE_MENU);
        bindKey(R.id.btnSettings, KeyEvent.KEYCODE_SETTINGS);
        bindKey(R.id.btnMute, KeyEvent.KEYCODE_VOLUME_MUTE);
        bindKey(R.id.btnInput, KeyEvent.KEYCODE_TV_INPUT);
        bindKey(R.id.btnPlay, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        bindRepeat(R.id.btnRewind, KeyEvent.KEYCODE_MEDIA_REWIND);
        bindRepeat(R.id.btnFf, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        bindRepeat(R.id.volUp, KeyEvent.KEYCODE_VOLUME_UP);
        bindRepeat(R.id.volDown, KeyEvent.KEYCODE_VOLUME_DOWN);
        bindRepeat(R.id.chUp, KeyEvent.KEYCODE_CHANNEL_UP);
        bindRepeat(R.id.chDown, KeyEvent.KEYCODE_CHANNEL_DOWN);

        DpadView.Listener pad = new DpadView.Listener() {
            @Override
            public void onKey(int keyCode) {
                send(keyCode);
            }

            @Override
            public void onLongKey(int keyCode) {
                if (ensureConnected()) tv.longPressKey(keyCode);
            }
        };
        dpad.setListener(pad);
        touchpad.setListener(pad);
        setTouchpad(prefs.getBoolean("touchpad", false));
        padMode.setOnClickListener(v -> setTouchpad(touchpad.getVisibility() != View.VISIBLE));

        findViewById(R.id.btnKeyboard).setOnClickListener(v -> {
            if (ensureConnected()) showKeyboard();
        });
        findViewById(R.id.btnPrefs).setOnClickListener(v -> {
            Feedback.tap(v);
            showSettings();
        });
        findViewById(R.id.volLabel).setOnClickListener(v -> showVolumeSlider());
        findViewById(R.id.btnApps).setOnClickListener(v -> {
            if (ensureConnected()) showApps();
        });

        if (store.current() != null) tv = controllerFor(store.current());
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Connect every saved TV (selected one first) so swiping between them is instant.
        DeviceStore.Device cur = store.current();
        if (cur != null) connectIfIdle(cur);
        for (DeviceStore.Device d : store.all()) connectIfIdle(d);
        autoDiscover();
    }

    @Override
    protected void onStop() {
        super.onStop();
        autoScanner.cancel();
        scanning = false;
        if (!isChangingConfigurations()) {
            for (TvController c : controllers.values()) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- devices

    private TvController controllerFor(DeviceStore.Device d) {
        TvController c = controllers.get(d.host);
        if (c != null) return c;
        TvController created = new TvController(this);
        created.setListener((state, title, detail) -> {
            if (state == TvController.State.CONNECTED) store.setName(d.host, title);
            if (created == tv) render();
            else renderDots();
            if (pickerRefresh != null) pickerRefresh.run();
        });
        controllers.put(d.host, created);
        return created;
    }

    private void connectIfIdle(DeviceStore.Device d) {
        TvController c = controllerFor(d);
        if (c.getState() != TvController.State.DISCONNECTED) return;
        String host = d.host;
        int port = TvController.DEFAULT_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException ignored) {
            }
            host = host.substring(0, colon);
        }
        c.connect(host, port);
    }

    private static String normalize(String host) {
        return host.endsWith(":" + TvController.DEFAULT_PORT)
                ? host.substring(0, host.length() - 5) : host;
    }

    private void switchTo(int index) {
        if (index < 0 || index >= store.size()) return;
        boolean changed = index != store.currentIndex() || tv == null;
        store.select(index);
        DeviceStore.Device d = store.current();
        tv = controllerFor(d);
        connectIfIdle(d);
        render();
        if (changed) Feedback.tap(deviceButton);
    }

    private void removeDevice(int index) {
        DeviceStore.Device d = store.all().get(index);
        TvController c = controllers.remove(d.host);
        if (c != null) c.disconnect();
        store.remove(index);
        DeviceStore.Device cur = store.current();
        tv = cur == null ? null : controllerFor(cur);
        if (cur != null) connectIfIdle(cur);
        render();
    }

    /** Adds a TV (found or typed) and selects it. */
    private void addAndSelect(String host) {
        switchTo(store.add(normalize(host)));
    }

    /** Scans the Wi-Fi in the background and saves every TV it finds — no typing needed. */
    private void autoDiscover() {
        if (scanning) return;
        scanning = true;
        render();
        autoScanner.start(this, TvController.DEFAULT_PORT, new NetworkScanner.Callback() {
            @Override
            public void onFound(String host) {
                if (store.indexOf(host) >= 0) return;
                int i = store.add(host);
                DeviceStore.Device d = store.all().get(i);
                if (tv == null) {
                    switchTo(i);
                } else {
                    connectIfIdle(d);
                    Toast.makeText(MainActivity.this, "New TV found: " + host + " · swipe to switch",
                            Toast.LENGTH_SHORT).show();
                }
                render();
                if (pickerRefresh != null) pickerRefresh.run();
            }

            @Override
            public void onDone(int found) {
                scanning = false;
                render();
                if (pickerRefresh != null) pickerRefresh.run();
            }
        });
    }

    /** Swipe the TV name left/right to change TV; tap it to open the list. */
    private void setUpHeaderSwipe() {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                showDevicePicker();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) < Math.abs(vy) || store.size() < 2) return false;
                int n = store.size();
                int next = (store.currentIndex() + (vx < 0 ? 1 : -1) + n) % n;
                slideName(vx < 0);
                switchTo(next);
                return true;
            }
        });
        deviceButton.setOnTouchListener((v, e) -> {
            v.onTouchEvent(e); // ripple
            gd.onTouchEvent(e);
            return true;
        });
    }

    private void slideName(boolean toLeft) {
        float d = toLeft ? 60 : -60;
        deviceName.setTranslationX(d);
        deviceName.setAlpha(0f);
        deviceName.animate().translationX(0).alpha(1f).setDuration(180).start();
    }

    // ---------------------------------------------------------------- rendering

    private static int stateColor(TvController.State s) {
        switch (s) {
            case CONNECTED: return 0xFF3DD68C;
            case CONNECTING:
            case AWAITING_APPROVAL: return 0xFFF5A524;
            default: return 0xFF5C6370;
        }
    }

    private static GradientDrawable dot(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void render() {
        DeviceStore.Device d = store.current();
        boolean approving = false;
        if (d == null || tv == null) {
            statusDot.setBackground(dot(0xFF5C6370));
            deviceName.setText(scanning ? "Looking for TVs…" : "No TV found");
            deviceStatus.setText(scanning ? "Searching your Wi-Fi" : "Tap to search again or add one");
        } else {
            TvController.State s = tv.getState();
            statusDot.setBackground(dot(stateColor(s)));
            deviceName.setText(d.label());
            String detail = tv.getDetail();
            if (detail == null) detail = "Not connected";
            deviceStatus.setText(normalize(d.host) + " · " + detail);
            approving = s == TvController.State.AWAITING_APPROVAL;
        }
        banner.setVisibility(approving ? View.VISIBLE : View.GONE);
        // Keep the phone awake while the TV shows its "Allow debugging?" dialog.
        if (approving) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderDots();
        renderFavorites();
        renderVolume();
    }

    // ---------------------------------------------------------------- favorites (saved per TV)

    private String favKey() {
        DeviceStore.Device d = store.current();
        return d == null ? null : "fav_" + normalize(d.host);
    }

    /** Favorites of the selected TV, as "component\tlabel" entries in order. */
    private List<String[]> loadFavorites() {
        List<String[]> out = new ArrayList<>();
        String key = favKey();
        if (key == null) return out;
        for (String line : prefs.getString(key, "").split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2) out.add(p);
        }
        return out;
    }

    private void saveFavorites(List<String[]> favs) {
        String key = favKey();
        if (key == null) return;
        StringBuilder sb = new StringBuilder();
        for (String[] f : favs) sb.append(f[0]).append('\t').append(f[1]).append('\n');
        prefs.edit().putString(key, sb.toString()).apply();
    }

    private boolean isFavorite(String component) {
        for (String[] f : loadFavorites()) if (f[0].equals(component)) return true;
        return false;
    }

    /** Adds or removes an app; returns true if it is now a favorite. */
    private boolean toggleFavorite(TvController.TvApp app) {
        List<String[]> favs = loadFavorites();
        for (int i = 0; i < favs.size(); i++) {
            if (favs.get(i)[0].equals(app.component)) {
                favs.remove(i);
                saveFavorites(favs);
                renderFavorites();
                return false;
            }
        }
        favs.add(new String[]{app.component, app.label});
        saveFavorites(favs);
        renderFavorites();
        return true;
    }

    private void renderFavorites() {
        View scroll = findViewById(R.id.favScroll);
        LinearLayout row = findViewById(R.id.favRow);
        row.removeAllViews();
        List<String[]> favs = loadFavorites();
        scroll.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        for (String[] f : favs) {
            TvController.TvApp app = new TvController.TvApp(f[0], f[0].split("/")[0], f[1]);
            app.icon = TvController.cachedIcon(this, app.packageName);
            // Tile: app icon in a glass circle, short name underneath.
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            chip.setContentDescription("Open " + app.label);
            FrameLayout circle = new FrameLayout(this);
            circle.setBackgroundResource(R.drawable.btn_round);
            View icon = appIcon(app, 34);
            circle.addView(icon, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
            chip.addView(circle, new LinearLayout.LayoutParams(dp(56), dp(56)));
            TextView name = text(app.label, 11, 0xFFC9CFDA);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setMaxWidth(dp(68));
            name.setPadding(0, dp(4), 0, 0);
            chip.addView(name);
            chip.setOnClickListener(v -> {
                Feedback.tap(v);
                if (ensureConnected()) {
                    tv.launchApp(app);
                    Toast.makeText(this, "Opening " + app.label, Toast.LENGTH_SHORT).show();
                }
            });
            chip.setOnLongClickListener(v -> {
                dialog().setMessage("Remove " + app.label + " from favorites?")
                        .setPositiveButton("Remove", (x, y) -> toggleFavorite(app))
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip, lp);
        }
    }

    /** The app's real icon from the TV, or a letter badge until it has been fetched. */
    private View appIcon(TvController.TvApp app, int sizeDp) {
        if (app.icon != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(app.icon);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            return iv;
        }
        TextView letter = text(app.label.isEmpty() ? "?" : app.label.substring(0, 1).toUpperCase(), 16, 0xFFFFFFFF);
        letter.setGravity(Gravity.CENTER);
        letter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        letter.setBackground(g);
        return letter;
    }

    // ---------------------------------------------------------------- volume

    private final Runnable volumeRefresh = () -> {
        if (tv != null) tv.requestVolume();
    };

    private void renderVolume() {
        TextView label = findViewById(R.id.volLabel);
        int pct = tv == null || !tv.isConnected() ? -1 : tv.getVolumePercent();
        label.setText(pct < 0 ? "VOL" : "VOL\n" + pct + "%");
    }

    /** Slider for setting an exact TV volume. */
    private void showVolumeSlider() {
        if (!ensureConnected()) return;
        if (!tv.supportsVolume() || tv.getVolumeMax() <= 0) {
            Toast.makeText(this, "This TV doesn't report its volume", Toast.LENGTH_SHORT).show();
            return;
        }
        final TvController target = tv;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        TextView pct = text(target.getVolumePercent() + "%", 32, 0xFFECEFF4);
        pct.setGravity(Gravity.CENTER);
        box.addView(pct);
        android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(target.getVolumeMax());
        bar.setProgress(Math.max(0, target.getVolumeStep()));
        box.addView(bar);
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                pct.setText(Math.round(p * 100f / s.getMax()) + "%");
                if (fromUser) {
                    Feedback.tick(s);
                    target.setVolumeStep(p);
                }
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        dialog().setTitle("TV volume").setView(box).setPositiveButton("Done", null).show();
    }

    // ---------------------------------------------------------------- settings

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        android.widget.Switch vib = new android.widget.Switch(this);
        vib.setText("Vibrate on button press");
        vib.setTextColor(0xFFECEFF4);
        vib.setChecked(Feedback.haptics());
        vib.setPadding(0, dp(12), 0, dp(12));
        android.widget.Switch snd = new android.widget.Switch(this);
        snd.setText("Tick sound on button press");
        snd.setTextColor(0xFFECEFF4);
        snd.setChecked(Feedback.sound());
        snd.setPadding(0, dp(12), 0, dp(12));
        box.addView(vib);
        box.addView(snd);
        vib.setOnCheckedChangeListener((b, on) -> {
            Feedback.set(prefs, on, snd.isChecked());
            Feedback.tap(b);
        });
        snd.setOnCheckedChangeListener((b, on) -> {
            Feedback.set(prefs, vib.isChecked(), on);
            Feedback.tap(b);
        });
        dialog().setTitle("Settings").setView(box).setPositiveButton("Done", null).show();
    }

    /** One dot per TV, colored by its connection; the selected one is larger. */
    private void renderDots() {
        int n = store.size();
        pageCount.setText(n > 1 ? (store.currentIndex() + 1) + "/" + n : "");
        pageDots.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
        pageDots.removeAllViews();
        if (n < 2) return;
        for (int i = 0; i < n; i++) {
            final int index = i;
            TvController c = controllers.get(store.all().get(i).host);
            int color = c == null ? 0xFF5C6370 : stateColor(c.getState());
            boolean selected = i == store.currentIndex();
            View v = new View(this);
            v.setBackground(dot(color));
            v.setAlpha(selected ? 1f : 0.55f);
            int size = dp(selected ? 10 : 7);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(5), 0, dp(5), 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            v.setOnClickListener(x -> switchTo(index));
            pageDots.addView(v, lp);
        }
    }

    // ---------------------------------------------------------------- keys

    private void bindKey(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> {
            Feedback.tap(v);
            send(keyCode);
        });
    }

    /** Press once on touch, then repeat while held (volume, channel, seek). */
    private void bindRepeat(int id, int keyCode) {
        View v = findViewById(id);
        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                send(keyCode);
                handler.postDelayed(this, 130);
            }
        };
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.drawableHotspotChanged(e.getX(), e.getY());
                    view.setPressed(true);
                    Feedback.tap(view);
                    send(keyCode);
                    handler.postDelayed(repeat, 450);
                    return true;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(repeat);
                    view.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    private void send(int keyCode) {
        if (ensureConnected()) {
            tv.pressKey(keyCode);
            afterVolumeKey(keyCode);
        }
    }

    /** Re-reads the TV volume shortly after a volume key so the % stays correct. */
    private void afterVolumeKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            handler.removeCallbacks(volumeRefresh);
            handler.postDelayed(volumeRefresh, 250);
        }
    }

    /** True when connected; otherwise reconnects / opens the TV list and says so once. */
    private boolean ensureConnected() {
        if (tv != null && tv.isConnected()) return true;
        long now = System.currentTimeMillis();
        if (now - lastHint < 2500) return false;
        lastHint = now;
        if (tv == null) {
            showDevicePicker();
        } else if (tv.getState() == TvController.State.DISCONNECTED) {
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show();
            connectIfIdle(store.current());
        } else {
            Toast.makeText(this, "Still connecting…", Toast.LENGTH_SHORT).show();
        }
        return false;
    }

    /** Phone volume buttons control the TV while connected. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && tv != null && tv.isConnected()) {
            tv.pressKey(keyCode);
            afterVolumeKey(keyCode);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
                && tv != null && tv.isConnected()) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void setTouchpad(boolean on) {
        touchpad.setVisibility(on ? View.VISIBLE : View.GONE);
        dpad.setVisibility(on ? View.GONE : View.VISIBLE);
        padMode.setCompoundDrawablesWithIntrinsicBounds(0, on ? R.drawable.ic_dpad : R.drawable.ic_touchpad, 0, 0);
        padMode.setText(on ? "D-pad" : "Touchpad");
        padMode.setContentDescription(on ? "Switch to D-pad" : "Switch to touchpad");
        prefs.edit().putBoolean("touchpad", on).apply();
    }

    // ---------------------------------------------------------------- dialogs

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** All saved TVs (found automatically): tap to switch, hold to remove; scan or add by IP. */
    private void showDevicePicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        TextView status = text("", 13, 0xFF8A93A6);
        status.setPadding(0, 0, 0, dp(8));
        box.addView(status);

        List<DeviceStore.Device> items = store.all();
        ArrayAdapter<DeviceStore.Device> adapter = new ArrayAdapter<DeviceStore.Device>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                DeviceStore.Device d = getItem(position);
                TvController c = controllers.get(d.host);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText((position == store.currentIndex() ? "▶  " : "") + d.label());
                String state = c == null ? "" : " · " + (c.getDetail() != null ? c.getDetail() : "");
                t2.setText(normalize(d.host) + state);
                t2.setTextColor(c == null ? 0xFF8A93A6 : stateColor(c.getState()));
                return v;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        EditText ip = new EditText(this);
        ip.setHint("Not listed? Type its IP, e.g. 192.168.1.20");
        ip.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ip.setSingleLine(true);
        box.addView(ip);

        TextView help = text("TVs on your Wi-Fi are found and added automatically. If yours is missing, "
                + "turn on Developer options → Network/USB debugging on the TV.", 12, 0xFF8A93A6);
        help.setPadding(0, dp(6), 0, 0);
        box.addView(help);

        AlertDialog d = dialog()
                .setTitle("Your TVs")
                .setView(box)
                .setPositiveButton("Add IP", null)
                .setNeutralButton("Scan", null)
                .setNegativeButton("Close", null)
                .create();

        Runnable refresh = () -> {
            adapter.notifyDataSetChanged();
            int n = store.size();
            if (scanning) status.setText("Searching your Wi-Fi for TVs…");
            else if (n == 0) status.setText("No TVs found yet. Tap Scan, or type the IP below.");
            else status.setText(n + (n == 1 ? " TV" : " TVs") + " · tap to switch · hold to remove");
        };
        pickerRefresh = refresh;
        refresh.run();

        list.setOnItemClickListener((p, v, pos, id) -> {
            switchTo(pos);
            d.dismiss();
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            DeviceStore.Device dev = store.all().get(pos);
            dialog().setMessage("Remove " + dev.label() + " (" + normalize(dev.host) + ")?")
                    .setPositiveButton("Remove", (x, y) -> {
                        removeDevice(pos);
                        refresh.run();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return true;
        });
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                String h = ip.getText().toString().trim();
                if (!HOST.matcher(h).matches()) {
                    ip.setError("Enter an IP address like 192.168.1.20");
                    return;
                }
                addAndSelect(h);
                d.dismiss();
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
                autoDiscover();
                refresh.run();
            });
        });
        d.setOnDismissListener(x -> pickerRefresh = null);
        d.show();
        if (store.size() == 0) autoDiscover();
    }

    private void showKeyboard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(text("Select a text field on the TV first. Letters are sent as you type.", 13, 0xFF8A93A6));

        final TvController target = tv;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_SEND);
        field.setHint("Type here");
        box.addView(field);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        Button del = new Button(this);
        del.setText("⌫ Delete");
        del.setOnClickListener(v -> target.pressKey(KeyEvent.KEYCODE_DEL));
        Button enter = new Button(this);
        enter.setText("Enter ⏎");
        enter.setOnClickListener(v -> target.pressKey(KeyEvent.KEYCODE_ENTER));
        row.addView(del);
        row.addView(enter);
        box.addView(row);

        field.addTextChangedListener(new TextWatcher() {
            private String last = "";

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String now = s.toString();
                int p = 0;
                while (p < now.length() && p < last.length() && now.charAt(p) == last.charAt(p)) p++;
                for (int i = p; i < last.length(); i++) target.pressKey(KeyEvent.KEYCODE_DEL);
                if (p < now.length()) target.sendText(now.substring(p));
                last = now;
            }
        });
        field.setOnEditorActionListener((v, actionId, e) -> {
            target.pressKey(KeyEvent.KEYCODE_ENTER);
            return true;
        });

        AlertDialog d = dialog().setTitle("Keyboard").setView(box).setPositiveButton("Done", null).create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        d.show();
        field.requestFocus();
    }

    private void showApps() {
        final TvController target = tv;
        List<TvController.TvApp> apps = new ArrayList<>();
        ArrayAdapter<TvController.TvApp> adapter = new ArrayAdapter<TvController.TvApp>(
                this, android.R.layout.simple_list_item_1, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TvController.TvApp app = getItem(position);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(20), dp(10), dp(20), dp(10));
                row.addView(appIcon(app, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(14), 0, 0, 0);
                texts.addView(text(app.label, 16, 0xFFECEFF4));
                texts.addView(text(app.packageName, 11, 0xFF8A93A6));
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView star = text(isFavorite(app.component) ? "★" : "☆", 22,
                        isFavorite(app.component) ? 0xFFF5C451 : 0xFF8A93A6);
                row.addView(star);
                return row;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            TvController.TvApp app = apps.get(pos);
            boolean added = toggleFavorite(app);
            Feedback.longPress(v);
            Toast.makeText(this, app.label + (added ? " added to favorites" : " removed from favorites"),
                    Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
            return true;
        });
        TextView hint = text("Tap to open · hold to add ★ favorite", 12, 0xFF8A93A6);
        hint.setPadding(dp(24), 0, dp(24), dp(4));
        TextView empty = text("Loading apps from the TV…", 14, 0xFF8A93A6);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(32), 0, dp(32));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(hint);
        box.addView(empty);
        box.addView(list);

        AlertDialog d = dialog().setTitle("Open an app").setView(box).setNegativeButton("Close", null).create();
        list.setOnItemClickListener((p, v, pos, id) -> {
            target.launchApp(apps.get(pos));
            d.dismiss();
        });
        d.show();
        target.loadApps(result -> {
            apps.addAll(result);
            adapter.notifyDataSetChanged();
            if (result.isEmpty()) empty.setText("Couldn't read the TV's app list.");
            else empty.setVisibility(View.GONE);
        });
    }
}
