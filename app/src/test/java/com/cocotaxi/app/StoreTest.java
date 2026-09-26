package com.cocotaxi.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import java.time.Duration;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StoreTest {
  Context c;
  CocoStore st;

  @Before
  public void setup() {
    c = RuntimeEnvironment.getApplication();
    CocoStore.closeForTests();
    c.deleteDatabase("cocotaxi_v3.db");
    st = CocoStore.get(c);
    st.start();
  }

  @After
  public void end() {
    CocoStore.closeForTests();
  }

  @Test
  public void startIdempotent() {
    String id = st.session().id;
    st.start();
    assertEquals(id, st.session().id);
  }

  @Test
  public void pauseStopsTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
    st.pause();
    double m = st.session().minutes;
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    assertEquals(m, st.session().minutes, .0001);
    st.pause();
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    assertEquals(m + 5, st.session().minutes, .0001);
  }

  @Test
  public void doubleStopDoesNotAddTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
    st.stop();
    st.stop();
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
    assertEquals(10, st.session().minutes, .001);
  }

  @Test
  public void correctDoesNotDoubleIncome() {
    String id = st.manual(200, 10, "test");
    st.correct(id, 180, 10, false, 0);
    st.correct(id, 180, 10, false, 0);
    assertEquals(170, st.session().money, .001);
    assertEquals(1, st.session().trips);
  }

  @Test
  public void deleteAndRestore() {
    String id = st.manual(200, 10, "test");
    st.delete(id);
    assertEquals(0, st.session().money, .001);
    st.restore(id);
    assertEquals(200, st.session().money, .001);
  }

  @Test
  public void separateSessions() {
    st.manual(200, 10, "a");
    st.stop();
    ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    st.start();
    assertEquals(0, st.session().money, .001);
    assertEquals(2, st.sessions().size());
  }

  private String attempt() {
    Offer o = OfferParser.parse("Para ti $188\n3 min\n9 min\nAceptar", "com.cabify.driver");
    DecisionEngine.Result r = new DecisionEngine.Result();
    r.payout = 188;
    r.minutes = 12;
    return st.attempt(o, r, true);
  }

  private String nextAttempt() {
    Offer o = OfferParser.parse("$240 en app\n3 min\n9 min\nAceptar", "com.cabify.driver");
    DecisionEngine.Result r = new DecisionEngine.Result();
    r.payout = 240;
    r.minutes = 12;
    return st.attempt(o, r, false);
  }

  @Test
  public void finalSummaryThenHistoryCorrectsCentsWithoutCreatingAnotherTrip() {
    String id = st.observedAssignment(null, null, false);
    assertNotNull(id);
    assertEquals(0, st.find(id).offered, .001);
    st.complete(id);
    long finishedAt = System.currentTimeMillis();
    String session = st.session().id;
    assertTrue(st.recordFinalPayment(session, finishedAt, 226, false));
    assertEquals(226, st.find(id).actual, .001);
    assertTrue(st.recordFinalPayment(null, finishedAt, 226.87, true));
    assertEquals(226.87, st.find(id).actual, .001);
    assertFalse(st.recordFinalPayment(session, finishedAt, 226, false));
    assertEquals(1, st.session().trips);
    assertEquals(226.87, st.session().money, .001);
  }

  @Test
  public void finalPaymentNeverOverwritesManualCorrectionOrManualTrip() {
    String id = st.observedAssignment(null, null, false);
    st.complete(id);
    long finishedAt = System.currentTimeMillis();
    st.correct(id, 230.50, 0, false, 0);
    assertFalse(st.recordFinalPayment(st.session().id, finishedAt, 226.87, true));
    assertEquals(230.50, st.find(id).actual, .001);
    String manual = st.manual(200, 10, "yo");
    assertFalse(st.recordFinalPayment(st.session().id, finishedAt, 200.66, true));
    assertEquals(200, st.find(manual).actual, .001);
  }

  @Test
  public void photographedOriginConfirmsAssignmentOnlyOnce() {
    String id = attempt();
    String screen = "CABIFY Ir a origen Avenida Ingeniero 1595 Navegar Ver ruta Cata";
    st.observe(screen);
    assertEquals("ASIGNADO", st.find(id).status);
    st.observe(screen);
    assertEquals(id, st.activeTrip().id);
    assertEquals(188, st.session().money, .001);
    st.observe("Viaje finalizado");
    st.observe("Viaje finalizado");
    assertEquals(188, st.session().money, .001);
    assertEquals(1, st.session().trips);
  }

  @Test
  public void attemptedNotIncome() {
    attempt();
    assertEquals(0, st.session().money, .001);
    assertEquals(0, st.session().trips);
  }

  @Test
  public void confirmedAcceptanceStartsLiveServiceAndIncomeImmediately() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    assertEquals("ASIGNADO", st.find(id).status);
    assertEquals(188, st.session().money, .001);
    ShadowSystemClock.advanceBy(Duration.ofSeconds(90));
    CocoStore.Session s = st.session();
    assertEquals(1.5, s.service, .02);
    assertEquals(0, Math.max(0, s.minutes - s.service), .02);
  }

  @Test
  public void consecutiveRideWaitsWithoutReplacingCurrentServiceClock() {
    String current = attempt();
    assertTrue(st.confirmAccepted(current));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));

    String next = nextAttempt();
    assertNotNull(next);
    assertEquals("INTENTO_SIGUIENTE", st.find(next).status);
    assertTrue(st.confirmAccepted(next));
    assertEquals("SIGUIENTE", st.find(next).status);
    assertEquals(current, st.activeTrip().id);
    assertEquals(5, st.session().service, .02);

    assertTrue(st.complete(current));
    assertEquals("FINALIZADO", st.find(current).status);
    assertEquals("ASIGNADO", st.find(next).status);
    assertEquals(next, st.activeTrip().id);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
    assertEquals(7, st.session().service, .02);
  }

  @Test
  public void duplicateAcceptEventDoesNotCreateAConsecutiveTrip() {
    String first = attempt();
    assertTrue(st.confirmAccepted(first));
    // Cabify can deliver ACTION_CLICK and an accessibility click event for one card.
    assertNull(attempt());
    assertNull(st.nextTrip());
    assertEquals(1, st.session().trips);
    String queued = nextAttempt();
    assertNotNull(queued);
    assertTrue(st.confirmAccepted(queued));
    assertEquals("SIGUIENTE", st.find(queued).status);
  }

  @Test
  public void completionAndCorrectionPreserveOriginalOfferDate() {
    String id = attempt();
    st.confirmAccepted(id);
    long offeredAt = st.find(id).at;
    ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
    assertTrue(st.complete(id));
    st.correct(id, 200, 0, false, 0);
    assertEquals(offeredAt, st.find(id).at);
    try (android.database.Cursor q = st.getReadableDatabase().rawQuery(
        "SELECT accepted_at,completed_at,corrected_at FROM trips WHERE id=?",
        new String[] {id})) {
      assertTrue(q.moveToFirst());
      assertTrue(q.getLong(0) > 0);
      assertTrue(q.getLong(1) >= q.getLong(0));
      assertTrue(q.getLong(2) >= q.getLong(1));
    }
  }

  @Test
  public void onlyOneConsecutiveRideCanBeQueued() {
    String current = attempt();
    assertTrue(st.confirmAccepted(current));
    String next = nextAttempt();
    assertNotNull(next);
    assertTrue(st.confirmAccepted(next));
    assertNull(attempt());
  }

  @Test
  public void driverCanRemoveAndRestoreDefaultTripConfirmation() {
    String id = attempt();
    st.confirmAccepted(id);
    assertEquals(188, st.session().money, .001);
    st.unconfirm(id);
    assertEquals("SIN CONFIRMAR", st.find(id).status);
    assertEquals(0, st.session().money, .001);
    st.reconfirm(id);
    assertEquals("ASIGNADO", st.find(id).status);
    assertEquals(188, st.session().money, .001);
  }

  @Test
  public void unconfirmActiveKeepsCheckpointAndReconfirmResumesFromThere() {
    String id = attempt();
    st.confirmAccepted(id);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(4));
    st.unconfirm(id);
    assertEquals("ASIGNADO", st.find(id).priorStatus);
    assertEquals(4, st.find(id).minutes, .02);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
    st.reconfirm(id);
    assertEquals("ASIGNADO", st.find(id).status);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
    assertEquals(5, st.session().service, .02);
  }

  @Test
  public void zeroMinuteFinalizedNeverReactivatesAndPromotesQueuedRide() {
    String current = attempt();
    st.confirmAccepted(current);
    String next = nextAttempt();
    st.confirmAccepted(next);
    st.complete(current);
    // A finalized zero-minute ride stays finalized even after a reversible confirmation edit.
    st.unconfirm(current);
    st.reconfirm(current);
    assertEquals("FINALIZADO", st.find(current).status);
    assertEquals(next, st.activeTrip().id);
  }

  @Test
  public void deletedManualFromVersionSevenRestoresConnectedTime() {
    st.stop();
    String id = st.manual(200, 10, "historical");
    st.delete(id);
    assertEquals(0, st.session().minutes, .001);
    SQLiteDatabase db = st.getWritableDatabase();
    db.execSQL("UPDATE trips SET connected_delta=0 WHERE id=?", new Object[] {id});
    db.setVersion(7);
    CocoStore.closeForTests();
    st = CocoStore.get(c);
    assertEquals(10, st.find(id).connectedDelta, .001);
    st.restore(id);
    assertEquals(10, st.session().minutes, .001);
  }

  @Test
  public void ocrLineOrderingIsTransitiveForStaggeredRows() {
    Rect a = new Rect(90, 0, 110, 20);
    Rect b = new Rect(10, 8, 30, 28);
    Rect c = new Rect(50, 16, 70, 36);
    assertTrue(ScreenOcrEngine.compareBounds(a, b) < 0);
    assertTrue(ScreenOcrEngine.compareBounds(b, c) < 0);
    assertTrue(ScreenOcrEngine.compareBounds(a, c) < 0);
  }

  @Test
  public void otherDriverWins() {
    String id = attempt();
    st.observe("Otro conductor aceptó el viaje");
    assertEquals("NO ASIGNADO", st.find(id).status);
    assertNull(st.activeTrip());
    assertEquals(0, st.session().money, .001);
  }

  @Test
  public void finishButtonNotCompletion() {
    String id = attempt();
    st.observe("Ir a recoger al pasajero");
    st.observe("Finalizar viaje");
    assertEquals("ASIGNADO", st.find(id).status);
    assertEquals(188, st.session().money, .001);
  }

  @Test
  public void historyNotCompletion() {
    String id = attempt();
    st.observe("He llegado");
    st.observe("Historial de viajes");
    assertEquals("ASIGNADO", st.find(id).status);
  }

  @Test
  public void completedExactlyOnce() {
    String id = attempt();
    st.observe("He llegado");
    ShadowSystemClock.advanceBy(Duration.ofMinutes(12));
    st.observe("Viaje finalizado");
    st.observe("Viaje finalizado");
    assertEquals(188, st.session().money, .001);
    assertEquals(1, st.session().trips);
    assertFalse(st.complete(id));
  }

  @Test
  public void verificationBuildsWeightedFactor() {
    String id = attempt();
    st.correct(id, 170, 0, true, 170);
    assertEquals(170.0 / 188, st.calibration("PARA_TI").factor, .000001);
    st.delete(id);
    assertEquals(0, st.calibration("PARA_TI").count);
  }

  @Test
  public void duplicateDailySnapshotReplaces() {
    st.saveBilling("2026-09-17", 11010, 60, 0, 0, 11061.89);
    st.saveBilling("2026-09-17", 11010, 60, 0, 0, 11061.89);
    try (android.database.Cursor q =
        st.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM billing", null)) {
      q.moveToFirst();
      assertEquals(1, q.getInt(0));
    }
  }

  @Test
  public void manualTripCanBeAddedWhilePausedWithoutStartingLiveService() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(30));
    st.pause();
    String id = st.manual(300, 12, "test");
    assertNotNull(id);
    assertNull(st.activeTrip());
    assertEquals(300, st.session().money, .001);
    assertEquals(1, st.session().trips);
    assertEquals(30, st.session().minutes, .001);
    assertEquals(12, st.session().service, .001);
    st.correct(id, 320, 0, false, 0, 15);
    assertNull(st.activeTrip());
    assertEquals(320, st.session().money, .001);
    assertEquals(30, st.session().minutes, .001);
    assertEquals(15, st.session().service, .001);
  }

  @Test
  public void manualTripDuringRunningSessionReclassifiesConnectedTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(25));
    String id = st.manual(240, 10, "Viaje manual", true);
    CocoStore.Session s = st.session();
    assertNotNull(id);
    assertNull(st.activeTrip());
    assertEquals("FINALIZADO", st.find(id).status);
    assertEquals(240, s.money, .001);
    assertEquals(1, s.trips);
    assertEquals(25, s.minutes, .001);
    assertEquals(10, s.service, .001);
    assertEquals(15, Math.max(0, s.minutes - s.service), .001);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    CocoStore.Session later = st.session();
    assertEquals(30, later.minutes, .001);
    assertEquals(10, later.service, .001);
  }


  @Test
  public void automaticServiceUsesRealTimeNotOfferEstimate() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    CocoStore.Session s = st.session();
    assertEquals(20, s.minutes, .01);
    assertEquals(20, s.service, .01);
    assertEquals(0, Math.max(0, s.minutes - s.service), .01);
  }

  @Test
  public void pauseFreezesConnectedAndLiveServiceTogether() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.pause();
    CocoStore.Session frozen = st.session();
    assertEquals(5, frozen.minutes, .01);
    assertEquals(5, frozen.service, .01);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    assertEquals(5, st.session().minutes, .01);
    assertEquals(5, st.session().service, .01);
    st.pause();
    ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
    CocoStore.Session resumed = st.session();
    assertEquals(15, resumed.minutes, .01);
    assertEquals(15, resumed.service, .01);
  }

  @Test
  public void correctingRunningManualTripKeepsCoveredConnectedTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(30));
    String id = st.manual(200, 8, "Viaje manual", true);
    assertEquals(30, st.session().minutes, .01);
    st.correct(id, 210, 0, false, 0, 12);
    assertEquals(30, st.session().minutes, .01);
    assertEquals(12, st.session().service, .01);
    assertNull(st.activeTrip());
  }

  @Test
  public void manualTripAtSessionStartCannotExplodeHourlyObjective() {
    ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
    String id = st.manual(200, 10, "Viaje manual", true);
    CocoStore.Session s = st.session();
    assertNotNull(id);
    assertEquals(10, s.minutes, .01);
    assertEquals(10, s.service, .01);
    assertEquals(1200, s.hourly(), .5);
    assertEquals(0, s.pending);
    assertNull(st.activeTrip());
    assertEquals("FINALIZADO", st.find(id).status);
    assertEquals(9 + 40.0 / 60, st.find(id).connectedDelta, .01);
    st.delete(id);
    assertEquals(20.0 / 60, st.session().minutes, .01);
    assertEquals(0, st.session().service, .01);
    st.restore(id);
    assertEquals(10, st.session().minutes, .01);
  }

  @Test
  public void pausedManualCorrectionAndDeletionDoNotInflateConnectedTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(30));
    st.pause();
    String id = st.manual(300, 12, "Omitido");
    st.correct(id, 320, 0, false, 0, 15);
    assertEquals(30, st.session().minutes, .01);
    assertEquals(15, st.session().service, .01);
    assertEquals(0, st.find(id).connectedDelta, .01);
    st.delete(id);
    assertEquals(30, st.session().minutes, .01);
    assertEquals(0, st.session().service, .01);
  }

  @Test
  public void correctingAssignedRideClosesSegmentAndPromotesNext() {
    String first = attempt();
    assertTrue(st.confirmAccepted(first));
    String next = nextAttempt();
    assertTrue(st.confirmAccepted(next));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
    st.correct(first, 200, 0, false, 0);
    assertEquals("FINALIZADO", st.find(first).status);
    assertEquals(3, st.find(first).minutes, .01);
    assertEquals("ASIGNADO", st.find(next).status);
    assertEquals(next, st.activeTrip().id);
  }

  @Test
  public void extraWithMinutesDoesNotCreateServiceOrConnectedTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    String id = st.manual(1300, 12, "Bono cobrado", false);
    CocoStore.Session s = st.session();
    assertNotNull(id);
    assertEquals(5, s.minutes, .01);
    assertEquals(0, s.service, .01);
    assertEquals(0, s.trips);
    assertEquals(1300, s.money, .001);
    assertNull(st.activeTrip());
  }

  @Test
  public void tripInsertRequiresExistingSessionAtDatabaseLevel() {
    android.content.ContentValues v = new android.content.ContentValues();
    v.put("id", "orphan");
    v.put("session", "missing-session");
    v.put("at", System.currentTimeMillis());
    v.put("status", "FINALIZADO");
    v.put("offered", 0);
    v.put("kind", "UNKNOWN");
    v.put("estimate", 0);
    v.put("actual", 10000);
    v.put("cost", 0);
    v.put("minutes", 10);
    try {
      st.getWritableDatabase().insertOrThrow("trips", null, v);
      fail("No debe existir un viaje sin sesión");
    } catch (android.database.SQLException expected) {
      assertTrue(expected.getMessage() == null || expected.getMessage().contains("session"));
    }
  }

  @Test
  public void manualTripCanTargetPreviousClosedSession() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    st.stop();
    String old = st.session().id;
    String id = st.manualForSession(old, 350, 8, "Viaje omitido", true);
    assertNotNull(id);
    assertFalse(st.session().active);
    assertEquals(28, st.session().minutes, .001);
    assertEquals(8, st.session().service, .001);
    assertEquals(350, st.session().money, .001);
  }

  @Test
  public void observedCabifyAssignmentWithoutCapturedFareStartsLiveService() {
    String id = st.observedAssignment(null, null, false);
    assertNotNull(id);
    assertEquals("ASIGNADO", st.find(id).status);
    assertFalse(st.find(id).manual);
    assertEquals(0, st.session().money, .001);
    ShadowSystemClock.advanceBy(Duration.ofSeconds(90));
    assertEquals(1.5, st.session().service, .01);
  }

  @Test
  public void manualOriginSurvivesCorrectionAndDoesNotChangeObservedZeroFare() {
    String manual = st.manual(398.97, 10, "Viaje manual");
    assertTrue(st.find(manual).manual);
    st.correct(manual, 400, 12, false, 0);
    assertTrue(st.find(manual).manual);
    String observed = st.observedAssignment(null, null, false);
    assertFalse(st.find(observed).manual);
  }

  @Test
  public void monthlyObservationsFeedAdvisorOnlyAfterThreeSamples() throws Exception {
    String place = "Zona " + java.util.UUID.randomUUID().toString().substring(0, 8);
    DecisionEngine.Result result = new DecisionEngine.Result();
    result.hourly = 840;
    for (int i = 0; i < 3; i++) {
      Offer o = new Offer();
      o.hasOfferSignal = true;
      o.fare = 210 + i;
      o.pickupMin = 3;
      o.tripMin = 12;
      o.pickupArea = place;
      o.destinationArea = "Centro";
      o.key = "sample-" + i + "-" + place;
      CocoDataStore.offer(c, st.session().id, o, result, true);
    }
    CocoDataStore.awaitWritesForTests();
    assertTrue(CocoDataStore.currentFile(c, System.currentTimeMillis()).isFile());
    assertTrue(CocoDataStore.currentDatabase(c, System.currentTimeMillis()).isFile());
    assertTrue(CocoDataStore.forecasts(c, System.currentTimeMillis()).stream()
        .anyMatch(f -> f.place.equals(OfferParser.normalize(place))
            && f.samples >= 3 && Math.abs(f.medianHourly - 840) < .01));
  }

  @Test
  public void extraDoesNotInventTrip() {
    st.manual(1300, 0, "Bono cobrado", false);
    assertEquals(1300, st.session().money, .001);
    assertEquals(0, st.session().trips);
  }

  @Test
  public void restartPausesUnobservedTime() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
    st.session();
    int b =
        android.provider.Settings.Global.getInt(
            c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 0);
    android.provider.Settings.Global.putInt(
        c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, b + 1);
    assertTrue(st.session().paused);
    assertEquals(1, st.session().minutes, .001);
  }

  @Test
  public void unfinishedRideCannotBlockNextSession() {
    String old = attempt();
    st.observe("He llegado");
    st.stop();
    st.start();
    assertNull(st.activeTrip());
    assertEquals("SIN CONFIRMAR", st.find(old).status);
    assertNotNull(attempt());
    assertEquals(0, st.session().money, .001);
  }

  @Test
  public void expiringAttemptInvalidatesRevisionAndRemovesActiveAttempt() {
    String id = attempt();
    assertNotNull(id);
    // Age the persisted wall-clock timestamp directly. Robolectric's monotonic
    // ShadowSystemClock is not a reliable proxy for System.currentTimeMillis().
    st.getWritableDatabase()
        .execSQL(
            "UPDATE trips SET at=? WHERE id=?",
            new Object[] {System.currentTimeMillis() - 46000L, id});
    long before = st.revision();
    assertNull(st.activeTrip());
    assertEquals("SIN CONFIRMAR", st.find(id).status);
    assertTrue(st.revision() > before);
  }

  @Test
  public void oldSessionQueuedRideIsNotPromotedByCurrentSessionCompletion() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(1));
    st.stop();
    String oldSession = st.session().id;
    android.content.ContentValues old = new android.content.ContentValues();
    old.put("id", "old-next");
    old.put("session", oldSession);
    old.put("at", System.currentTimeMillis());
    old.put("status", "SIGUIENTE");
    old.put("offered", 200);
    old.put("kind", "EN_APP");
    old.put("estimate", 20000);
    old.put("cost", 0);
    old.put("minutes", 0);
    old.put("trip", 1);
    st.getWritableDatabase().insertOrThrow("trips", null, old);

    st.start();
    String current = attempt();
    assertTrue(st.confirmAccepted(current));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
    assertTrue(st.complete(current));
    assertEquals("SIGUIENTE", st.find("old-next").status);
    assertNull(st.activeTrip());
  }

  @Test
  public void rebootPreservesActiveRideUntilCabifyReconcilesIt() {
    String old = attempt();
    st.observe("He llegado");
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.session();
    android.provider.Settings.Global.putInt(
        c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 1234);
    assertTrue(st.session().paused);
    assertEquals("ASIGNADO", st.find(old).status);
    assertNotNull(st.activeTrip());
    assertTrue(st.recoverFromCabify("Viaje en curso Finalizar viaje"));
    assertFalse(st.session().paused);
    assertEquals("ASIGNADO", st.find(old).status);
    assertEquals(5, st.session().service, .05);
  }

  @Test
  public void rebootWithoutActiveRideResumesSessionAfterStoreOpens() {
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.session();
    android.provider.Settings.Global.putInt(
        c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 2987);
    CocoStore.closeForTests();
    st = CocoStore.get(c);
    assertTrue(st.session().active);
    assertFalse(st.session().paused);
    assertFalse(st.session().recovering);
    assertEquals(5, st.session().minutes, .05);
  }

  @Test
  public void sameBootProcessRecoveryDoesNotCountDeadTimeAsService() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.session(); // persists both session and active-service checkpoints
    assertTrue(st.beginRecovery());
    assertTrue(st.session().recovering);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    assertTrue(st.recoverFromCabify("Viaje en curso Finalizar viaje"));
    CocoStore.Session recovered = st.session();
    assertFalse(recovered.recovering);
    assertEquals(5, recovered.service, .05);
    assertEquals("ASIGNADO", st.find(id).status);
  }

  @Test
  public void firstStoreOpenAfterProcessDeathFreezesRideBeforeSessionRead() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.session();
    CocoStore.closeForTests();
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));

    st = CocoStore.get(c); // first access in the simulated fresh process
    CocoStore.Session frozen = st.session();
    assertTrue(frozen.paused);
    assertTrue(frozen.recovering);
    assertEquals(5, frozen.service, .05);
    assertTrue(st.recoverFromCabify("Viaje en curso Finalizar viaje"));
    assertEquals(5, st.session().service, .05);
  }

  @Test
  public void rebootRecoveryDoesNotCountDeviceDowntimeAsService() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(5));
    st.session();
    android.provider.Settings.Global.putInt(
        c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 1777);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(20));
    CocoStore.Session booted = st.session();
    assertTrue(booted.paused);
    assertTrue(booted.recovering);
    assertTrue(st.recoverFromCabify("Viaje en curso Finalizar viaje"));
    CocoStore.Session recovered = st.session();
    assertEquals(5, recovered.service, .05);
    assertEquals("ASIGNADO", st.find(id).status);
  }

  @Test
  public void manualPauseIsNotMistakenForCrashRecovery() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
    st.pause();
    assertTrue(st.session().paused);
    assertFalse(st.session().recovering);
    assertFalse(st.beginRecovery());
    assertTrue(st.session().paused);
  }

  @Test
  public void stopPreservesActiveServiceBeforeInvalidatingTrip() {
    String id = attempt();
    assertTrue(st.confirmAccepted(id));
    ShadowSystemClock.advanceBy(Duration.ofMinutes(7));
    st.stop();
    CocoStore.Trip trip = st.find(id);
    assertEquals("SIN CONFIRMAR", trip.status);
    assertEquals(7, trip.minutes, .05);
  }

  @Test
  public void rebootReturningToIdleMapDoesNotCreatePhantomService() {
    String old = attempt();
    st.observe("He llegado");
    android.provider.Settings.Global.putInt(
        c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, 2222);
    assertTrue(st.session().paused);
    assertTrue(st.recoverFromCabify("Cabify Estas conectado Buscando viajes"));
    assertFalse(st.session().paused);
    assertEquals("SIN CONFIRMAR", st.find(old).status);
    assertNull(st.activeTrip());
  }

  @Test
  public void cancellationStopsServiceWithoutEarningTrip() {
    String id = attempt();
    st.confirmAccepted(id);
    ShadowSystemClock.advanceBy(Duration.ofMinutes(3));
    st.observe("El pasajero cancelo el viaje");
    assertEquals("CANCELADO", st.find(id).status);
    assertNull(st.activeTrip());
    assertEquals(0, st.session().money, .001);
    assertEquals(0, st.session().trips);
  }

  private String closedDay(int ago) {
    return java.time.LocalDate.now(CocoStore.ZONE).minusDays(ago).toString();
  }

  @Test
  public void dailySeedPredictsWithoutIndividualPaymentPairs() {
    CocoStore.Calibration k = st.dailyCalibration();
    assertTrue(k.provisional);
    assertTrue(k.usable());
    assertEquals(1.0, k.factor, .000001);
    SettingsStore.Values v = new SettingsStore.Values();
    v.payoutMode = 3;
    v.operationalGoalHour = 700;
    v.realGoalHour = 700;
    Offer o = OfferParser.parse("Aceptar\n$200 en app\n3 min\n9 min", "com.cabify.driver");
    DecisionEngine.Result r =
        DecisionEngine.evaluate(o, v, st.session(), 2, st.calibrationForOffer(o.kind, v));
    assertEquals(200, r.payout, .001);
    assertEquals(200 * 60 / r.minutes, r.hourly, .001);
    assertTrue(r.accept);
    assertTrue(r.provisional);
  }

  @Test
  public void completeDailyTotalsLearnWeightedFactorAndExcludeTipsBonusesTolls() {
    st.saveBilling(closedDay(1), 1100, 100, 50, 50, 1000, true);
    st.saveBilling(closedDay(2), 2500, 100, 0, 0, 3000, true);
    CocoStore.Calibration k = st.dailyCalibration();
    assertEquals(2, k.count);
    assertEquals(.825, k.factor, .000001);
    assertFalse(k.provisional);
    assertEquals(0, st.session().money, .001);
  }

  @Test
  public void partialAndOldClosuresCannotTrain() {
    st.saveBilling(closedDay(1), 2833, 0, 0, 0, 2471.59, false);
    st.saveBilling(closedDay(30), 100, 0, 0, 0, 200, true);
    assertTrue(st.dailyCalibration().provisional);
    assertEquals(1.0, st.dailyCalibration().factor, .000001);
  }

  @Test
  public void correctingClosureReplacesAndCanWithdrawTraining() {
    st.saveBilling(closedDay(1), 900, 0, 0, 0, 1000, true);
    st.saveBilling(closedDay(1), 800, 0, 0, 0, 1000, true);
    assertEquals(1, st.dailyCalibration().count);
    assertEquals(.8, st.dailyCalibration().factor, .000001);
    st.saveBilling(closedDay(1), 800, 0, 0, 0, 1000, false);
    assertTrue(st.dailyCalibration().provisional);
  }

  @Test
  public void todayCannotBeUsedAsClosedDay() {
    assertThrows(
        IllegalArgumentException.class,
        () -> st.saveBilling(closedDay(0), 100, 0, 0, 0, 100, true));
    st.saveBilling(closedDay(0), 100, 0, 0, 0, 100, false);
    assertTrue(st.dailyCalibration().provisional);
  }

  @Test
  public void migrationPreservesSessionsAndDoesNotTrustOldBillingAutomatically() {
    st.manual(200, 10, "antes");
    st.saveBilling(closedDay(1), 900, 0, 0, 0, 1000);
    android.database.sqlite.SQLiteDatabase db = st.getWritableDatabase();
    db.execSQL("ALTER TABLE billing RENAME TO billing_v2");
    db.execSQL(
        "CREATE TABLE billing(day TEXT PRIMARY KEY,total REAL,tips REAL,bonus REAL,tolls"
            + " REAL,history REAL)");
    db.execSQL("INSERT INTO billing SELECT day,total,tips,bonus,tolls,history FROM billing_v2");
    db.execSQL("DROP TABLE billing_v2");
    db.setVersion(1);
    CocoStore.closeForTests();
    st = CocoStore.get(c);
    assertEquals(200, st.session().money, .001);
    assertEquals(10, st.getReadableDatabase().getVersion());
    assertTrue(st.dailyCalibration().provisional);
  }

  @Test
  public void ayeryhoyFaresAccumulateUsingSameEngineAsLiveOffers() {
    double[] amounts = {
      181.36, 280.42, 316.01, 0, 300.77, 0, 216.2, 273.53, 210.64, 206.99, 213.22, 267.04, 348.63,
      181.36, 485.74, 272.99, 415.57, 228.39, 209.48, 183.84, 216.05, 188.66, 257.44, 0, 220.22,
      178.2, 224.47, 238.24, 178.2, 357.09, 444.91, 270.23, 255.93, 293.42, 369.91, 197.13, 240.04,
      245.81, 216.21, 178.2, 361.91, 311.99, 0, 232.42, 351.75, 241.28
    };
    SettingsStore.Values v = new SettingsStore.Values();
    v.payoutMode = 3;
    v.operationalGoalHour = 700;
    v.realGoalHour = 700;
    long sum = 0;
    for (double fare : amounts) {
      if (fare == 0) continue;
      Offer o =
          OfferParser.parse("Aceptar\n$" + fare + " en app\n3 min\n9 min", "com.cabify.driver");
      // Times only make a complete synthetic offer; this asserts payout, not historical
      // profitability.
      sum +=
          Money.cents(DecisionEngine.evaluate(o, v, st.session(), 2, st.dailyCalibration()).payout);
    }
    assertEquals(1106189, sum);
  }
}
