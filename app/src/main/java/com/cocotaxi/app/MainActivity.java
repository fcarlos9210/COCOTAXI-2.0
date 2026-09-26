package com.cocotaxi.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Native reference-inspired UI: live figures replace all illustrative values. */
public final class MainActivity extends Activity {
  private static java.lang.ref.WeakReference<MainActivity> foreground =
      new java.lang.ref.WeakReference<>(null);

  /** Only the resumed Coco activity can be minimized by its own floating icon. */
  public static boolean minimizeFromOverlay() {
    MainActivity activity = foreground.get();
    return activity != null && !activity.isFinishing() && !activity.isDestroyed()
        && activity.moveTaskToBack(true);
  }

  private CocoStore.Session dashboardSession() {
    CocoStore.Session session = CocoStore.get(this).session();
    return session.active ? session : new CocoStore.Session();
  }

  // Palette adapted from the new COCOTAXI visual reference: deep navy surfaces,
  // emerald actions, yellow navigation accents and cool secondary colors.
  private static final int BG = 0xff03111d,
      PANEL = 0xff0a1d2e,
      PANEL_2 = 0xff0d2438,
      BORDER = 0xff173650,
      GREEN = 0xff20e3a2,
      YELLOW = 0xffffc928,
      BLUE = 0xff2f7df6,
      PURPLE = 0xff7048f6,
      WHITE = 0xfff5f8fc,
      MUTED = 0xff9fb1c9,
      RED = 0xffff5d72;
  private int tab = 0;
  private boolean advanced = false;
  private boolean profileView = false;
  private boolean settingsDirty = false;
  private int historyPage;
  private boolean permissionDialog, permissionDeferred;
  private boolean compactUi;
  private boolean pinUnlocked;
  private LicenseManager licenseManager;
  private LinearLayout body;
  private SettingsStore.Values draft;
  private final Map<String, EditText> inputs = new LinkedHashMap<>();
  private final Map<String, String> draftText = new LinkedHashMap<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Runnable> refreshers = new ArrayList<>();
  private String selectedSession = "";
  private ScreenOcrEngine ocr;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          for (Runnable r : new ArrayList<>(refreshers)) r.run();
          // The dashboard contains live idle/service clocks; refresh once a second so they feel
          // like real timers instead of rounded session totals.
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    LegacyImport.run(this);
    licenseManager = LicenseManager.get(this);
    refreshLicense(false);
    pinUnlocked = !PinStore.enabled(this) || (state != null && state.getBoolean("pinUnlocked"));
    if (state != null) {
      tab = state.getInt("tab");
      advanced = state.getBoolean("advanced");
      profileView = state.getBoolean("profileView");
      settingsDirty = state.getBoolean("settingsDirty");
      draft = (SettingsStore.Values) state.getSerializable("values");
      selectedSession = state.getString("selected", "");
      Bundle b = state.getBundle("draft");
      if (b != null) for (String k : b.keySet()) draftText.put(k, b.getString(k));
    }
    if (pinUnlocked) render();
    else showPinGate();
  }

  @Override
  public void onResume() {
    super.onResume();
    foreground = new java.lang.ref.WeakReference<>(this);
    if (!pinUnlocked) {
      showPinGate();
      return;
    }
    if (tab != 3) render();
    handler.removeCallbacks(tick);
    handler.post(tick);
    handler.post(this::checkPermissions);
    if (CocoStore.get(this).session().active && Settings.canDrawOverlays(this))
      startForegroundService(new Intent(this, CocotaxiOverlayService.class));
  }

  @Override
  public void onPause() {
    if (foreground.get() == this) foreground.clear();
    super.onPause();
    handler.removeCallbacks(tick);
  }

  @Override
  public void onBackPressed() {
    if (!canLeaveSettings()) return;
    if (profileView) {
      profileView = false;
      render();
      return;
    }
    super.onBackPressed();
  }

  private boolean canLeaveSettings() {
    if (tab != 3 || profileView || !settingsDirty) return true;
    toast("Guarda los ajustes antes de salir");
    return false;
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (ocr != null) ocr.close();
    if (foreground.get() == this) foreground.clear();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("tab", tab);
    b.putBoolean("advanced", advanced);
    b.putBoolean("profileView", profileView);
    b.putBoolean("settingsDirty", settingsDirty);
    b.putBoolean("pinUnlocked", pinUnlocked);
    b.putSerializable("values", draft);
    b.putString("selected", selectedSession);
    if (tab == 3) {
      captureDraft();
      Bundle d = new Bundle();
      for (Map.Entry<String, String> e : draftText.entrySet())
        d.putString(e.getKey(), e.getValue());
      b.putBundle("draft", d);
    }
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout col() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private GradientDrawable background(int color, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(18));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private TextView text(String s, float size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setPadding(0, dp(3), 0, dp(3));
    return t;
  }

  private TextView bold(String s, float size, int color) {
    TextView t = text(s, size, color);
    t.setTypeface(null, Typeface.BOLD);
    return t;
  }

  private TextView button(String s, Runnable action) {
    TextView t = bold(s, compactUi ? 12.2f : 13.5f, WHITE);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(compactUi ? 38 : 44));
    t.setPadding(dp(compactUi ? 8 : 11), dp(compactUi ? 5 : 8), dp(compactUi ? 8 : 11), dp(compactUi ? 5 : 8));
    t.setBackground(new InsetDrawable(background(PANEL_2, BORDER), dp(4), 0, dp(4), 0));
    t.setOnClickListener(v -> action.run());
    return t;
  }

  private TextView primaryButton(String s, Runnable action) {
    TextView t = bold(s, compactUi ? 13 : 14.5f, 0xff041610);
    t.setGravity(Gravity.CENTER);
    t.setMinHeight(dp(compactUi ? 42 : 48));
    t.setPadding(dp(12), dp(compactUi ? 7 : 9), dp(12), dp(compactUi ? 7 : 9));
    GradientDrawable d = new GradientDrawable();
    d.setColor(GREEN);
    d.setCornerRadius(dp(22));
    t.setElevation(dp(2));
    t.setBackground(new InsetDrawable(d, dp(5), 0, dp(5), 0));
    t.setOnClickListener(v -> action.run());
    return t;
  }

  private TextView sessionButton(String s, boolean selected, Runnable action) {
    TextView t = button(s, action);
    if (selected) {
      t.setTextColor(0xff041610);
      t.setBackground(new InsetDrawable(background(GREEN, GREEN), dp(4), 0, dp(4), 0));
    }
    return t;
  }

  private LinearLayout settingTile(
      int icon, String label, String value, boolean selected, Runnable action) {
    LinearLayout tile = col();
    tile.setPadding(dp(compactUi ? 6 : 9), dp(compactUi ? 5 : 9), dp(compactUi ? 6 : 9), dp(compactUi ? 5 : 9));
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(background(PANEL_2, selected ? GREEN : BORDER));
    LineIconView iv = new LineIconView(this, icon);
    iv.setIconColor(selected ? GREEN : MUTED);
    tile.addView(iv, new LinearLayout.LayoutParams(dp(compactUi ? 20 : 25), dp(compactUi ? 20 : 25)));
    TextView l = text(label, compactUi ? 9.5f : 11, selected ? WHITE : MUTED);
    l.setGravity(Gravity.CENTER);
    l.setMaxLines(2);
    tile.addView(l);
    TextView v = bold(value, compactUi ? 16 : 19, selected ? GREEN : WHITE);
    v.setGravity(Gravity.CENTER);
    v.setMaxLines(1);
    v.setAutoSizeTextTypeUniformWithConfiguration(10, compactUi ? 16 : 19, 1, 2);
    tile.addView(v, new LinearLayout.LayoutParams(-1, dp(compactUi ? 22 : 27)));
    tile.setOnClickListener(x -> action.run());
    return tile;
  }

  private void settingsToggle(
      LinearLayout parent,
      int icon,
      String title,
      String subtitle,
      boolean checked,
      java.util.function.Consumer<Boolean> changed) {
    LinearLayout r = row();
    r.setPadding(dp(7), dp(4), dp(4), dp(4));
    LineIconView iv = new LineIconView(this, icon);
    iv.setIconColor(GREEN);
    r.addView(iv, new LinearLayout.LayoutParams(dp(27), dp(27)));
    LinearLayout labels = col();
    labels.setPadding(dp(8), 0, dp(6), 0);
    labels.addView(bold(title, 13, WHITE));
    if (subtitle != null && !subtitle.isEmpty()) labels.addView(text(subtitle, 10, MUTED));
    r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    Switch sw = new Switch(this);
    sw.setChecked(checked);
    sw.setContentDescription(title);
    sw.setOnCheckedChangeListener((v, on) -> changed.accept(on));
    r.addView(sw);
    parent.addView(r);
  }

  private LinearLayout settingsToggleTile(
      int icon, String title, boolean checked, java.util.function.Consumer<Boolean> changed) {
    LinearLayout tile = row();
    tile.setPadding(dp(7), dp(4), dp(3), dp(4));
    tile.setBackground(background(PANEL_2, BORDER));
    LineIconView iv = new LineIconView(this, icon);
    iv.setIconColor(GREEN);
    tile.addView(iv, new LinearLayout.LayoutParams(dp(21), dp(21)));
    TextView label = bold(title, compactUi ? 10.5f : 12, WHITE);
    label.setMaxLines(2);
    label.setPadding(dp(6), 0, dp(3), 0);
    tile.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Switch sw = new Switch(this);
    sw.setChecked(checked);
    sw.setContentDescription(title);
    sw.setOnCheckedChangeListener((v, on) -> changed.accept(on));
    tile.addView(sw, new LinearLayout.LayoutParams(dp(50), -2));
    return tile;
  }

  private String workDaysSummary(int mask) {
    String[] names = {"L", "M", "X", "J", "V", "S", "D"};
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) {
      if (b.length() > 0) b.append(" · ");
      b.append(names[i]);
    }
    return b.toString();
  }

  private LinearLayout card(LinearLayout parent, String title, int icon) {
    LinearLayout c = col();
    c.setPadding(dp(compactUi ? 9 : 12), dp(compactUi ? 7 : 10), dp(compactUi ? 9 : 12), dp(compactUi ? 7 : 10));
    c.setBackground(background(PANEL, BORDER));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(compactUi ? 5 : 8);
    parent.addView(c, p);
    if (title != null) {
      LinearLayout h = row();
      LineIconView i = new LineIconView(this, icon);
      i.setIconColor(GREEN);
      h.addView(i, new LinearLayout.LayoutParams(dp(compactUi ? 21 : 25), dp(compactUi ? 21 : 25)));
      TextView t = bold("  " + title, compactUi ? 14 : 16, WHITE);
      h.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      c.addView(h);
      gap(c, compactUi ? 3 : 5);
    }
    return c;
  }

  private void gap(LinearLayout l, int h) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  private void metric(LinearLayout row, String title, String value, int color) {
    LinearLayout c = col();
    c.setPadding(dp(2), dp(compactUi ? 2 : 4), dp(2), dp(compactUi ? 2 : 4));
    TextView label = text(title, compactUi ? 10 : 11, MUTED);
    label.setGravity(Gravity.CENTER);
    label.setMaxLines(2);
    c.addView(label);
    TextView v = bold(value, compactUi ? 19 : 23, color);
    v.setGravity(Gravity.CENTER);
    v.setMaxLines(1);
    v.setAutoSizeTextTypeUniformWithConfiguration(
        10, compactUi ? 19 : 23, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    c.addView(v, new LinearLayout.LayoutParams(-1, dp(compactUi ? 26 : 32)));
    row.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void compactMetric(LinearLayout r, String label, String value, int color) {
    metric(r, label, value, color);
    LinearLayout c = (LinearLayout) r.getChildAt(r.getChildCount() - 1);
    c.setPadding(dp(2), dp(1), dp(2), dp(1));
    TextView t = (TextView) c.getChildAt(1);
    t.getLayoutParams().height = dp(25);
    t.setAutoSizeTextTypeUniformWithConfiguration(10, 20, 1, 2);
  }

  private void updateMetrics(LinearLayout r, String[] values) {
    for (int i = 0; i < values.length; i++) {
      LinearLayout c = (LinearLayout) r.getChildAt(i);
      ((TextView) c.getChildAt(1)).setText(values[i]);
    }
  }

  private void line(LinearLayout c, String label, String value, int color) {
    LinearLayout r = row();
    TextView l = text(label, 13, MUTED);
    r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
    TextView v = bold(value, 14, color);
    r.addView(v);
    c.addView(r);
  }

  private void progress(LinearLayout c, double value) {
    ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    p.setMax(1000);
    p.setProgress((int) Math.max(0, Math.min(1000, value * 1000)));
    p.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
    p.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff1b3946));
    c.addView(p, new LinearLayout.LayoutParams(-1, dp(15)));
  }

  private EditText pinField(LinearLayout parent, String hint) {
    EditText e = new EditText(this);
    e.setHint(hint);
    e.setHintTextColor(0xff637b96);
    e.setTextColor(WHITE);
    e.setTextSize(compactUi ? 18 : 20);
    e.setGravity(Gravity.CENTER);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    e.setPadding(dp(12), dp(9), dp(12), dp(9));
    e.setBackground(background(PANEL_2, BORDER));
    parent.addView(e, new LinearLayout.LayoutParams(-1, dp(compactUi ? 48 : 54)));
    return e;
  }

  private void showPinGate() {
    refreshers.clear();
    handler.removeCallbacks(tick);
    compactUi = getResources().getConfiguration().screenHeightDp < 780;
    LinearLayout root = col();
    root.setBackgroundColor(BG);
    root.setPadding(dp(22), 0, dp(22), 0);
    root.setGravity(Gravity.CENTER);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
          v.setPadding(dp(22), top + dp(12), dp(22), bottom + dp(12));
          return insets;
        });

    LinearLayout brand = col();
    brand.setGravity(Gravity.CENTER);
    TextView name = bold("COCOTAXI", compactUi ? 29 : 34, WHITE);
    name.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
    android.text.SpannableString span = new android.text.SpannableString("COCOTAXI");
    span.setSpan(new android.text.style.ForegroundColorSpan(YELLOW), 4, 8, 0);
    name.setText(span);
    name.setGravity(Gravity.CENTER);
    brand.addView(name);
    root.addView(brand);
    gap(root, 22);

    LinearLayout card = col();
    card.setPadding(dp(20), dp(20), dp(20), dp(20));
    card.setBackground(background(PANEL, BORDER));
    LineIconView lock = new LineIconView(this, LineIconView.LOCK);
    lock.setIconColor(GREEN);
    LinearLayout lockWrap = row();
    lockWrap.setGravity(Gravity.CENTER);
    lockWrap.addView(lock, new LinearLayout.LayoutParams(dp(38), dp(38)));
    card.addView(lockWrap);
    TextView title = bold("Desbloquear COCOTAXI", compactUi ? 18 : 21, WHITE);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, dp(8), 0, dp(3));
    card.addView(title);
    TextView help = text("Introduce tu PIN de inicio", compactUi ? 11 : 12, MUTED);
    help.setGravity(Gravity.CENTER);
    card.addView(help);
    gap(card, 14);
    EditText pin = pinField(card, "PIN de 4 a 6 números");
    gap(card, 10);
    TextView state = text("", compactUi ? 10.5f : 11.5f, RED);
    state.setGravity(Gravity.CENTER);
    card.addView(state);
    TextView unlock = primaryButton("Entrar", () -> attemptUnlock(pin, state));
    LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(-1, -2);
    unlockLp.topMargin = dp(9);
    card.addView(unlock, unlockLp);
    TextView exit = button("Salir", this::finish);
    LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(-1, -2);
    exitLp.topMargin = dp(7);
    card.addView(exit, exitLp);
    pin.setOnEditorActionListener((v, actionId, event) -> {
      attemptUnlock(pin, state);
      return true;
    });
    root.addView(card, new LinearLayout.LayoutParams(-1, -2));
    setContentView(root);
    root.requestApplyInsets();
    pin.requestFocus();
  }

  private void attemptUnlock(EditText pin, TextView state) {
    long locked = PinStore.secondsLocked(this);
    if (locked > 0) {
      state.setText("Demasiados intentos. Espera " + locked + " s.");
      return;
    }
    if (PinStore.verify(this, pin.getText().toString().trim())) {
      pinUnlocked = true;
      render();
      if (CocoStore.get(this).session().active
          && Settings.canDrawOverlays(this))
        startForegroundService(new Intent(this, CocotaxiOverlayService.class));
      handler.removeCallbacks(tick);
      handler.post(tick);
      handler.post(this::checkPermissions);
      return;
    }
    long after = PinStore.secondsLocked(this);
    state.setText(after > 0 ? "PIN incorrecto. Bloqueado " + after + " s." : "PIN incorrecto");
    pin.setText("");
  }

  private void configurePin() {
    if (!PinStore.enabled(this)) {
      createPinDialog();
      return;
    }
    actionButtonsDialog(
        "PIN de inicio",
        java.util.Arrays.asList("Cambiar PIN", "Desactivar PIN"),
        java.util.Arrays.asList(this::changePinDialog, this::disablePinDialog),
        "Cancelar");
  }

  private void createPinDialog() {
    LinearLayout c = form();
    c.addView(text("El PIN se pedirá al abrir COCOTAXI. No se guarda en texto visible.", 12, MUTED));
    gap(c, 8);
    EditText first = pinField(c, "Nuevo PIN (4–6 números)");
    gap(c, 7);
    EditText confirm = pinField(c, "Repite el PIN");
    dialog(
        "Crear PIN de inicio",
        c,
        "Activar",
        () -> {
          String a = first.getText().toString().trim(), b = confirm.getText().toString().trim();
          PinStore.validateFormat(a);
          if (!a.equals(b)) throw new IllegalArgumentException("Los PIN no coinciden");
          PinStore.set(this, a);
          pinUnlocked = true;
          toast("PIN de inicio activado");
        });
  }

  private void changePinDialog() {
    LinearLayout c = form();
    EditText current = pinField(c, "PIN actual");
    gap(c, 7);
    EditText next = pinField(c, "Nuevo PIN (4–6 números)");
    gap(c, 7);
    EditText confirm = pinField(c, "Repite el nuevo PIN");
    dialog(
        "Cambiar PIN",
        c,
        "Guardar",
        () -> {
          if (!PinStore.verify(this, current.getText().toString().trim()))
            throw new IllegalArgumentException("El PIN actual no es correcto");
          String a = next.getText().toString().trim(), b = confirm.getText().toString().trim();
          PinStore.validateFormat(a);
          if (!a.equals(b)) throw new IllegalArgumentException("Los PIN no coinciden");
          PinStore.set(this, a);
          pinUnlocked = true;
          toast("PIN actualizado");
        });
  }

  private void disablePinDialog() {
    LinearLayout c = form();
    c.addView(text("Confirma tu PIN actual para quitar el bloqueo de inicio.", 12, MUTED));
    gap(c, 8);
    EditText current = pinField(c, "PIN actual");
    dialog(
        "Desactivar PIN",
        c,
        "Desactivar",
        () -> {
          if (!PinStore.verify(this, current.getText().toString().trim()))
            throw new IllegalArgumentException("El PIN actual no es correcto");
          PinStore.clear(this);
          pinUnlocked = true;
          toast("PIN desactivado");
        });
  }

  private void render() {
    refreshers.clear();
    inputs.clear();
    compactUi = getResources().getConfiguration().screenHeightDp < 780;
    LinearLayout root = col();
    root.setBackgroundColor(BG);
    root.setPadding(dp(10), 0, dp(10), 0);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int top = insets.getSystemWindowInsetTop(), bottom = insets.getSystemWindowInsetBottom();
          v.setPadding(dp(10), top, dp(10), bottom);
          return insets;
        });
    LinearLayout header = row();
    header.setPadding(dp(4), dp(compactUi ? 5 : 8), dp(2), dp(compactUi ? 6 : 9));
    LinearLayout brand = row();
    ImageView mascot = new ImageView(this);
    mascot.setImageResource(R.drawable.cocotaxi_foreground);
    mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
    mascot.setContentDescription("El Coco");
    brand.addView(mascot, new LinearLayout.LayoutParams(dp(compactUi ? 45 : 53), dp(compactUi ? 45 : 53)));
    LinearLayout brandText = col();
    brandText.setPadding(dp(8), 0, 0, 0);
    TextView name = bold("COCOTAXI", compactUi ? 24 : 29, WHITE);
    name.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
    android.text.SpannableString span = new android.text.SpannableString("COCOTAXI");
    span.setSpan(new android.text.style.ForegroundColorSpan(YELLOW), 4, 8, 0);
    name.setText(span);
    brandText.addView(name);
    TextView conductor = text("C O N D U C T O R", compactUi ? 8.5f : 10, MUTED);
    brandText.addView(conductor);
    brand.addView(brandText);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout profileButton = col();
    profileButton.setGravity(Gravity.CENTER);
    profileButton.setPadding(dp(10), dp(8), dp(10), dp(8));
    profileButton.setBackground(background(PANEL_2, profileView ? GREEN : BORDER));
    LineIconView profileIcon = new LineIconView(this, LineIconView.PROFILE);
    profileIcon.setIconColor(profileView ? GREEN : WHITE);
    profileButton.addView(profileIcon, new LinearLayout.LayoutParams(dp(compactUi ? 25 : 29), dp(compactUi ? 25 : 29)));
    profileButton.setContentDescription("Perfil");
    profileButton.setOnClickListener(
        v -> {
          if (!canLeaveSettings()) return;
          profileView = true;
          render();
        });
    header.addView(profileButton, new LinearLayout.LayoutParams(dp(compactUi ? 46 : 50), dp(compactUi ? 46 : 50)));
    root.addView(header);
    body = col();
    // Keep the bottom navigation fixed, but allow every screen to scroll when a smaller display
    // (for example the Galaxy A52) cannot fit all cards vertically. On taller phones this behaves
    // like the previous layout because fillViewport keeps the content at least screen-height.
    ScrollView screenScroll = new ScrollView(this);
    screenScroll.setFillViewport(true);
    screenScroll.setClipToPadding(false);
    screenScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
    screenScroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    root.addView(screenScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    if (profileView) profile();
    else if (tab == 0) home();
    else if (tab == 1) history();
    else if (tab == 2) goals();
    else if (tab == 3) settings();
    else advisor();
    LinearLayout nav = row();
    String[] labels = {"Inicio", "Sesión", "Metas", "Ajustes", "DÓNDE IR"};
    int[] icons = {LineIconView.HOME, LineIconView.CLOCK, LineIconView.TARGET, LineIconView.GEAR,
        LineIconView.ROAD};
    for (int n = 0; n < labels.length; n++) {
      final int target = n;
      LinearLayout item = col();
      item.setGravity(Gravity.CENTER);
      item.setPadding(0, dp(compactUi ? 3 : 6), 0, dp(compactUi ? 3 : 5));
      LineIconView i = new LineIconView(this, icons[n]);
      i.setIconColor(n == tab ? YELLOW : MUTED);
      item.addView(i, new LinearLayout.LayoutParams(dp(compactUi ? 22 : 25), dp(compactUi ? 22 : 25)));
      TextView navLabel = text(labels[n], compactUi ? 10.5f : 12, n == tab ? YELLOW : MUTED);
      navLabel.setGravity(Gravity.CENTER);
      item.addView(navLabel);
      View indicator = new View(this);
      indicator.setBackgroundColor(n == tab ? YELLOW : BG);
      item.addView(indicator, new LinearLayout.LayoutParams(dp(42), dp(2)));
      item.setContentDescription(labels[n]);
      item.setOnClickListener(
          v -> {
            if (target != 3 && !canLeaveSettings()) return;
            profileView = false;
            tab = target;
            render();
          });
      nav.addView(item, new LinearLayout.LayoutParams(0, dp(compactUi ? 50 : 58), 1));
    }
    root.addView(nav);
    setContentView(root);
    root.requestApplyInsets();
  }

  private void home() {
    SettingsStore.Values v = SettingsStore.load(this);
    CocoStore.Session s = dashboardSession();
    LinearLayout controls = row();
    controls.addView(
        sessionButton(
            "▶  Conectar",
            s.active && !s.paused,
            () -> {
              if (!ensureLicenseForConnect()) return;
              CocoStore st = CocoStore.get(this);
              if (st.session().paused) st.pause();
              else st.start();
              startOverlay();
              CocotaxiAccessibilityService.inspectCurrent();
              render();
            }),
        new LinearLayout.LayoutParams(0, -2, 1.35f));
    gapHorizontal(controls, 6);
    controls.addView(
        sessionButton(
            "Ⅱ Pausar",
            s.active && s.paused,
            () -> {
              if (CocoStore.get(this).session().active && !CocoStore.get(this).session().paused)
                CocoStore.get(this).pause();
              CocotaxiOverlayService.clearOffer();
              render();
            }),
        new LinearLayout.LayoutParams(0, -2, .8f));
    gapHorizontal(controls, 6);
    TextView disconnect = sessionButton("■ Desconectar", !s.active, this::stop);
    disconnect.setTextSize(compactUi ? 10.8f : 12.2f);
    disconnect.setMinHeight(dp(compactUi ? 42 : 48));
    disconnect.setPadding(dp(8), dp(compactUi ? 6 : 8), dp(8), dp(compactUi ? 6 : 8));
    controls.addView(disconnect, new LinearLayout.LayoutParams(0, -2, 1.28f));
    body.addView(controls);
    gap(body, compactUi ? 4 : 8);
    LinearLayout status = card(body, null, 0);
    LinearLayout sr = row();
    metric(
        sr,
        s.active ? (s.paused ? "PAUSA" : "CONECTADO") : "DESCONECTADO",
        duration(s.minutes),
        GREEN);
    metric(sr, "OBJETIVO", Money.format(v.realGoalHour) + "/h", WHITE);
    status.addView(sr);
    TextView automationState = text("", compactUi ? 10 : 12, MUTED);
    automationState.setMaxLines(2);
    status.addView(automationState);
    LinearLayout main = card(body, "OBJETIVO ACTUAL", LineIconView.CHART);
    LinearLayout r = row();
    LinearLayout left = col();
    // "Objetivo actual" is the real accumulated hourly average for this session.
    // Below the configured objective it is red; meeting or exceeding it turns it green.
    double appMoney = s.money;
    double currentHourly = s.hourly();
    int currentHourlyColor =
        s.minutes <= 0 ? WHITE : (currentHourly >= v.realGoalHour ? GREEN : RED);
    TextView avg =
        bold(Money.format(currentHourly) + "/h", compactUi ? 34 : 42, currentHourlyColor);
    avg.setMaxLines(1);
    avg.setAutoSizeTextTypeUniformWithConfiguration(compactUi ? 20 : 24, compactUi ? 34 : 42, 1, 2);
    left.addView(avg, new LinearLayout.LayoutParams(-1, dp(compactUi ? 49 : 63)));
    TextView tag = text("", compactUi ? 10.5f : 12, WHITE);
    left.addView(tag);
    r.addView(left, new LinearLayout.LayoutParams(0, -2, 2));
    GaugeView gauge = new GaugeView(this, currentHourly / v.realGoalHour);
    r.addView(gauge, new LinearLayout.LayoutParams(0, dp(compactUi ? 62 : 80), 1.2f));
    main.addView(r);
    LinearLayout figures = row();
    metric(figures, "GANANCIA ACTUAL", Money.format(appMoney), BLUE);
    metric(figures, "DEBERÍA TENER", Money.format(v.realGoalHour * s.minutes / 60), WHITE);
    metric(
        figures,
        "PÉRDIDAS",
        Money.format(Math.max(0, v.realGoalHour * s.minutes / 60 - appMoney)),
        RED);
    main.addView(figures);
    LinearLayout session = card(body, "Tu sesión", LineIconView.CLOCK);
    LinearLayout stats = row();
    metric(stats, "Viajes", String.valueOf(s.trips), WHITE);
    
    metric(stats, "Sin viaje", duration(Math.max(0, s.minutes - s.service)), WHITE);
    metric(stats, "En servicio", duration(s.service), WHITE);
    session.addView(stats);
    if (!compactUi) progress(session, s.hourly() / v.realGoalHour);
    session.addView(
        button(
            "Ver y corregir viajes  ›",
            () -> {
              selectedSession = "";
              tab = 1;
              render();
            }));
    TextView pace = text("", compactUi ? 11 : 13, GREEN);
    session.addView(pace);
    TextView promoButton = button("", this::promotion);
    body.addView(promoButton);
    refreshers.add(
        () -> {
          automationState.setText("Autoaceptar " + (v.autoAccept ? "activo" : "apagado")
              + " · " + CocotaxiAccessibilityService.autoAcceptStatus);
          CocoStore.Session live = dashboardSession();
          double liveAppMoney = live.money;
          double liveCurrentHourly = live.hourly();
          avg.setText(Money.format(liveCurrentHourly) + "/h");
          avg.setTextColor(
              live.minutes <= 0 ? WHITE : (liveCurrentHourly >= v.realGoalHour ? GREEN : RED));
          gauge.setFraction(liveCurrentHourly / v.realGoalHour);
          tag.setText(
              live.minutes == 0
                  ? "OBJETIVO AL INICIAR"
                  : liveCurrentHourly >= v.realGoalHour
                      ? "RITMO CUBIERTO"
                      : "NECESITAS RECUPERAR RITMO");
          tag.setTextColor(liveCurrentHourly >= v.realGoalHour ? GREEN : RED);
          updateMetrics(
              sr, new String[] {duration(live.minutes), Money.format(v.realGoalHour) + "/h"});
          updateMetrics(
              figures,
              new String[] {
                Money.format(liveAppMoney),
                Money.format(v.realGoalHour * live.minutes / 60),
                Money.format(Math.max(0, v.realGoalHour * live.minutes / 60 - liveAppMoney))
              });
          updateMetrics(
              stats,
              new String[] {
                String.valueOf(live.trips),
                duration(Math.max(0, live.minutes - live.service)),
                duration(live.service)
              });
          double needed = PromotionMath.requiredHourly(v.dailyGoal, v.targetHoursDay, live.minutes, live.money);
          pace.setText(live.minutes >= v.targetHoursDay*60
              ? "Tiempo previsto agotado · faltan " + Money.format(Math.max(0,v.dailyGoal-live.money))
              : "");
          promoButton.setText(PromotionStore.get(this).description() + "  ›");
        });
  }

  private void startOverlay() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 91);
    startForegroundService(new Intent(this, CocotaxiOverlayService.class));
  }

  private void stop() {
    CocoStore.Trip t = CocoStore.get(this).activeTrip();
    if (t != null) {
      new AlertDialog.Builder(this)
          .setTitle("Viaje pendiente")
          .setMessage("Confirma si terminó antes de cerrar la sesión.")
          .setPositiveButton(
              "Ver viaje",
              (d, w) -> {
                tab = 1;
                render();
              })
          .setNegativeButton(
              "Cerrar sesión",
              (d, w) -> {
                CocoStore.get(this).status(t.id, "SIN CONFIRMAR");
                finishSession();
              })
          .show();
    } else finishSession();
  }

  private void finishSession() {
    CocoStore.get(this).stop();
    selectedSession = "";
    historyPage = 0;
    stopService(new Intent(this, CocotaxiOverlayService.class));
    stopService(new Intent(this, CaptureService.class));
    render();
  }

  private void history() {
    CocoStore store = CocoStore.get(this);
    CocoStore.Session current = store.session();
    String id = selectedSession.isEmpty() ? current.id : selectedSession;
    LinearLayout head = card(body, "SESIÓN Y VIAJES", LineIconView.CLOCK);
    head.addView(
        text(
            "Coco confirma el viaje al aceptarlo. Puedes quitar esa confirmación desde Gestionar.",
            compactUi ? 10.5f : 12,
            MUTED));
    LinearLayout topActions = row();
    topActions.addView(
        button(
            "Elegir sesión",
            () -> {
              List<CocoStore.Session> ss = store.sessions();
              List<String> names = new ArrayList<>();
              List<Runnable> actions = new ArrayList<>();
              for (CocoStore.Session item : ss) {
                names.add(stamp(item.start) + " · " + Money.exact(item.money));
                actions.add(
                    () -> {
                      selectedSession = item.id;
                      historyPage = 0;
                      render();
                    });
              }
              if (names.isEmpty()) {
                toast("Todavía no hay sesiones");
                return;
              }
              actionButtonsDialog("Sesiones", names, actions, "Cancelar");
            }),
        new LinearLayout.LayoutParams(0, -2, .8f));
    gapHorizontal(topActions, 6);
    topActions.addView(
        button("＋ Registrar viaje manual", this::manualIncome),
        new LinearLayout.LayoutParams(0, -2, 1.2f));
    head.addView(topActions);

    List<CocoStore.Trip> trips = store.trips(id);
    if (trips.isEmpty()) {
      head.addView(text("Todavía no hay viajes en esta sesión.", 13, WHITE));
      return;
    }

    // One trip per page guarantees the complete history screen fits without vertical scrolling,
    // even on smaller phones. Navigation remains fast because the newest trip is page 1.
    int pages = trips.size();
    historyPage = Math.max(0, Math.min(historyPage, pages - 1));
    LinearLayout pageControls = row();
    pageControls.addView(
        button("‹ Anterior", () -> { historyPage = Math.max(0, historyPage - 1); render(); }),
        new LinearLayout.LayoutParams(0, -2, 1));
    TextView page = text((historyPage + 1) + " / " + pages, 13, WHITE);
    page.setGravity(Gravity.CENTER);
    pageControls.addView(page, new LinearLayout.LayoutParams(dp(58), -2));
    pageControls.addView(
        button("Siguiente ›", () -> { historyPage = Math.min(pages - 1, historyPage + 1); render(); }),
        new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(pageControls);

    CocoStore.Trip t = trips.get(historyPage);
    LinearLayout tripCard = card(body, stamp(t.at), LineIconView.CAR);
    String state = t.status;
    if ("ASIGNADO".equals(t.status)) state = "CONFIRMADO · EN SERVICIO";
    else if ("FINALIZADO".equals(t.status)) state = "CONFIRMADO · FINALIZADO";
    line(
        tripCard,
        t.deleted ? "ELIMINADO" : state,
        Money.exact(t.net()),
        t.deleted ? MUTED : ("SIN CONFIRMAR".equals(t.status) ? RED : GREEN));
    tripCard.addView(
        text(
            t.manual
                ? (t.trip ? "Importe " + Money.exact(t.net()) + " · Tiempo " + duration(t.minutes)
                    : "Importe " + Money.exact(t.net()))
                : (t.confirmed ? "Pago real" : "Pago estimado")
                    + " · oferta " + Money.exact(t.offered)
                    + (t.minutes > 0 ? " · servicio " + duration(t.minutes) : "")
                    + "\n" + t.label,
            compactUi ? 10.5f : 12,
            MUTED));
    tripCard.addView(button("Gestionar viaje  ›", () -> tripActions(t)));
  }

  private void advisor() {
    LinearLayout intro = card(body, "DÓNDE IR", LineIconView.ROAD);
    intro.addView(text("Señales observadas de Cabify en Montevideo. Sin GPS ni posición actual.",
        compactUi ? 11 : 13, WHITE));
    intro.addView(text("El $/h es la mediana de ofertas vistas en este día y horario; todavía no incluye "
        + "espera hasta el próximo viaje ni traslado hacia la zona.", compactUi ? 10 : 12, MUTED));
    List<CocoDataStore.Forecast> forecasts = CocoDataStore.forecasts(this,
        System.currentTimeMillis());
    if (forecasts.isEmpty()) {
      intro.addView(text("Todavía faltan datos: se necesitan 3 ofertas por zona en este día y horario.",
          compactUi ? 11 : 13, YELLOW));
    } else {
      for (int i = 0; i < Math.min(3, forecasts.size()); i++) {
        CocoDataStore.Forecast f = forecasts.get(i);
        LinearLayout zone = card(body, f.place.toUpperCase(Locale.ROOT), LineIconView.CHART);
        line(zone, "Oferta observada por hora", Money.exact(f.medianHourly) + "/h", GREEN);
        zone.addView(text(f.samples + " ofertas · "
            + (f.heat == 3 ? "mapa rojo observado" : f.heat == 2 ? "mapa amarillo observado"
                : "sin señal reciente del mapa"), compactUi ? 10 : 12, MUTED));
      }
    }
    intro.addView(button("Exportar observaciones del mes", () -> {
      java.io.File file = CocoDataStore.currentFile(this, System.currentTimeMillis());
      if (!file.isFile()) { toast("Aún no hay observaciones este mes"); return; }
      Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE).setType("application/x-ndjson")
          .putExtra(Intent.EXTRA_TITLE, file.getName());
      startActivityForResult(save, 94);
    }));
  }

  private void tripActions(CocoStore.Trip t) {
    CocoStore store = CocoStore.get(this);
    List<String> actions = new ArrayList<>();
    List<Runnable> run = new ArrayList<>();
    if (!t.deleted) {
      boolean manualEntry = t.manual;
      if (!manualEntry && ("ASIGNADO".equals(t.status) || "FINALIZADO".equals(t.status))) {
        actions.add("Quitar confirmación del viaje");
        run.add(() -> { store.unconfirm(t.id); render(); });
      } else if (!manualEntry && "SIN CONFIRMAR".equals(t.status)) {
        actions.add("Confirmar viaje nuevamente");
        run.add(() -> { store.reconfirm(t.id); render(); });
      }
      actions.add("Corregir / confirmar pago");
      run.add(() -> correct(t));
      actions.add("Eliminar del historial");
      run.add(() -> { store.delete(t.id); render(); });
    } else {
      actions.add("Deshacer eliminación");
      run.add(() -> { store.restore(t.id); render(); });
    }
    actionButtonsDialog("Gestionar viaje", actions, run, "Cerrar");
  }

  private LinearLayout form() {
    LinearLayout c = col();
    c.setPadding(dp(18), dp(5), dp(18), dp(5));
    return c;
  }

  private EditText field(LinearLayout c, String label, String value) {
    c.addView(text(label, 12, MUTED));
    EditText e = new EditText(this);
    e.setTextColor(WHITE);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    e.setText(value);
    e.setSelectAllOnFocus(true);
    e.setMinHeight(dp(44));
    c.addView(e, new LinearLayout.LayoutParams(-1, -2));
    return e;
  }

  private double number(EditText e) {
    double n = Money.parse(e.getText().toString());
    if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Revisa " + e.getText());
    return n;
  }

  private String decimal(double n) {
    return String.format(Locale.US, "%.2f", n);
  }

  private AlertDialog dialog(String title, LinearLayout form, String action, Runnable save) {
    ScrollView s = new ScrollView(this);
    s.addView(form);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(s)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(action, null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    b -> {
                      try {
                        save.run();
                        d.dismiss();
                        render();
                      } catch (Exception e) {
                        toast(e.getMessage());
                      }
                    }));
    d.show();
    return d;
  }

  private AlertDialog actionButtonsDialog(
      String title, List<String> labels, List<Runnable> actions, String closeLabel) {
    LinearLayout c = form();
    AlertDialog[] holder = new AlertDialog[1];
    for (int i = 0; i < labels.size(); i++) {
      final int index = i;
      TextView option =
          button(
              labels.get(i),
              () -> {
                if (holder[0] != null) holder[0].dismiss();
                actions.get(index).run();
              });
      c.addView(option, new LinearLayout.LayoutParams(-1, -2));
      if (i < labels.size() - 1) gap(c, 6);
    }
    if (closeLabel != null && !closeLabel.isEmpty()) {
      gap(c, 8);
      c.addView(
          button(
              closeLabel,
              () -> {
                if (holder[0] != null) holder[0].dismiss();
              }),
          new LinearLayout.LayoutParams(-1, -2));
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(c);
    AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(scroll).create();
    holder[0] = d;
    d.show();
    return d;
  }

  private void manualIncome() {
    CocoStore store = CocoStore.get(this);
    CocoStore.Session latest = store.session();
    String targetSession = selectedSession.isEmpty() ? latest.id : selectedSession;
    if (targetSession == null || targetSession.isEmpty()) {
      toast("Primero inicia una sesión o elige una sesión anterior");
      return;
    }

    LinearLayout c = form();
    for (CocoStore.Session item : store.sessions()) {
      if (item.id.equals(targetSession)) {
        String when = Instant.ofEpochMilli(item.start).atZone(CocoStore.ZONE)
            .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"));
        c.addView(text("Sesión asociada: " + when + (item.active ? " · activa" : " · cerrada"), 13, GREEN));
        break;
      }
    }
    EditText amount = field(c, "Cobro real sin propina ni peajes (UYU)", ""),
        minutes = field(c, "Minutos de recogida y servicio", "0");
    Switch trip = new Switch(this);
    trip.setText("Cuenta como viaje (desactivar para bono o extra)");
    trip.setTextColor(WHITE);
    trip.setChecked(true);
    c.addView(trip);
    c.addView(
        text(
            "El viaje manual queda finalizado de inmediato. En una sesión activa o pausada"
                + " reclasifica tiempo ya conectado; si falta cobertura, Conectado sube sólo lo"
                + " necesario. En una sesión cerrada suma todo el tiempo a esa sesión. Nunca"
                + " inicia un contador vivo. Un extra suma dinero sin minutos.",
            12,
            MUTED));
    final String sessionId = targetSession;
    dialog(
        "Registrar ingreso",
        c,
        "Guardar",
        () ->
            store.manualForSession(
                sessionId,
                number(amount),
                number(minutes),
                trip.isChecked() ? "Viaje manual" : "Extra cobrado",
                trip.isChecked()));
  }

  private void correct(CocoStore.Trip t) {
    LinearLayout c = form();
    boolean manualEntry = t.manual;
    EditText
        amount =
            field(
                c, "Pago real sin propina ni peajes", decimal(t.confirmed ? t.actual : t.estimate)),
        cost = field(c, "Gastos del viaje", decimal(t.cost)),
        ordinary =
            field(
                c,
                "Pago ordinario sin bonos, propina ni peajes",
                decimal(t.ordinary > 0 ? t.ordinary : t.confirmed ? t.actual : t.estimate));
    EditText manualMinutes = manualEntry && t.trip
        ? field(c, "Minutos de recogida y servicio", decimal(t.minutes))
        : null;
    c.addView(
        text(
            manualEntry
                ? (t.trip
                    ? "Al guardar, el viaje manual sigue finalizado. Cualquier cambio de minutos"
                        + " ajusta de inmediato En servicio y Conectado; nunca inicia un contador vivo."
                    : "Este registro es un extra/bono: suma dinero, no En servicio ni Conectado.")
                : "Confirmar marca el viaje como finalizado. Si Coco capturó la oferta original,"
                    + " la comparación con el pago se valida automáticamente.",
            12,
            MUTED));
    dialog(
        "Confirmar / corregir viaje",
        c,
        "Guardar",
        () -> {
          if (manualEntry)
            CocoStore.get(this)
                .correct(
                    t.id,
                    number(amount),
                    number(cost),
                    false,
                    number(ordinary),
                    manualMinutes == null ? 0 : number(manualMinutes));
          else
            CocoStore.get(this)
                .correct(t.id, number(amount), number(cost), t.offered > 0, number(ordinary));
        });
  }

  private void goals() {
    CocoStore store = CocoStore.get(this);
    CocoStore.Session session = dashboardSession();
    SettingsStore.Values v = SettingsStore.load(this);
    CocoStore.Totals week = store.period(CocoStore.weekStart(System.currentTimeMillis()),System.currentTimeMillis()+1);
    LinearLayout day = card(body,"META DE LA JORNADA",LineIconView.TARGET);
    line(day,"Objetivo",Money.format(v.dailyGoal),GREEN);
    line(day,"Acumulado",Money.exact(session.money),WHITE);
    line(day,"Falta",Money.format(Math.max(0,v.dailyGoal-session.money)),RED);
    progress(day,session.money/v.dailyGoal);
    day.addView(text("La jornada continúa al cruzar medianoche. Incluye la espera entre viajes.",12,MUTED));
    LinearLayout weekly=card(body,"SEMANA · LUNES A DOMINGO",LineIconView.CALENDAR);
    line(weekly,"Objetivo",Money.format(v.weeklyGoal),GREEN);
    line(weekly,"Acumulado",Money.exact(week.money),WHITE);
    progress(weekly,week.money/v.weeklyGoal);
    weekly.addView(text(Money.exact(week.estimated)+" estimado activo · "+Money.exact(week.earned)+" ganado",12,MUTED));
  }

  private void promotion() {
    List<PromotionStore.Snapshot> promos = PromotionStore.getAll(this);
    LinearLayout c = form();
    c.addView(
        text(
            "Puedes tener varias promociones al mismo tiempo. Cada una conserva su propio premio, cantidad de viajes, inicio y fin.",
            12,
            MUTED));
    gap(c, 8);
    final AlertDialog[] manager = {null};
    if (promos.isEmpty()) {
      TextView empty = text("No hay promociones configuradas en esta sesión.", 13, WHITE);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, dp(12), 0, dp(12));
      c.addView(empty);
    } else {
      DateTimeFormatter shortFmt = DateTimeFormatter.ofPattern("dd/MM HH:mm");
      for (int i = 0; i < promos.size(); i++) {
        PromotionStore.Snapshot promo = promos.get(i);
        LinearLayout item = col();
        item.setPadding(dp(10), dp(9), dp(10), dp(9));
        item.setBackground(background(PANEL_2, promo.active() ? GREEN : BORDER));
        TextView title = bold("PROMO " + (i + 1) + "  ·  " + Money.format(promo.bonus), 15, promo.active() ? GREEN : WHITE);
        item.addView(title);
        item.addView(
            text(
                promo.completed
                    + "/"
                    + promo.target
                    + " viajes  ·  "
                    + Instant.ofEpochMilli(promo.start).atZone(CocoStore.ZONE).format(shortFmt)
                    + " → "
                    + Instant.ofEpochMilli(promo.end).atZone(CocoStore.ZONE).format(shortFmt),
                12,
                MUTED));
        item.addView(text(promo.description(), 11, promo.active() ? GREEN : MUTED));
        LinearLayout actions = row();
        actions.addView(
            button(
                "Editar",
                () -> {
                  if (manager[0] != null) manager[0].dismiss();
                  editPromotion(promo);
                }),
            new LinearLayout.LayoutParams(0, -2, 1.25f));
        gapHorizontal(actions, 6);
        actions.addView(
            button(
                "Eliminar",
                () -> {
                  PromotionStore.remove(this, promo.id);
                  if (manager[0] != null) manager[0].dismiss();
                  toast("Promoción eliminada");
                  render();
                  promotion();
                }),
            new LinearLayout.LayoutParams(0, -2, .75f));
        item.addView(actions);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.bottomMargin = dp(8);
        c.addView(item, ip);
      }
    }
    c.addView(primaryButton("＋ Agregar promoción", () -> {
      if (manager[0] != null) manager[0].dismiss();
      editPromotion(null);
    }));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(c);
    manager[0] =
        new AlertDialog.Builder(this)
            .setTitle("Promociones de esta sesión")
            .setView(scroll)
            .setNegativeButton("Cerrar", null)
            .create();
    manager[0].show();
  }

  private void editPromotion(PromotionStore.Snapshot old) {
    LinearLayout c = form();
    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    LocalDateTime now = LocalDateTime.now(CocoStore.ZONE);
    boolean editing = old != null && old.target > 0;
    EditText bonus = field(c, "Premio anunciado (UYU)", decimal(editing ? old.bonus : 1100));
    EditText target = field(c, "Total de viajes exigidos", String.valueOf(editing ? old.target : 13));
    EditText done = field(c, "Viajes válidos ya completados", String.valueOf(editing ? old.completed : 0));
    EditText start =
        field(
            c,
            "Inicio · fecha y hora",
            editing
                ? Instant.ofEpochMilli(old.start).atZone(CocoStore.ZONE).format(fmt)
                : now.format(fmt));
    EditText end =
        field(
            c,
            "Fin · fecha y hora",
            editing
                ? Instant.ofEpochMilli(old.end).atZone(CocoStore.ZONE).format(fmt)
                : now.plusHours(4).format(fmt));
    start.setInputType(InputType.TYPE_CLASS_TEXT);
    end.setInputType(InputType.TYPE_CLASS_TEXT);
    LinearLayout presets = row();
    for (int h : new int[] {19, 23, 3}) {
      final int hour = h;
      presets.addView(
          button(
              String.format(Locale.US, "%02d–%02d", h, (h + 4) % 24),
              () -> {
                LocalDateTime from = now.toLocalDate().atTime(hour, 0);
                if (hour == 23 && now.getHour() < 3) from = from.minusDays(1);
                if (!now.isBefore(from.plusHours(4))) from = from.plusDays(1);
                start.setText(from.format(fmt));
                end.setText(from.plusHours(4).format(fmt));
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    }
    c.addView(presets);
    c.addView(
        text(
            editing && old.samples >= 3
                ? "Ritmo observado: " + decimal(old.cycle) + " min por viaje, incluyendo esperas"
                : "Cada promoción se calcula de forma independiente; el premio nunca se suma antes de cobrarlo.",
            12,
            MUTED));
    dialog(
        editing ? "Editar promoción" : "Nueva promoción",
        c,
        editing ? "Guardar cambios" : "Agregar",
        () -> {
          double n = number(target), d = number(done);
          if (n != (int) n || d != (int) d)
            throw new IllegalArgumentException("Introduce cantidades enteras");
          long a =
              LocalDateTime.parse(start.getText().toString().trim(), fmt)
                  .atZone(CocoStore.ZONE)
                  .toInstant()
                  .toEpochMilli();
          long b =
              LocalDateTime.parse(end.getText().toString().trim(), fmt)
                  .atZone(CocoStore.ZONE)
                  .toInstant()
                  .toEpochMilli();
          if (editing)
            PromotionStore.update(this, old.id, (int) n, number(bonus), a, b, (int) d);
          else PromotionStore.add(this, (int) n, number(bonus), a, b, (int) d);
        });
  }

  private void settings() {
    if (draft == null) draft = SettingsStore.load(this);
    SettingsStore.normalizeGoals(draft);

    LinearLayout goals = card(body, "OBJETIVO · ELIGE 1 PARÁMETRO", LineIconView.TARGET);
    goals.addView(text("Toca Semanal, Jornada o Por hora. Solo editas uno; Coco recalcula automáticamente los otros dos.", compactUi ? 10 : 11.5f, MUTED));
    LinearLayout goalRow = row();
    String[] labels = {"Semanal", "Jornada", "Por hora"};
    double[] values = {draft.weeklyGoal, draft.dailyGoal, draft.realGoalHour};
    int[] icons = {LineIconView.CALENDAR, LineIconView.MONEY, LineIconView.CLOCK};
    for (int i = 0; i < 3; i++) {
      final int mode = i;
      LinearLayout tile =
          settingTile(
              icons[i],
              labels[i],
              Money.format(values[i]) + (i == 2 ? "/h" : ""),
              draft.goalMode == i,
              () -> {
                editGoals(mode);
              });
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(0, dp(compactUi ? 72 : 88), i == 1 ? 1.12f : 1f);
      if (i > 0) lp.leftMargin = dp(5);
      goalRow.addView(tile, lp);
    }
    goals.addView(goalRow);

    LinearLayout schedule = card(body, "JORNADA", LineIconView.CALENDAR);
    schedule.addView(
        settingTile(
            LineIconView.CALENDAR,
            "Días de trabajo",
            workDaysSummary(draft.workDaysMask) + " · hoy " + decimal(SettingsStore.currentTargetHours(draft)) + " h",
            false,
            this::editSchedule),
        new LinearLayout.LayoutParams(-1, dp(compactUi ? 64 : 76)));

    LinearLayout automation = card(body, "AUTOMATIZACIÓN", LineIconView.GEAR);
    LinearLayout autoRow = row();
    autoRow.addView(
        settingsToggleTile(
            LineIconView.CAR,
            "Autoaceptar",
            draft.autoAccept,
            on -> {
              draft.autoAccept = on;
              settingsDirty = true;
            }),
        new LinearLayout.LayoutParams(-1, dp(compactUi ? 49 : 56)));
    automation.addView(autoRow);

    LinearLayout tools = card(body, "HERRAMIENTAS", LineIconView.WALLET);
    LinearLayout first = row();
    first.addView(button("Cobro", this::editPayment), new LinearLayout.LayoutParams(0, -2, .85f));
    gapHorizontal(first, 5);
    first.addView(button("Tiempos y límites", this::editTimes), new LinearLayout.LayoutParams(0, -2, 1.25f));
    gapHorizontal(first, 5);
    first.addView(button("Demanda", this::editAutomation), new LinearLayout.LayoutParams(0, -2, .9f));
    tools.addView(first);
    gap(tools, 5);
    tools.addView(button("Liquidación", () -> billing(null)));

    LinearLayout save = row();
    save.setPadding(dp(2), 0, dp(2), 0);
    save.addView(primaryButton("Guardar ajustes", this::persistDraft), new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(save);
  }

  private void profile() {
    SettingsStore.Values liveSettings = SettingsStore.load(this);
    LicenseManager.Snapshot license =
        licenseManager == null ? LicenseManager.get(this).snapshot() : licenseManager.snapshot();

    LinearLayout profile = card(body, "PERFIL", LineIconView.PROFILE);

    LinearLayout pinRow = row();
    LineIconView pinIcon = new LineIconView(this, LineIconView.PIN);
    pinIcon.setIconColor(PinStore.enabled(this) ? GREEN : MUTED);
    pinRow.addView(
        pinIcon,
        new LinearLayout.LayoutParams(
            dp(compactUi ? 24 : 28), dp(compactUi ? 24 : 28)));
    LinearLayout pinText = col();
    pinText.setPadding(dp(9), 0, dp(8), 0);
    pinText.addView(bold("PIN de inicio", compactUi ? 11.5f : 13, WHITE));
    pinText.addView(
        text(
            PinStore.enabled(this)
                ? "Activado · se pedirá al abrir Coco"
                : "Desactivado",
            compactUi ? 9.5f : 10.5f,
            MUTED));
    pinRow.addView(pinText, new LinearLayout.LayoutParams(0, -2, 1));
    TextView pinAction =
        button(PinStore.enabled(this) ? "Cambiar" : "Configurar", this::configurePin);
    pinAction.setTextSize(compactUi ? 10.8f : 12.0f);
    pinAction.setMinHeight(dp(compactUi ? 42 : 46));
    pinAction.setPadding(
        dp(8), dp(compactUi ? 6 : 8), dp(8), dp(compactUi ? 6 : 8));
    pinRow.addView(
        pinAction, new LinearLayout.LayoutParams(dp(compactUi ? 112 : 126), -2));
    profile.addView(pinRow);

    gap(profile, 7);
    profile.addView(
        settingsToggleTile(
            LineIconView.ROAD,
            "Vibración",
            liveSettings.vibrate,
            on -> {
              SettingsStore.saveVibration(this, on);
              toast(on ? "Vibración activada" : "Vibración desactivada");
              render();
            }),
        new LinearLayout.LayoutParams(-1, dp(compactUi ? 49 : 56)));

    gap(profile, 7);
    LinearLayout licenseRow = row();
    LinearLayout licenseText = col();
    licenseText.addView(bold("Activación", compactUi ? 11.5f : 13, WHITE));
    licenseText.addView(
        text(
            licenseLabel(license),
            compactUi ? 10 : 11.5f,
            license.allowsUse() ? GREEN : (license.checking ? YELLOW : MUTED)));
    if (!license.displayCode.isEmpty())
      licenseText.addView(
          text("Código: " + license.displayCode, compactUi ? 9.5f : 10.5f, MUTED));
    licenseRow.addView(licenseText, new LinearLayout.LayoutParams(0, -2, 1));
    TextView activate = button("Activar", this::showActivationDialog);
    activate.setMinHeight(dp(compactUi ? 42 : 46));
    licenseRow.addView(
        activate, new LinearLayout.LayoutParams(dp(compactUi ? 100 : 116), -2));
    profile.addView(licenseRow);

    gap(profile, 7);
    profile.addView(
        button(
            "Permisos",
            () -> {
              permissionDeferred = false;
              checkPermissions();
            }));

    TextView author = text("Creado por Frank Carlos", compactUi ? 10.5f : 12, MUTED);
    author.setGravity(Gravity.CENTER);
    author.setPadding(0, dp(compactUi ? 8 : 12), 0, dp(compactUi ? 2 : 4));
    profile.addView(author);
  }

  private void showActivationDialog() {
    if (licenseManager == null) licenseManager = LicenseManager.get(this);
    LicenseManager.Snapshot snapshot = licenseManager.snapshot();

    LinearLayout c = col();
    c.setPadding(dp(20), dp(18), dp(20), dp(12));

    String title =
        snapshot.state == LicenseState.PERMANENT_ACTIVE
            ? "Activación permanente"
            : "Activación de COCOTAXI";
    TextView heading = bold(title, compactUi ? 19 : 22, WHITE);
    heading.setGravity(Gravity.CENTER);
    c.addView(heading);
    gap(c, 12);

    String statusText;
    if (snapshot.state == LicenseState.PERMANENT_ACTIVE)
      statusText = "COCOTAXI está activado permanentemente en este dispositivo.";
    else if (snapshot.state == LicenseState.TRIAL_ACTIVE)
      statusText = "COCOTAXI tiene una prueba activa en este dispositivo.";
    else if (snapshot.state == LicenseState.BLOCKED)
      statusText = "La activación de este dispositivo está bloqueada.";
    else if (snapshot.state == LicenseState.TRIAL_EXPIRED)
      statusText = "La prueba terminó. Contacta para activar COCOTAXI.";
    else statusText = licenseLabel(snapshot);
    TextView status =
        text(
            statusText,
            compactUi ? 11.5f : 13,
            snapshot.allowsUse() ? GREEN : (snapshot.checking ? YELLOW : MUTED));
    status.setGravity(Gravity.CENTER);
    c.addView(status);

    gap(c, 13);
    TextView codeLabel = text("Código del dispositivo", compactUi ? 10.5f : 12, MUTED);
    codeLabel.setGravity(Gravity.CENTER);
    c.addView(codeLabel);
    String code = snapshot.displayCode;
    if (code == null || code.isEmpty()) {
      try {
        code = DeviceIdentity.displayCode(DeviceIdentity.hash(this));
      } catch (RuntimeException ignored) {
        code = "SIN CÓDIGO";
      }
    }
    TextView codeView = bold(code, compactUi ? 19 : 22, YELLOW);
    codeView.setGravity(Gravity.CENTER);
    c.addView(codeView);
    TextView note =
        text(
            "Este código identifica la activación de este dispositivo.",
            compactUi ? 10.5f : 11.5f,
            MUTED);
    note.setGravity(Gravity.CENTER);
    c.addView(note);

    gap(c, 12);
    AlertDialog[] holder = new AlertDialog[1];
    c.addView(
        primaryButton("CONTACTAR POR WHATSAPP", this::openLicenseWhatsApp),
        new LinearLayout.LayoutParams(-1, -2));
    gap(c, 7);
    c.addView(
        button(
            "COMPROBAR ACTIVACIÓN",
            () -> {
              if (holder[0] != null) holder[0].dismiss();
              refreshLicense(true);
            }),
        new LinearLayout.LayoutParams(-1, -2));

    gap(c, 7);
    LinearLayout cancelRow = row();
    cancelRow.setGravity(Gravity.RIGHT);
    TextView cancel = bold("Cancelar", compactUi ? 11.5f : 12.5f, YELLOW);
    cancel.setGravity(Gravity.RIGHT);
    cancel.setPadding(dp(16), dp(8), dp(2), dp(6));
    cancel.setOnClickListener(v -> {
      if (holder[0] != null) holder[0].dismiss();
    });
    cancelRow.addView(cancel, new LinearLayout.LayoutParams(-2, -2));
    c.addView(cancelRow);

    ScrollView scroll = new ScrollView(this);
    scroll.addView(c);
    AlertDialog d = new AlertDialog.Builder(this).setView(scroll).create();
    holder[0] = d;
    d.show();
  }

  private void gapHorizontal(LinearLayout l, int width) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(dp(width), 1));
  }

  private void editGoals(int mode) {
    LinearLayout c=form();
    EditText amount=field(c,mode==0?"Meta semanal":mode==1?"Meta por jornada":"Meta por hora",
        decimal(mode==0?draft.weeklyGoal:mode==1?draft.dailyGoal:draft.realGoalHour));
    c.addView(text("Las otras dos metas se calculan automáticamente.",12,MUTED));
    dialog("Tu meta",c,"Aplicar",()->{
      double n=number(amount);
      if(n<=0 || n>1000000) throw new IllegalArgumentException("Importe entre 1 y 1.000.000");
      draft.goalMode=mode;
      if(mode==0) draft.weeklyGoal=n; else if(mode==1) draft.dailyGoal=n; else draft.realGoalHour=n;
      SettingsStore.normalizeGoals(draft);
      settingsDirty = true;
    });
  }
  private void editSchedule() {
    LinearLayout c=form();
    final int[] mask={draft.workDaysMask};
    final EditText[] hours = new EditText[7];
    String[] days={"Lunes","Martes","Miércoles","Jueves","Viernes","Sábado","Domingo"};
    for(int i=0;i<7;i++) {
      final int bit=i;
      LinearLayout day = row();
      CheckBox b=new CheckBox(this);b.setText(days[i]);b.setTextColor(WHITE);
      b.setChecked((mask[0]&(1<<i))!=0);
      day.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
      hours[i] = new EditText(this);
      hours[i].setTextColor(WHITE);
      hours[i].setHintTextColor(MUTED);
      hours[i].setSingleLine(true);
      hours[i].setGravity(Gravity.CENTER);
      hours[i].setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
      double saved = draft.workDayHours != null && draft.workDayHours.length == 7 ? draft.workDayHours[i] : draft.targetHoursDay;
      hours[i].setText(saved > 0 ? decimal(saved) : "");
      hours[i].setHint("horas");
      hours[i].setEnabled(b.isChecked());
      hours[i].setBackground(background(PANEL_2, BORDER));
      day.addView(hours[i], new LinearLayout.LayoutParams(dp(94), dp(43)));
      b.setOnCheckedChangeListener((v,on)->{
        if(on) { mask[0]|=1<<bit; if(hours[bit].getText().toString().trim().isEmpty()) hours[bit].setText("8.00"); }
        else mask[0]&=~(1<<bit);
        hours[bit].setEnabled(on);
      });
      c.addView(day);
    }
    dialog("Días y horas de trabajo",c,"Aplicar",()->{
      if(mask[0]==0)throw new IllegalArgumentException("Elige al menos un día de trabajo");
      double[] perDay = new double[7];
      for (int i=0;i<7;i++) {
        if ((mask[0] & (1<<i)) == 0) continue;
        double h=number(hours[i]);
        if(h<1||h>18)throw new IllegalArgumentException("Configura entre 1 y 18 horas para cada día elegido");
        perDay[i]=h;
      }
      draft.workDayHours=perDay;draft.workDaysMask=mask[0];SettingsStore.normalizeGoals(draft);
      settingsDirty = true;
    });
  }

  private void persistDraft() {
    try {
      if (draft.autoAccept && draft.payoutMode == 0)
        throw new IllegalArgumentException(
            "Configura el pago antes de activar la aceptación automática");
      SettingsStore.save(this, draft);
      draft = null;
      settingsDirty = false;
      draftText.clear();
      stopService(new Intent(this, CocotaxiOverlayService.class));
      if (CocoStore.get(this).session().active) startOverlay();
      toast("Ajustes guardados");
      render();
    } catch (Exception e) {
      toast(e.getMessage());
    }
  }

  private void advancedSettings() {
    body.addView(button("‹ Volver a Ajustes",()->{advanced=false;render();}));
    LinearLayout c=card(body,"PAGO Y RECOMENDACIONES",LineIconView.GEAR);
    c.addView(button("Estimación del cobro",this::editPayment));
    c.addView(button("Tiempos y límites",this::editTimes));
    c.addView(button("Demanda",this::editAutomation));
    c.addView(button("Registrar liquidación diaria",()->billing(null)));
    body.addView(button("Guardar ajustes",this::persistDraft));
  }
  private void editPayment() {
    LinearLayout c=form();
    final int[] mode={draft.payoutMode};
    spinner(c,"Modelo de pago",new String[]{"Sin calibrar","Factor manual","Parejas verificadas","Cierres diarios (provisional sin datos)"},mode[0],n->mode[0]=n);
    EditText factor=field(c,"Factor manual",decimal(draft.factor));
    EditText cost=field(c,"Gasto por km (0 para medir cobro)",decimal(draft.costKm));
    CocoStore.Calibration cal=CocoStore.get(this).dailyCalibration();
    c.addView(text("Factor diario: "+decimal(cal.factor)+(cal.provisional?" · provisional, precisión no validada":" · "+cal.count+" cierres")+". Usa el mismo período del historial y excluye propinas y premios.",12,MUTED));
    dialog("Cobro estimado",c,"Aplicar",()->{
      double f=number(factor),k=number(cost);if(f<.1||f>2||k>100)throw new IllegalArgumentException("Factor 0,1–2; gasto 0–100");
      draft.payoutMode=mode[0];draft.factor=f;draft.costKm=k;
      settingsDirty = true;
    });
  }
  private void editTimes() {
    LinearLayout c=form();
    EditText wait=field(c,"Espera al pasajero (min)",decimal(draft.waitPassenger));
    c.addView(text("La espera real cuenta en tu sesión. No se añade una espera futura a las ofertas.",12,MUTED));
    EditText pickup=field(c,"Máxima recogida (min; 0 sin límite)",decimal(draft.maxPickupMin));
    EditText km=field(c,"Máxima recogida (km; 0 sin límite)",decimal(draft.maxPickupKm));
    dialog("Tiempos y recogida",c,"Aplicar",()->{
      double a=number(wait),d=number(pickup),e=number(km);
      if(a>60||d>60||e>30)throw new IllegalArgumentException("Revisa los límites de tiempo y distancia");
      draft.waitPassenger=a;draft.waitNext=0;draft.maxPickupMin=d;draft.maxPickupKm=e;
      settingsDirty = true;
    });
  }
  private void editAutomation() {
    LinearLayout c=form();
    final int[] demand={draft.demandMode};
    final boolean[] flags={draft.mapDemand};
    spinner(c,"Demanda",new String[]{"Automática","Alta","Media","Baja"},demand[0],n->demand[0]=n);
    toggle(c,"Leer demanda del mapa","map",flags[0],on->flags[0]=on);
    c.addView(text("La ventana flotante permanece activa durante la sesión. En listas solo recomienda el mejor visible.",12,MUTED));
    c.addView(button("Autorizar captura de respaldo",()->startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),92)));
    dialog("Demanda",c,"Aplicar",()->{
      draft.demandMode=demand[0];draft.mapDemand=flags[0];draft.floatingEnabled=true;
      settingsDirty = true;
    });
  }

  private void refreshLicense(boolean showFeedback) {
    if (licenseManager == null) licenseManager = LicenseManager.get(this);
    licenseManager.refresh(
        snapshot ->
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  if (showFeedback) toast(licenseLabel(snapshot));
                  if (profileView && pinUnlocked) render();
                }));
  }

  private boolean ensureLicenseForConnect() {
    if (licenseManager == null) licenseManager = LicenseManager.get(this);
    LicenseManager.Snapshot snapshot = licenseManager.snapshot();
    if (snapshot.allowsUse()) return true;
    if (snapshot.state == LicenseState.UNKNOWN || snapshot.state == LicenseState.UNAVAILABLE) {
      refreshLicense(false);
      new AlertDialog.Builder(this)
          .setTitle("Comprobar licencia")
          .setMessage(
              snapshot.state == LicenseState.UNAVAILABLE && !snapshot.error.isEmpty()
                  ? "No se pudo comprobar la licencia. " + snapshot.error
                  : "Coco está comprobando la activación con el servidor. Intenta conectar de nuevo en unos segundos.")
          .setPositiveButton("Reintentar", (d, w) -> refreshLicense(true))
          .setNeutralButton("WhatsApp", (d, w) -> openLicenseWhatsApp())
          .setNegativeButton("Cerrar", null)
          .show();
      return false;
    }
    if (snapshot.checking) {
      toast("Comprobando licencia…");
      return false;
    }
    String message =
        snapshot.state == LicenseState.BLOCKED
            ? "Esta licencia está bloqueada."
            : "El período de prueba terminó. Activa Coco para volver a conectar.";
    if (!snapshot.displayCode.isEmpty()) message += "\n\nCódigo: " + snapshot.displayCode;
    new AlertDialog.Builder(this)
        .setTitle("Licencia de COCOTAXI")
        .setMessage(message)
        .setPositiveButton("WhatsApp", (d, w) -> openLicenseWhatsApp())
        .setNeutralButton("Comprobar", (d, w) -> refreshLicense(true))
        .setNegativeButton("Cerrar", null)
        .show();
    return false;
  }

  private String licenseLabel(LicenseManager.Snapshot snapshot) {
    if (snapshot == null) return "Sin comprobar";
    if (snapshot.checking) return "Comprobando…";
    switch (snapshot.state) {
      case PERMANENT_ACTIVE:
        return "Permanente activa";
      case TRIAL_ACTIVE:
        long seconds = Math.max(0, snapshot.remainingTrialSeconds);
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        if (days > 0) return "Prueba activa · " + days + " d " + hours + " h restantes";
        return "Prueba activa · " + Math.max(1, hours) + " h restantes";
      case TRIAL_EXPIRED:
        return "Prueba finalizada";
      case BLOCKED:
        return "Licencia bloqueada";
      case UNAVAILABLE:
        return snapshot.error.isEmpty() ? "Servidor no disponible" : "No se pudo comprobar";
      case UNKNOWN:
      default:
        return "Sin comprobar";
    }
  }

  private void openLicenseWhatsApp() {
    if (licenseManager == null) licenseManager = LicenseManager.get(this);
    LicenseManager.Snapshot snapshot = licenseManager.snapshot();
    String code = snapshot.displayCode;
    if (code == null || code.isEmpty()) {
      try {
        code = DeviceIdentity.displayCode(DeviceIdentity.hash(this));
      } catch (RuntimeException ignored) {
        code = "sin código disponible";
      }
    }
    String message = "Hola, quiero activar COCOTAXI. Mi código de dispositivo es " + code;
    Intent intent =
        new Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/59898819252?text=" + Uri.encode(message)));
    try {
      startActivity(intent);
    } catch (RuntimeException e) {
      toast("No se pudo abrir WhatsApp");
    }
  }

  private void checkPermissions() {
    if (isFinishing() || permissionDialog || permissionDeferred) return;
    Intent intent=null; String explanation="";
    if(!Settings.canDrawOverlays(this)) {
      intent=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()));
      explanation="Activa Mostrar sobre otras aplicaciones para ver las recomendaciones de Coco.";
    } else {
      String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
      ComponentName own=new ComponentName(this,CocotaxiAccessibilityService.class);
      boolean found=false;
      if(enabled!=null)for(String entry:enabled.split(":"))if(own.equals(ComponentName.unflattenFromString(entry)))found=true;
      if(!found) {intent=new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);explanation="Activa COCOTAXI en Accesibilidad para leer las ofertas y reconocer viajes aceptados. La aceptación automática solo se activa en Automatización.";}
    }
    if(intent==null) {
      if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED
          && !getPreferences(0).getBoolean("notificationAsked",false)) {
        getPreferences(0).edit().putBoolean("notificationAsked",true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},91);
      }
      return;
    }
    permissionDialog=true; final Intent action=intent;
    new AlertDialog.Builder(this).setTitle("Preparar COCOTAXI").setMessage(explanation)
      .setPositiveButton("Autorizar",(d,w)->{permissionDialog=false;try{startActivity(action);}catch(Exception e){permissionDeferred=true;toast("Abre este permiso en los ajustes de Android");}})
      .setNegativeButton("Más tarde",(d,w)->{permissionDialog=false;permissionDeferred=true;})
      .setOnCancelListener(d->{permissionDialog=false;permissionDeferred=true;}).show();
  }

  private interface BoolChange {
    void set(boolean value);
  }

  private interface IntChange {
    void set(int value);
  }

  private void toggle(
      LinearLayout c, String label, String key, boolean initial, BoolChange action) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(WHITE);
    s.setTextSize(13);
    s.setPadding(0, dp(8), 0, dp(8));
    boolean value = draftText.containsKey(key) ? Boolean.parseBoolean(draftText.get(key)) : initial;
    s.setChecked(value);
    action.set(value);
    s.setOnCheckedChangeListener(
        (v, on) -> {
          action.set(on);
          draftText.put(key, String.valueOf(on));
        });
    c.addView(s, new LinearLayout.LayoutParams(-1, -2));
  }

  private void spinner(LinearLayout c, String title, String[] labels, int value, IntChange action) {
    c.addView(text(title, 12, MUTED));
    Spinner s = new Spinner(this);
    ArrayAdapter<String> a =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
    s.setAdapter(a);
    s.setSelection(Math.max(0, Math.min(labels.length - 1, value)));
    s.setOnItemSelectedListener(
        new android.widget.AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(android.widget.AdapterView<?> p) {}

          public void onItemSelected(android.widget.AdapterView<?> p, View v, int n, long id) {
            action.set(n);
          }
        });
    c.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
  }

  private void input(LinearLayout c, String key, String label, double value) {
    EditText e = field(c, label, draftText.getOrDefault(key, decimal(value)));
    inputs.put(key, e);
  }

  private void captureDraft() {
    for (Map.Entry<String, EditText> e : inputs.entrySet())
      draftText.put(e.getKey(), e.getValue().getText().toString());
  }

  private double value(String key, double min, double max) {
    double v = number(inputs.get(key));
    if (v < min || v > max)
      throw new IllegalArgumentException(key + ": debe estar entre " + min + " y " + max);
    return v;
  }

  private void billing(String raw) {
    LinearLayout c = form();
    c.addView(
        text(
            "Un registro por fecha; guardar otra vez lo reemplaza. El cierre enseña cuánto del"
                + " historial llega a ganancias. No añade ingresos por segunda vez. Stars queda"
                + " incluido; propinas y bonos extraordinarios se separan.",
            12,
            MUTED));
    EditText date = field(c, "Fecha (AAAA-MM-DD)", LocalDate.now(CocoStore.ZONE).toString());
    date.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
    EditText total = field(c, "Total de ganancias", extract(raw, "total(?: de)? ganancias")),
        tips = field(c, "Propinas", "0"),
        bonus = field(c, "Bonos extraordinarios", "0"),
        tolls = field(c, "Peajes reembolsados incluidos", "0"),
        history = field(c, "Suma del historial del mismo día", "");
    Switch eligible = new Switch(this);
    eligible.setText("Día cerrado, historial completo y mismo importe que las ofertas");
    c.addView(eligible);
    c.addView(
        text(
            "Actívalo solo si coinciden día, conductor y todos los viajes. Los registros parciales"
                + " se guardan sin entrenar. No sumes Stars otra vez.",
            12,
            MUTED));
    if (raw != null) {
      c.addView(text("OCR para revisión:\n" + raw, 11, MUTED));
      String tip = extract(raw, "propinas?");
      if (!tip.isEmpty()) tips.setText(tip);
    }
    dialog(
        "Conciliar liquidación",
        c,
        "Guardar y comparar",
        () -> {
          double all = number(total),
              tip = number(tips),
              bon = number(bonus),
              toll = number(tolls),
              hist = number(history);
          CocoStore.get(this)
              .saveBilling(
                  date.getText().toString(), all, tip, bon, toll, hist, eligible.isChecked());
          new AlertDialog.Builder(this)
              .setTitle("Pago comparable: " + Money.exact(all - tip - bon - toll))
              .setMessage(
                  "Sin propinas, bonos extraordinarios ni reembolsos.\nFactor historial → pago: "
                      + (hist > 0
                          ? String.format(Locale.US, "%.6f", (all - tip - bon - toll) / hist)
                          : "sin suma de historial")
                      + (eligible.isChecked()
                          ? "\n"
                                + "Cierre incorporado al modelo diario. No es un cobro individual"
                                + " confirmado ni se suma otra vez a la sesión."
                          : "\nGuardado sin entrenar: cierre parcial o sin verificar."))
              .setPositiveButton("Entendido", null)
              .show();
        });
  }

  private String extract(String raw, String label) {
    if (raw == null) return "";
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?:" + label + ")\\s*[:>]?\\s*\\$\\s*([0-9][0-9.,]*)")
            .matcher(OfferParser.normalize(raw));
    return m.find() ? decimal(Money.parse(m.group(1))) : "";
  }

  private void demo() {
    LinearLayout c = form();
    EditText e =
        field(c, "Texto de una tarjeta", "Para ti $188\n3 min · 339 m\n9 min · 4.1 km\nAceptar");
    e.setSingleLine(false);
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    dialog(
        "Prueba sin registrar viajes",
        c,
        "Calcular",
        () -> {
          Offer o = OfferParser.parse(e.getText().toString(), "com.cabify.driver");
          CocoStore.Session s = CocoStore.get(this).session();
          s.active = true;
          s.paused = false;
          SettingsStore.Values v = SettingsStore.load(this);
          DecisionEngine.Result r =
              DecisionEngine.evaluate(
                  o, v, s, 2, CocoStore.get(this).calibrationForOffer(o.kind, v), PromotionStore.get(this));
          new AlertDialog.Builder(this)
              .setTitle("SIMULACIÓN · " + r.label())
              .setMessage(
                  r.reason
                      + "\nViaje: "
                      + Money.exact(r.hourly)
                      + "/h\nSesión después: "
                      + Money.exact(r.projected)
                      + "/h\nOferta mínima: "
                      + Money.exact(r.required))
              .setPositiveButton("Cerrar", null)
              .show();
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request == 94 && data.getData() != null) {
      java.io.File file = CocoDataStore.currentFile(this, System.currentTimeMillis());
      try (java.io.InputStream in = new java.io.FileInputStream(file);
           java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
        if (out == null) throw new java.io.IOException("Destino no disponible");
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
        toast("Observaciones exportadas");
      } catch (Exception e) { toast("No se pudieron exportar los datos"); }
      return;
    }
    if (request == 92) {
      startForegroundService(
          new Intent(this, CaptureService.class).putExtra("code", result).putExtra("data", data));
      return;
    }
    if (request == 93 && data.getData() != null) {
      try {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
          BitmapFactory.decodeStream(in, null, options);
        }
        options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 2400);
        options.inJustDecodeBounds = false;
        Bitmap b;
        try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
          b = BitmapFactory.decodeStream(in, null, options);
        }
        if (b == null) throw new IllegalArgumentException("Imagen no válida");
        if (ocr == null) ocr = new ScreenOcrEngine();
        toast("Leyendo captura…");
        ocr.process(
            b,
            new ScreenOcrEngine.Callback() {
              public void onText(String t) {
                b.recycle();
                if (!isDestroyed()) billing(t);
              }

              public void onError(Exception e) {
                b.recycle();
                toast("No se pudo leer; introduce los datos manualmente");
              }
            });
      } catch (Exception e) {
        toast("No se pudo abrir la imagen");
      }
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s == null ? "Revisa los datos" : s, Toast.LENGTH_LONG).show();
  }

  private static String duration(double minutes) {
    long seconds = Math.max(0, Math.round(minutes * 60));
    long h = seconds / 3600;
    long m = (seconds % 3600) / 60;
    long s = seconds % 60;
    return h > 0
        ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        : String.format(Locale.US, "%02d:%02d", m, s);
  }

  private static String stamp(long time) {
    return DateTimeFormatter.ofPattern("dd/MM · HH:mm")
        .withZone(CocoStore.ZONE)
        .format(Instant.ofEpochMilli(time));
  }
}
