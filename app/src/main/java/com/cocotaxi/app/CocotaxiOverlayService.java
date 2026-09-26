package com.cocotaxi.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

public final class CocotaxiOverlayService extends Service {
  private static CocotaxiOverlayService instance;
  private WindowManager windows;
  private LinearLayout panel;
  private TextView summary, decision, pickup, journey, total, projected, amount, reason, close;
  private LinearLayout metrics, bottom;
  private String dismissedKey = "";
  private RoundedIconView icon;
  private TextView handle;
  private WindowManager.LayoutParams layout;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private CocoStore.Session tickSession;
  private long tickSessionAt, tickRevision = -1L;
  private long shownAt;
  private long holdUntil;
  private boolean minimized = true;
  private static final long RESULT_VISIBLE_MS = 4000L;
  private String lastKey = "";
  private android.media.ToneGenerator tone;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          CocoStore store = CocoStore.get(CocotaxiOverlayService.this);
          long now = SystemClock.elapsedRealtime();
          long revision = store.revision();
          if (tickSession == null || tickRevision != revision || now - tickSessionAt >= 1000L) {
            tickSession = store.session();
            tickSessionAt = now;
            tickRevision = store.revision();
          }
          if (!tickSession.active) {
            stopSelf();
            return;
          }
          if (tickSession.paused
              || (holdUntil > 0 && now >= holdUntil)
              || (holdUntil == 0 && now - shownAt > RESULT_VISIBLE_MS)) hideCard();
          // Timer remains visually precise without hammering SQLite on every tick.
          handler.postDelayed(this, 200);
        }
      };


  public static void update(
      DecisionEngine.Result r, int position, int count, int demand, boolean ocr) {
    if (instance != null) instance.display(r, position, count, demand, ocr);
  }

  public static void clearOffer() {
    if (instance != null) instance.requestHide();
  }

  public static void autoAcceptResult(boolean success) {
    if (instance != null) instance.showAutoAcceptResult(success);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    instance = this;
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel(
            "coco_session", "Sesión COCOTAXI", NotificationManager.IMPORTANCE_LOW));
    Intent open =
        new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    PendingIntent pi =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification n =
        new Notification.Builder(this, "coco_session")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("COCOTAXI conectado")
            .setContentText("Toca para abrir tu sesión y pausar")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    startForeground(101, n);
    if (Settings.canDrawOverlays(this)) createWindow();
    handler.post(tick);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!CocoStore.get(this).session().active) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (panel == null && Settings.canDrawOverlays(this))
      createWindow();
    return START_NOT_STICKY;
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String t, int size, int color) {
    TextView v = new TextView(this);
    v.setText(t);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setPadding(dp(5), dp(3), dp(5), dp(3));
    return v;
  }

  private GradientDrawable frame(int fill, int border, int radius) {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(fill);
    bg.setCornerRadius(dp(radius));
    bg.setStroke(dp(1), border);
    return bg;
  }

  private TextView metric(int iconType, String title, LinearLayout row) {
    LinearLayout cell = new LinearLayout(this);
    cell.setOrientation(LinearLayout.VERTICAL);
    cell.setGravity(Gravity.CENTER);
    LinearLayout caption = new LinearLayout(this);
    caption.setGravity(Gravity.CENTER);
    LineIconView iconView = new LineIconView(this, iconType);
    iconView.setIconColor(0xff20e3a2);
    caption.addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));
    TextView label = text(title, 10, 0xffced8df);
    label.setGravity(Gravity.CENTER_VERTICAL);
    caption.addView(label);
    cell.addView(caption, new LinearLayout.LayoutParams(-1, dp(29)));
    TextView value = text("", 18, Color.WHITE);
    value.setGravity(Gravity.CENTER);
    value.setTypeface(null, android.graphics.Typeface.BOLD);
    value.setMaxLines(1);
    value.setAutoSizeTextTypeUniformWithConfiguration(10, 18, 1, 2);
    cell.addView(value, new LinearLayout.LayoutParams(-1, dp(28)));
    row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
    return value;
  }

  private void divider(LinearLayout row) {
    View line = new View(this);
    line.setBackgroundColor(0xff173650);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(44));
    lp.gravity = Gravity.CENTER_VERTICAL;
    row.addView(line, lp);
  }

  private GradientDrawable expandedPanelBackground() {
    GradientDrawable bg =
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xff0a1d2e, 0xff03111d});
    bg.setCornerRadius(dp(18));
    bg.setStroke(dp(2), 0xff20e3a2);
    return bg;
  }

  private GradientDrawable minimizedPanelBackground() {
    GradientDrawable bg = new GradientDrawable();
    bg.setColor(Color.TRANSPARENT);
    return bg;
  }

  private void createWindow() {
    windows = getSystemService(WindowManager.class);
    panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(5), dp(5), dp(5), dp(5));
    panel.setBackground(minimizedPanelBackground());
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    panel.addView(top);
    icon = new RoundedIconView(this);
    icon.setContentDescription("Abrir COCOTAXI; arrastra para mover");
    // The minimized Coco bubble must have a real measured size. In 2.5.2 this
    // view was added as 0 x 0, so it became VISIBLE but remained invisible.
    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(76), dp(76));
    iconLp.gravity = Gravity.CENTER;
    top.addView(icon, iconLp);
    icon.setVisibility(View.GONE);
    handle = text("COCO · OFERTA", 11, 0xff7f93a8);
    handle.setGravity(Gravity.CENTER);
    top.addView(handle, new LinearLayout.LayoutParams(0, dp(34), 1));
    close = text("×", 36, Color.WHITE);
    close.setGravity(Gravity.CENTER);
    close.setTypeface(null, android.graphics.Typeface.BOLD);
    close.setPadding(dp(5), 0, dp(5), dp(9));
    close.setContentDescription("Ocultar esta oferta");
    top.addView(close, new LinearLayout.LayoutParams(dp(48), dp(42)));
    close.setOnClickListener(v -> { dismissedKey = lastKey; hideCard(); });
    metrics = new LinearLayout(this);
    metrics.setPadding(dp(2), dp(3), dp(2), dp(3));
    metrics.setBackground(frame(0xff0a1d2e, 0xff173650, 14));
    pickup = metric(LineIconView.PIN, "Recogida", metrics);
    divider(metrics);
    journey = metric(LineIconView.TARGET, "Viaje", metrics);
    divider(metrics);
    total = metric(LineIconView.CLOCK, "Total", metrics);
    divider(metrics);
    projected = metric(LineIconView.ROAD, "Después\ndel viaje", metrics);
    panel.addView(metrics);
    bottom = new LinearLayout(this);
    bottom.setGravity(Gravity.CENTER_VERTICAL);
    bottom.setPadding(0, dp(4), 0, 0);
    LineIconView car = new LineIconView(this, LineIconView.CAR);
    car.setIconColor(0xff20e3a2);
    bottom.addView(car, new LinearLayout.LayoutParams(dp(40), dp(40)));
    amount = text("", 42, 0xff63ff6b);
    amount.setTypeface(null, android.graphics.Typeface.BOLD);
    amount.setGravity(Gravity.CENTER);
    amount.setMaxLines(1);
    amount.setAutoSizeTextTypeUniformWithConfiguration(24, 42, 1, 2);
    bottom.addView(amount, new LinearLayout.LayoutParams(0, dp(62), .95f));
    divider(bottom);
    LinearLayout verdict = new LinearLayout(this);
    verdict.setGravity(Gravity.CENTER);
    verdict.setBackground(frame(0xff0d2438, 0xff20e3a2, 14));
    LineIconView check = new LineIconView(this, LineIconView.CHECK);
    check.setIconColor(0xff20e3a2);
    verdict.addView(check, new LinearLayout.LayoutParams(dp(42), dp(42)));
    LinearLayout verdictCopy = new LinearLayout(this);
    verdictCopy.setOrientation(LinearLayout.VERTICAL);
    verdictCopy.setGravity(Gravity.CENTER);
    decision = text("", 24, 0xfff7d93d);
    decision.setTypeface(null, android.graphics.Typeface.BOLD);
    decision.setGravity(Gravity.CENTER);
    decision.setMaxLines(1);
    decision.setAutoSizeTextTypeUniformWithConfiguration(13, 22, 1, 2);
    reason = text("", 10, Color.WHITE);
    reason.setGravity(Gravity.CENTER);
    verdictCopy.addView(decision, new LinearLayout.LayoutParams(-1, dp(32)));
    verdictCopy.addView(reason);
    verdict.addView(verdictCopy, new LinearLayout.LayoutParams(0, -2, 1));
    bottom.addView(verdict, new LinearLayout.LayoutParams(0, dp(62), 1.15f));
    panel.addView(bottom);
    summary = text("", 9, 0xff7f93a8);
    summary.setGravity(Gravity.CENTER);
    panel.addView(summary);
    summary.setVisibility(View.GONE);
    layout =
        new WindowManager.LayoutParams(
            dp(86),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
    layout.gravity = Gravity.TOP | Gravity.START;
    layout.x = dp(12);
    layout.y = dp(100);
    handle.setVisibility(View.GONE);
    summary.setVisibility(View.GONE);
    decision.setVisibility(View.GONE);
    close.setVisibility(View.GONE);
    metrics.setVisibility(View.GONE);
    bottom.setVisibility(View.GONE);
    icon.setVisibility(View.VISIBLE);
    icon.setOnClickListener(v -> openApp());
    drag(icon, true);
    drag(handle, false);
    try {
      windows.addView(panel, layout);
    } catch (RuntimeException e) {
      panel = null;
    }
  }

  private void openApp() {
    if (MainActivity.minimizeFromOverlay()) return;
    startActivity(
        new Intent(this, MainActivity.class)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }

  private void drag(View view, boolean clickable) {
    view.setOnTouchListener(
        new View.OnTouchListener() {
          float x, y;
          int px, py;
          boolean moved;
          final int slop = ViewConfiguration.get(CocotaxiOverlayService.this).getScaledTouchSlop();

          public boolean onTouch(View v, android.view.MotionEvent event) {
            switch (event.getActionMasked()) {
              case MotionEvent.ACTION_DOWN:
                x = event.getRawX();
                y = event.getRawY();
                px = layout.x;
                py = layout.y;
                moved = false;
                return true;
              case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - x, dy = event.getRawY() - y;
                if (Math.hypot(dx, dy) > slop) moved = true;
                if (moved) {
                  int width = getResources().getDisplayMetrics().widthPixels,
                      height = getResources().getDisplayMetrics().heightPixels;
                  layout.x = Math.max(0, Math.min(width - panel.getWidth(), px + (int) dx));
                  layout.y = Math.max(0, Math.min(height - panel.getHeight(), py + (int) dy));
                  windows.updateViewLayout(panel, layout);
                }
                return true;
              case MotionEvent.ACTION_UP:
                if (!moved && clickable) v.performClick();
                return true;
              case MotionEvent.ACTION_CANCEL:
                return true;
            }
            return false;
          }
        });
  }

  private void display(
      DecisionEngine.Result r, int position, int count, int demand, boolean fromOcr) {
    if (panel == null) return;
    CocoStore store = CocoStore.get(this);
    CocoStore.Session session = store.session();
    if (!session.active || session.paused) {
      hideCard();
      return;
    }
    if (r.offer.key.equals(dismissedKey)) return;
    shownAt = SystemClock.elapsedRealtime();
    holdUntil = 0L;
    int width = getResources().getDisplayMetrics().widthPixels;
    layout.width = Math.min(dp(350), width - dp(16));
    layout.x = Math.max(0, Math.min(layout.x, width - layout.width));
    pickup.setText(fmt(r.offer.pickupMin) + " min");
    journey.setText(fmt(r.offer.tripMin) + " min");
    total.setText(fmt(r.minutes) + " min");
    projected.setText(Money.format(r.projected) + "/h");
    total.setTextSize(25);
    total.setTextColor(0xfff7d93d);
    total.setTypeface(null, android.graphics.Typeface.BOLD);
    amount.setTextColor(0xff63ff6b);
    decision.setTextColor(0xfff7d93d);
    amount.setText(Money.format(r.payout));
    amount.setContentDescription("Cobro estimado " + Money.exact(r.payout));
    decision.setText(r.accept ? "ACEPTABLE" : "RECHAZA");
    decision.setAutoSizeTextTypeUniformWithConfiguration(r.accept ? 13 : 10, r.accept ? 22 : 16, 1, 2);
    reason.setText("");
    reason.setVisibility(View.GONE);
    summary.setText("Oferta " + position + " de " + count + " visibles · cobro estimado"
        + (r.minutes > r.offer.totalMinutes() ? " · total incluye esperas" : "")
        + (r.provisional ? " · provisional" : "")
        + (fromOcr ? " · OCR" : ""));
    panel.setBackground(expandedPanelBackground());
    minimized = false;
    icon.setVisibility(View.GONE);
    handle.setVisibility(View.VISIBLE);
    close.setVisibility(View.VISIBLE);
    metrics.setVisibility(View.VISIBLE);
    bottom.setVisibility(View.VISIBLE);
    summary.setVisibility(View.GONE);
    decision.setVisibility(View.VISIBLE);
    windows.updateViewLayout(panel, layout);
    if (!lastKey.equals(r.offer.key) && r.accept) {
      lastKey = r.offer.key;
      SettingsStore.Values v = SettingsStore.load(this);
      if (v.vibrate) {
        android.os.Vibrator vib = getSystemService(android.os.Vibrator.class);
        if (vib != null && vib.hasVibrator())
          vib.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
      }
    }
    lastKey = r.offer.key;
  }

  private void showAutoAcceptResult(boolean success) {
    if (panel == null || metrics.getVisibility() != View.VISIBLE) return;
    decision.setText(success ? "ACEPTABLE" : "ERROR");
    decision.setTextColor(success ? 0xfff7d93d : 0xffff7a8a);
    reason.setText("");
    reason.setVisibility(View.GONE);
    holdUntil = SystemClock.elapsedRealtime() + RESULT_VISIBLE_MS;
  }

  private void requestHide() {
    long now = SystemClock.elapsedRealtime();
    if (holdUntil > now) return;
    if (shownAt > 0 && now - shownAt < RESULT_VISIBLE_MS) {
      holdUntil = shownAt + RESULT_VISIBLE_MS;
      return;
    }
    hideCard();
  }

  private static String fmt(double n) {
    return n == (int) n ? "" + (int) n : String.format(java.util.Locale.US, "%.1f", n);
  }

  private void hideCard() {
    if (panel == null || minimized) return;
    minimized = true;
    holdUntil = 0L;
    handle.setVisibility(View.GONE);
    summary.setVisibility(View.GONE);
    decision.setVisibility(View.GONE);
    close.setVisibility(View.GONE);
    metrics.setVisibility(View.GONE);
    bottom.setVisibility(View.GONE);
    icon.setVisibility(View.VISIBLE);
    panel.setBackground(minimizedPanelBackground());
    layout.width = dp(86);
    try {
      windows.updateViewLayout(panel, layout);
    } catch (RuntimeException ignored) {
    }
  }

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (panel != null)
      try {
        windows.removeView(panel);
      } catch (RuntimeException ignored) {
      }
    if (tone != null) tone.release();
    instance = null;
    super.onDestroy();
  }
}
