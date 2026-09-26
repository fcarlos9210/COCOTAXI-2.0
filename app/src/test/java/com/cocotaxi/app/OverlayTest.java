package com.cocotaxi.app;

import static org.junit.Assert.*;

import android.content.*;
import android.view.*;
import android.widget.ImageView;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class OverlayTest {
  private ServiceController<CocotaxiOverlayService> controller;
  private CocotaxiOverlayService service;

  @Before
  public void setup() {
    Context c = RuntimeEnvironment.getApplication();
    CocoStore.closeForTests();
    c.deleteDatabase("cocotaxi_v3.db");
    c.getSharedPreferences(SettingsStore.PREFS, 0).edit().clear().commit();
    CocoStore.get(c).start();
    ShadowSettings.setCanDrawOverlays(true);
    controller = Robolectric.buildService(CocotaxiOverlayService.class).create();
    service = controller.get();
  }

  @After
  public void cleanup() {
    controller.destroy();
    CocoStore.closeForTests();
  }

  private View icon() throws Exception {
    java.lang.reflect.Field f = CocotaxiOverlayService.class.getDeclaredField("icon");
    f.setAccessible(true);
    return (View) f.get(service);
  }

  @Test
  public void tapOpensCoco() throws Exception {
    View icon = icon();
    assertNotNull(icon);
    icon.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 5, 5, 0));
    icon.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 5, 5, 0));
    Intent intent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    assertNotNull(intent);
    assertEquals(MainActivity.class.getName(), intent.getComponent().getClassName());
  }

  @Test
  public void dragDoesNotOpenCoco() throws Exception {
    View icon = icon();
    assertNotNull(icon);
    icon.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 5, 5, 0));
    icon.dispatchTouchEvent(MotionEvent.obtain(0, 20, MotionEvent.ACTION_MOVE, 105, 105, 0));
    icon.dispatchTouchEvent(MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 105, 105, 0));
    assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
  }

  @Test
  public void acceptedOfferMayRemainVisibleBrieflyAfterTripStarts() throws Exception {
    SettingsStore.Values values = new SettingsStore.Values();
    values.payoutMode = 1;
    values.factor = 1;
    Offer offer = OfferParser.parse("Aceptar\n$180 en app\n3 min\n12 min", "com.cabify.driver");
    DecisionEngine.Result result = DecisionEngine.evaluate(offer, values,
        CocoStore.get(service).session(), 2, null);
    String id = CocoStore.get(service).attempt(offer, result, false);
    assertNotNull(id);
    CocoStore.get(service).observe("Ir a origen Navegar Ver ruta");
    CocotaxiOverlayService.update(result, 1, 1, 2, false);
    java.lang.reflect.Field f = CocotaxiOverlayService.class.getDeclaredField("summary");
    f.setAccessible(true);
    assertEquals(View.GONE, ((View) f.get(service)).getVisibility());
    assertEquals(View.GONE, icon().getVisibility());
  }

  @Test
  public void recommendationHasTwoStatesAndIdentifiesVisibleCard() throws Exception {
    SettingsStore.Values settings = new SettingsStore.Values();
    settings.payoutMode = 1;
    settings.factor = 1;
    settings.operationalGoalHour = 700;
    settings.realGoalHour = 700;
    settings.maxPickupKm = 0;
    settings.maxPickupMin = 0;
    Offer offer =
        OfferParser.parse(
            "Aceptar\n$188 en app\n3 min · 339 m\n9 min · 4.1 km", "com.cabify.driver");
    DecisionEngine.Result r =
        DecisionEngine.evaluate(offer, settings, CocoStore.get(service).session(), 3, null);
    CocotaxiOverlayService.update(r, 2, 2, 3, false);
    java.lang.reflect.Field df = CocotaxiOverlayService.class.getDeclaredField("decision");
    df.setAccessible(true);
    android.widget.TextView label = (android.widget.TextView) df.get(service);
    assertEquals("ACEPTABLE", label.getText().toString());
    java.lang.reflect.Field rf = CocotaxiOverlayService.class.getDeclaredField("reason");
    rf.setAccessible(true);
    assertEquals(View.GONE, ((View) rf.get(service)).getVisibility());
    assertEquals("", ((android.widget.TextView) rf.get(service)).getText().toString());
    java.lang.reflect.Field sf = CocotaxiOverlayService.class.getDeclaredField("summary");
    sf.setAccessible(true);
    assertTrue(
        ((android.widget.TextView) sf.get(service)).getText().toString().contains("Oferta 2 de 2"));
    java.lang.reflect.Field pf = CocotaxiOverlayService.class.getDeclaredField("panel");
    pf.setAccessible(true);
    View panel = (View) pf.get(service);
    int width = Math.round(340 * service.getResources().getDisplayMetrics().density);
    panel.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    panel.layout(0, 0, width, panel.getMeasuredHeight());
    android.graphics.Bitmap b =
        android.graphics.Bitmap.createBitmap(
            width, panel.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
    panel.draw(new android.graphics.Canvas(b));
    java.io.File dir = new java.io.File("build/ui-preview");
    dir.mkdirs();
    try (java.io.FileOutputStream f =
        new java.io.FileOutputStream(new java.io.File(dir, "overlay.png"))) {
      b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, f);
    }
    b.recycle();
    settings.operationalGoalHour = settings.realGoalHour = 2000;
    CocotaxiOverlayService.update(
        DecisionEngine.evaluate(offer, settings, CocoStore.get(service).session(), 3, null),
        2,
        2,
        3,
        false);
    assertEquals("RECHAZA", label.getText().toString());
    assertEquals(View.GONE, ((View) rf.get(service)).getVisibility());
    assertEquals("", ((android.widget.TextView) rf.get(service)).getText().toString());
    java.lang.reflect.Field cf = CocotaxiOverlayService.class.getDeclaredField("close");
    cf.setAccessible(true);
    android.widget.TextView close = (android.widget.TextView) cf.get(service);
    assertTrue(close.getTextSize() / service.getResources().getDisplayMetrics().scaledDensity >= 36);
  }

  @Test
  public void floatingCocoIconUsesLargerTouchSize() throws Exception {
    View icon = icon();
    int expected = Math.round(76 * service.getResources().getDisplayMetrics().density);
    assertEquals(expected, icon.getLayoutParams().width);
    assertEquals(expected, icon.getLayoutParams().height);
  }
}
