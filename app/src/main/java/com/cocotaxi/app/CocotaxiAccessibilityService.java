package com.cocotaxi.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.Display;
import android.view.accessibility.*;
import java.util.*;
import java.util.concurrent.*;

public final class CocotaxiAccessibilityService extends AccessibilityService {
  public static final String CABIFY = "com.cabify.driver";
  public static CocotaxiAccessibilityService instance;
  public static String diagnostic = "Activa Accesibilidad para leer Cabify";
  public static String autoAcceptStatus = "Sin oferta evaluada";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private ScreenOcrEngine ocr;
  private final MapDemandVision zoneVision = new MapDemandVision();
  private volatile boolean processing;
  private volatile boolean busy, destroyed;
  private final Runnable stableRetry = () -> { if (!destroyed) safeInspect(null); };
  private long inspectStartedNs, lastDispatchNs;
  private long ocrAt, stableAt, clickedAt;
  private String stableKey = "";
  private final java.util.Map<String, Long> attemptedKeys = new java.util.HashMap<>();
  private List<Offer> previous = new ArrayList<>();
  private long previousAt;
  private Offer recentDetail;
  private Offer recentOcrOffer;
  private Offer lastUnambiguousOffer;
  private long recentOcrAt, lastUnambiguousAt;
  private String detailSession = "", lastOfferSession = "";
  private long detailAt;
  private long assignmentWithoutOfferAt;
  private long lastMapCaptureAt, lastJourneyEvidenceAt, passiveFinishMapSeenAt, finishClickedAt;
  private long finishActionVisibleAt;
  private String passiveFinishTripId = "", finishActionTripId = "";
  private long lastPassiveOfferHash = Long.MIN_VALUE, lastPassiveCenterHash = Long.MIN_VALUE;

  // Short-lived hot-path caches. Store revision invalidates them immediately after any session/trip mutation.
  private CocoStore.Session cachedSession;
  private CocoStore.Trip cachedTrip;
  private long cachedSessionAt, cachedTripAt, cachedRevision = -1L, cachedTripRevision = -1L;
  private PromotionStore.Snapshot cachedPromo;
  private long cachedPromoAt, cachedPromoRevision = -1L;
  private final ExecutorService screenshotExecutor =
      Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CocoScreenshot");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
      });

  private static final int CLICK_NONE = 0;
  private static final int CLICK_SUCCESS = 1;
  private static final int CLICK_FAILURE = -1;

  private void rememberDetail(List<Offer> cards, boolean individual) {
    // Never replace the last verified single offer with a list. Keeping the last detail briefly
    // lets us associate a very fast screen transition with the amount the user just accepted.
    if (!individual || cards.size() != 1) return;
    recentDetail = cards.get(0);
    detailAt = SystemClock.elapsedRealtime();
    detailSession = cachedSession != null ? cachedSession.id : CocoStore.get(this).session().id;
    cacheUnambiguous(recentDetail);
  }

  private void cacheUnambiguous(Offer offer) {
    if (offer == null || !offer.hasEnoughForDecision()) return;
    lastUnambiguousOffer = offer;
    lastUnambiguousAt = SystemClock.elapsedRealtime();
    lastOfferSession = cachedSession != null ? cachedSession.id : CocoStore.get(this).session().id;
  }

  private void observeAssignment(
      String normalized, boolean assigned, CocoStore store, CocoStore.Session session, CocoStore.Trip trip) {
    if (assigned && trip == null) {
      long now = SystemClock.elapsedRealtime();
      Offer candidate = null;
      if (recentDetail != null && session.id.equals(detailSession) && now - detailAt <= 15000)
        candidate = recentDetail;
      else if (recentOcrOffer != null && session.id.equals(lastOfferSession) && now - recentOcrAt <= 15000)
        candidate = recentOcrOffer;
      else if (lastUnambiguousOffer != null
          && session.id.equals(lastOfferSession)
          && now - lastUnambiguousAt <= 15000)
        candidate = lastUnambiguousOffer;
      else if (previous.size() == 1 && now - previousAt <= 15000) candidate = previous.get(0);
      if (candidate != null && recentOcrOffer != null
          && session.id.equals(lastOfferSession) && now - recentOcrAt <= 15000
          && OfferParser.sameOffer(candidate, recentOcrOffer))
        candidate = OfferParser.merge(candidate, recentOcrOffer);

      if (trip == null) {
        // Give an already-running OCR frame a short chance to publish the fare before creating a
        // zero-value assignment. This removes the common race where Cabify changes to Ir a origen
        // a few milliseconds before Coco receives the final offer text.
        if (candidate == null) {
          if (assignmentWithoutOfferAt == 0L) assignmentWithoutOfferAt = now;
          if (now - assignmentWithoutOfferAt < 1500L) {
            diagnostic = "Viaje aceptado detectado · confirmando importe";
            return;
          }
        }
        DecisionEngine.Result r = null;
        if (candidate != null) {
          annotateOffer(candidate);
          SettingsStore.Values v = SettingsStore.load(this);
          int demand = demand(v);
          PromotionStore.Snapshot promo = promotionSnapshot(store, session);
          CocoStore.Calibration learned = calibration(store, candidate.kind, v);
          r = DecisionEngine.evaluate(candidate, v, session, demand, learned, promo);
        }
        String id = store.observedAssignment(candidate, r, false);
        if (id != null) {
          diagnostic =
              candidate == null
                  ? "Viaje aceptado detectado · importe pendiente"
                  : "Viaje aceptado detectado · en servicio";
          cachedSessionAt = cachedTripAt = 0L;
        }
      }
      assignmentWithoutOfferAt = 0L;
      recentDetail = null;
      recentOcrOffer = null;
      lastUnambiguousOffer = null;
    } else if (!assigned || trip != null) {
      assignmentWithoutOfferAt = 0L;
    }

    // Avoid the old session + active-trip database round-trip on every 150 ms map poll.
    if (trip != null || assigned) store.observeNormalized(normalized);
  }

  private int mapSignal = -1;
  private long mapAt;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (destroyed) return;
          CocoStore store = CocoStore.get(CocotaxiAccessibilityService.this);
          CocoStore.Session session = sessionSnapshot(store, false);
          if (session.active) safeInspect(null);
          long delay;
          if (!session.active) delay = 5000L;
          else if (session.paused) delay = 3000L;
          else delay = 1200L;
          handler.postDelayed(this, delay);
        }
      };

  @Override
  protected void onServiceConnected() {
    instance = this;
    boolean recovering = CocoStore.get(this).beginRecovery();
    handler.post(poll);
    diagnostic = recovering ? "INICIALIZANDO · recuperando viaje" : "Esperando señal de Cabify";
  }

  @Override
  public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event == null) return;
    CharSequence pkg = event.getPackageName();
    String packageName = pkg == null ? "" : pkg.toString();
    if (!CABIFY.equals(packageName) && !packageName.contains("systemui")) return;
    if (CABIFY.equals(packageName)
        && (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED
            || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || event.getEventType() == AccessibilityEvent.TYPE_VIEW_SELECTED)) {
      if (!finishInteraction(event) && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED)
        manualClick(event);
    }
    safeInspect(event);
  }

  private void safeInspect(AccessibilityEvent event) {
    try {
      inspect(event);
    } catch (RuntimeException e) {
      diagnostic = "Coco se recuperó de un error de lectura";
      android.util.Log.e("COCO_ACCESS", "inspect", e);
    }
  }

  private boolean finishInteraction(AccessibilityEvent e) {
    if (!CABIFY.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return false;
    AccessibilityNodeInfo n = e.getSource();
    if (n == null) return false;
    StringBuilder seen = new StringBuilder();
    try {
      if (e.getText() != null)
        for (CharSequence value : e.getText())
          if (value != null) seen.append(' ').append(OfferParser.normalize(value.toString()));
      for (int i = 0; i < 5 && n != null; i++) {
        if (n.getText() != null)
          seen.append(' ').append(OfferParser.normalize(n.getText().toString()));
        if (n.getContentDescription() != null)
          seen.append(' ').append(OfferParser.normalize(n.getContentDescription().toString()));
        // Some Cabify versions put the action label on the clickable parent rather than the leaf.
        // Read only each ancestor's own label; never its whole subtree, otherwise tapping a map
        // control could accidentally see a sibling "Finalizar" button and close the trip.
        AccessibilityNodeInfo parent = n.getParent();
        n.recycle();
        n = parent;
      }
      String text = OfferParser.normalize(seen.toString());
      boolean finish = AssignmentSignals.looksFinishAction(text);
      if (!finish) return false;
      finishClickedAt = SystemClock.elapsedRealtime();
      CocoStore store = CocoStore.get(this);
      CocoStore.Trip trip = store.activeTrip();
      if (trip != null && "ASIGNADO".equals(trip.status) && store.complete(trip.id)) {
        diagnostic = "Viaje finalizado · servicio detenido";
        CocotaxiOverlayService.clearOffer();
      }
      return true;
    } finally {
      if (n != null) n.recycle();
    }
  }

  private void manualClick(AccessibilityEvent e) {
    if (!CABIFY.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())
        || SystemClock.elapsedRealtime() - clickedAt < 1500) return;
    AccessibilityNodeInfo n = e.getSource();
    if (n == null) return;
    try {
      String label =
          OfferParser.normalize(
                  String.valueOf(n.getText() == null ? n.getContentDescription() : n.getText()))
              .trim();
      if (!label.startsWith("aceptar")) return;
      for (int i = 0; i < 8 && n != null; i++) {
        Offer o = OfferParser.parse(TextCollector.collect(n), CABIFY);
        annotateOffer(o);
        if (o.hasEnoughForDecision()) {
          SettingsStore.Values v = SettingsStore.load(this);
          CocoStore store = CocoStore.get(this);
          DecisionEngine.Result r =
              DecisionEngine.evaluate(
                  o, v, store.session(), demand(v), store.calibrationForOffer(o.kind, v), PromotionStore.get(this));
          String id = store.attempt(o, r, false);
          if (id != null) {
            store.confirmAccepted(id);
            CocoStore.Trip accepted = store.find(id);
            boolean queued = accepted != null && "SIGUIENTE".equals(accepted.status);
            diagnostic = queued ? "Siguiente viaje confirmado" : "Viaje confirmado · en servicio";
          }
          return;
        }
        AccessibilityNodeInfo parent = n.getParent();
        n.recycle();
        n = parent;
      }

      // Very fast Cabify transitions may remove the fare from Accessibility before the click
      // event reaches us. Reuse only a recent unambiguous single offer, never a list.
      long now = SystemClock.elapsedRealtime();
      Offer candidate = null;
      CocoStore.Session session = CocoStore.get(this).session();
      if (recentDetail != null && session.id.equals(detailSession) && now - detailAt <= 30000)
        candidate = recentDetail;
      else if (recentOcrOffer != null && session.id.equals(lastOfferSession) && now - recentOcrAt <= 15000)
        candidate = recentOcrOffer;
      else if (lastUnambiguousOffer != null
          && session.id.equals(lastOfferSession)
          && now - lastUnambiguousAt <= 15000) candidate = lastUnambiguousOffer;
      else if (previous.size() == 1 && now - previousAt <= 15000) candidate = previous.get(0);
      if (candidate != null && recentOcrOffer != null
          && session.id.equals(lastOfferSession) && now - recentOcrAt <= 15000
          && OfferParser.sameOffer(candidate, recentOcrOffer))
        candidate = OfferParser.merge(candidate, recentOcrOffer);
      if (candidate != null && candidate.hasEnoughForDecision()) {
        annotateOffer(candidate);
        SettingsStore.Values v = SettingsStore.load(this);
        CocoStore store = CocoStore.get(this);
        DecisionEngine.Result r =
            DecisionEngine.evaluate(
                candidate,
                v,
                session,
                demand(v),
                store.calibrationForOffer(candidate.kind, v),
                PromotionStore.get(this));
        String id = store.attempt(candidate, r, false);
        if (id != null) {
          store.confirmAccepted(id);
          CocoStore.Trip accepted = store.find(id);
          boolean queued = accepted != null && "SIGUIENTE".equals(accepted.status);
          diagnostic = queued ? "Siguiente viaje confirmado" : "Viaje confirmado · en servicio";
        }
      }
    } finally {
      if (n != null) n.recycle();
    }
  }

  private boolean observeTripTerminal(
      String normalized, boolean assigned, CocoStore store, CocoStore.Session session, CocoStore.Trip trip) {
    if (trip == null || !"ASIGNADO".equals(trip.status)) {
      passiveFinishMapSeenAt = 0L;
      passiveFinishTripId = "";
      finishActionVisibleAt = 0L;
      finishActionTripId = "";
      return false;
    }
    if (AssignmentSignals.isCancelledNormalized(normalized)) {
      store.cancelObserved(trip.id);
      diagnostic = "Viaje cancelado · reloj detenido";
      passiveFinishMapSeenAt = 0L;
      return true;
    }
    long now = SystemClock.elapsedRealtime();
    // With a consecutive ride Cabify can jump directly from Finalizar to the next pickup screen,
    // without ever showing the idle map. That transition finalizes the old ride and promotes the
    // already accepted SIGUIENTE record.
    boolean possibleQueuedPickup =
        assigned
            && trip.id.equals(finishActionTripId)
            && finishActionVisibleAt > 0
            && now - finishActionVisibleAt <= 30000L
            && AssignmentSignals.isPickupStageNormalized(normalized);
    CocoStore.Trip queued = possibleQueuedPickup ? store.nextTrip() : null;
    boolean movedToQueuedPickup =
        possibleQueuedPickup && queued != null && "SIGUIENTE".equals(queued.status);
    if (movedToQueuedPickup) {
      store.complete(trip.id);
      diagnostic = "Viaje finalizado · siguiente viaje activo";
      passiveFinishMapSeenAt = 0L;
      return true;
    }
    if (assigned) {
      lastJourneyEvidenceAt = now;
      passiveFinishTripId = trip.id;
      if (AssignmentSignals.looksFinishAction(normalized)) {
        finishActionVisibleAt = now;
        finishActionTripId = trip.id;
      }
      passiveFinishMapSeenAt = 0L;
      return false;
    }
    if (AssignmentSignals.isCompletedNormalized(normalized)) {
      store.complete(trip.id);
      diagnostic = "Viaje finalizado · servicio detenido";
      passiveFinishMapSeenAt = 0L;
      return true;
    }
    boolean offerVisible = OfferParser.isLikelyOfferNormalized(normalized);
    boolean strongIdle = AssignmentSignals.isStrongIdleMainMapNormalized(normalized) && !offerVisible;
    boolean armed = trip.id.equals(passiveFinishTripId)
        && lastJourneyEvidenceAt > 0 && now - lastJourneyEvidenceAt <= 90000L;
    armed = armed || (finishClickedAt > 0 && now - finishClickedAt <= 45000L);
    boolean leftFinalStage = trip.id.equals(finishActionTripId)
        && finishActionVisibleAt > 0 && now - finishActionVisibleAt <= 120000L;
    // A stable return to Cabify's real searching map is terminal evidence by itself. Real rides can
    // last far longer than 90 seconds and Cabify often stops exposing journey nodes before Coco
    // sees the final swipe/click. Do not require a recent journey node in this case.
    boolean terminalCandidate = strongIdle || (leftFinalStage && !offerVisible && !normalized.isEmpty());
    boolean allowed = strongIdle || armed || (session.paused && trip.accepted <= 0);
    if (terminalCandidate && allowed) {
      if (passiveFinishMapSeenAt == 0L) passiveFinishMapSeenAt = now;
      long required = strongIdle ? 900L : 1800L;
      if (now - passiveFinishMapSeenAt >= required) {
        store.complete(trip.id);
        diagnostic = "Fin confirmado al volver al mapa · servicio detenido";
        passiveFinishMapSeenAt = 0L;
        cachedSessionAt = cachedTripAt = 0L;
        return true;
      }
    } else passiveFinishMapSeenAt = 0L;
    return false;
  }

  private int demand(SettingsStore.Values v) {
    return DemandModel.level(v, System.currentTimeMillis(), mapSignal, mapAt);
  }

  private CocoStore.Session sessionSnapshot(CocoStore store, boolean forDecision) {
    long now = SystemClock.elapsedRealtime(), revision = store.revision();
    long maxAge = forDecision ? 200L : 750L;
    if (cachedSession == null || cachedRevision != revision || now - cachedSessionAt >= maxAge) {
      cachedSession = store.session();
      cachedSessionAt = now;
      cachedRevision = store.revision();
    }
    return cachedSession;
  }

  private CocoStore.Trip tripSnapshot(CocoStore store, CocoStore.Session session) {
    long now = SystemClock.elapsedRealtime(), revision = store.revision();
    if (cachedTripRevision != revision || now - cachedTripAt >= 750L) {
      cachedTrip = store.activeTrip();
      cachedTripAt = now;
      cachedTripRevision = store.revision();
      // activeTrip() can expire a stale INTENTO; refresh the session revision token if needed.
      if (cachedRevision != store.revision()) cachedSessionAt = 0;
    }
    return cachedTrip;
  }

  private PromotionStore.Snapshot promotionSnapshot(CocoStore store, CocoStore.Session session) {
    long now = SystemClock.elapsedRealtime(), revision = store.revision();
    if (cachedPromo == null || cachedPromoRevision != revision || now - cachedPromoAt >= 1500L) {
      cachedPromo = PromotionStore.get(this, session);
      cachedPromoAt = now;
      cachedPromoRevision = store.revision();
    }
    return cachedPromo;
  }

  private CocoStore.Calibration calibration(CocoStore store, String kind, SettingsStore.Values v) {
    if (v.payoutMode == 3) return store.dailyCalibration();
    if (v.payoutMode == 2) return store.calibration(kind);
    return null;
  }

  /**
   * Returns the Cabify interactive window even when another app is in the foreground. Cabify may
   * publish ride offers as an overlay/window while the driver is using Maps or another app, so
   * getRootInActiveWindow() alone is not enough. A SystemUI window is accepted only when its text
   * explicitly identifies Cabify and contains a real offer/assignment signal.
   */
  private AccessibilityNodeInfo cabifyRoot() {
    AccessibilityNodeInfo active = getRootInActiveWindow();
    if (active != null) {
      CharSequence pkg = active.getPackageName();
      if (CABIFY.contentEquals(pkg == null ? "" : pkg)) return active;
    }

    List<AccessibilityWindowInfo> windows = getWindows();
    if (windows != null) {
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window == null ? null : window.getRoot();
        if (root == null) continue;
        CharSequence pkg = root.getPackageName();
        if (CABIFY.contentEquals(pkg == null ? "" : pkg)) {
          if (active != null) active.recycle();
          return root;
        }
        root.recycle();
      }

      // Some Android/Cabify combinations expose a heads-up overlay through SystemUI rather than
      // com.cabify.driver. Inspect only those windows, and only if the content is unmistakably a
      // Cabify ride offer or assigned-trip screen.
      for (AccessibilityWindowInfo window : windows) {
        AccessibilityNodeInfo root = window == null ? null : window.getRoot();
        if (root == null) continue;
        CharSequence pkg = root.getPackageName();
        String packageName = pkg == null ? "" : pkg.toString();
        if (packageName.contains("systemui")) {
          String normalized = OfferParser.normalize(TextCollector.collect(root));
          if (normalized.contains("cabify")
              && (OfferParser.isLikelyOfferNormalized(normalized)
                  || AssignmentSignals.isAssignedNormalized(normalized))) {
            if (active != null) active.recycle();
            return root;
          }
        }
        root.recycle();
      }
    }
    if (active != null) active.recycle();
    return null;
  }

  public boolean cabifyForeground() {
    AccessibilityNodeInfo root = cabifyRoot();
    if (root == null) return false;
    root.recycle();
    return true;
  }

  private void inspect(AccessibilityEvent event) {
    inspectStartedNs = SystemClock.elapsedRealtimeNanos();
    AccessibilityNodeInfo root = cabifyRoot();
    if (root == null) {
      CocotaxiOverlayService.clearOffer();
      stableKey = "";
      return;
    }
    try {

      CocoStore store = CocoStore.get(this);
      CocoStore.Session session = sessionSnapshot(store, false);
      if (!session.active) {
        CocotaxiOverlayService.clearOffer();
        stableKey = "";
        stableAt = 0L;
        return;
      }

      try (TextCollector.Snapshot tree = TextCollector.snapshot(root)) {
        String text = tree.text;
        String normalized = OfferParser.normalize(text);
        boolean assigned = AssignmentSignals.isAssignedNormalized(normalized);
        CocoStore.Trip trip = tripSnapshot(store, session);

        // INICIALIZANDO after a reboot: preserve the pending ride until Cabify itself tells us
        // whether we are still serving it or have returned to the search map.
        if (session.recovering && store.recoverFromCabify(normalized)) {
          cachedSessionAt = cachedTripAt = 0L;
          session = sessionSnapshot(store, false);
          trip = tripSnapshot(store, session);
          diagnostic =
              trip != null && "ASIGNADO".equals(trip.status)
                  ? "Recuperado · viaje en servicio"
                  : "Recuperado · estado de Cabify reconciliado";
        }

        if (observeTripTerminal(normalized, assigned, store, session, trip)) {
          CocotaxiOverlayService.clearOffer();
          cachedSessionAt = cachedTripAt = 0L;
          return;
        }
        if (session.paused) {
          CocotaxiOverlayService.clearOffer();
          return;
        }
        observeAssignment(normalized, assigned, store, session, trip);

        if (assigned || OfferParser.isHistoryNormalized(normalized)) {
          CocotaxiOverlayService.clearOffer();
          return;
        }

        int signal = DemandModel.textSignalNormalized(normalized);
        if (signal >= 0) {
          mapSignal = signal;
          mapAt = System.currentTimeMillis();
        }

        boolean list = tree.isList(normalized);
        boolean hasAccept = tree.hasAccept();

        // Fastest path: one tree scan + one parse + one decision. No UI work occurs before the click.
        if (!list && hasAccept) {
          Offer direct = OfferParser.parseNormalized(text, normalized, CABIFY);
          boolean fused = false;
          if ((!direct.hasEnoughForDecision() || !direct.fareFractionVisible)
              && recentOcrOffer != null
              && SystemClock.elapsedRealtime() - recentOcrAt <= 5000
              && OfferParser.sameOffer(direct, recentOcrOffer)) {
            Offer merged = OfferParser.merge(direct, recentOcrOffer);
            if (merged != null && merged.hasEnoughForDecision()) {
              direct = merged;
              fused = true;
            }
          }
          annotateOffer(direct);
          if (direct.hasEnoughForDecision()) {
            direct.top = tree.acceptBounds.top;
            direct.bottom = tree.acceptBounds.bottom;
            List<Offer> single = Collections.singletonList(direct);
            rememberDetail(single, true);
            previous = single;
            previousAt = SystemClock.elapsedRealtime();
            handleOffers(root, tree, single, fused, false, true, session, fused);
            if (!direct.fareFractionVisible && Build.VERSION.SDK_INT >= 30)
              requestScreenshot(root.getWindowId(), true);
            if ((direct.pickupArea.isEmpty() || direct.destinationArea.isEmpty())
                && Build.VERSION.SDK_INT >= 30) requestScreenshot(root.getWindowId());
            diagnostic = fused ? "Oferta confirmada por Accessibility + OCR" : "Oferta detectada al instante";
            return;
          }
          if (recentOcrOffer != null && SystemClock.elapsedRealtime() - recentOcrAt <= 5000
              && OfferParser.sameOffer(direct, recentOcrOffer)) {
            annotateOffer(recentOcrOffer);
            List<Offer> single = Collections.singletonList(recentOcrOffer);
            rememberDetail(single, true);
            handleOffers(root, tree, single, true, false, true, session, true);
            diagnostic = "Oferta OCR + botón accesible";
            return;
          }
        }

        // List containers need card geometry so Coco can target the exact Accept button.
        List<Offer> cards = list ? TextCollector.cards(root) : OfferParser.parseVisibleNormalized(normalized);
        if (cards.isEmpty()) cards = OfferParser.parseVisibleNormalized(normalized);
        if (cards.isEmpty()) {
          Offer rootOffer = OfferParser.parseNormalized(text, normalized, CABIFY);
          if (rootOffer.hasEnoughForDecision()) cards = Collections.singletonList(rootOffer);
        }
        if (cards.isEmpty()) cards = TextCollector.cards(root);
        if (!cards.isEmpty()) {
          annotateOffers(cards);
          if (cards.size() == 1) cacheUnambiguous(cards.get(0));
          rememberDetail(cards, !list && hasAccept);
          previous = cards;
          previousAt = SystemClock.elapsedRealtime();
          handleOffers(root, tree, cards, false, list, hasAccept, session, false);
          if (hasAccept && resultNeedsOcr(cards) && Build.VERSION.SDK_INT >= 30)
            requestScreenshot(root.getWindowId(), true);
          diagnostic = "Leyendo " + cards.size() + " ofertas visibles";
        } else {
          stableKey = "";
          long now = SystemClock.elapsedRealtime();
          // If Cabify exposes an Accept action but hides some fare/time nodes, OCR is urgent. Do not
          // wait for the normal heat-map capture cadence or erase an already visible result.
          if (hasAccept && Build.VERSION.SDK_INT >= 30) {
            requestScreenshot(root.getWindowId(), true);
            diagnostic = "Oferta visible · completando datos";
          } else {
            CocotaxiOverlayService.clearOffer();
            if (Build.VERSION.SDK_INT >= 30 && now - lastMapCaptureAt >= 5000L) {
              lastMapCaptureAt = now;
              requestScreenshot(root.getWindowId());
            } else if (!assigned) diagnostic = "Esperando señal de Cabify";
          }
        }
      }
    } finally {
      root.recycle();
    }
  }

  private void annotateOffer(Offer offer) {
    zoneVision.annotate(offer, System.currentTimeMillis());
  }

  private void annotateOffers(List<Offer> offers) {
    if (offers == null) return;
    long now = System.currentTimeMillis();
    for (Offer offer : offers) zoneVision.annotate(offer, now);
  }

  private DecisionEngine.Result best(
      List<Offer> cards, SettingsStore.Values v, CocoStore.Session s, int demand,
      PromotionStore.Snapshot promo) {
    CocoStore store = CocoStore.get(this);
    CocoStore.Calibration daily = v.payoutMode == 3 ? store.dailyCalibration() : null;
    Map<String, CocoStore.Calibration> learnedByKind = new HashMap<>();
    List<DecisionEngine.Result> results = new ArrayList<>(cards.size());
    for (Offer o : cards) {
      CocoStore.Calibration learned = null;
      if (v.payoutMode == 3) learned = daily;
      else if (v.payoutMode == 2) {
        learned = learnedByKind.get(o.kind);
        if (learned == null) {
          learned = store.calibration(o.kind);
          learnedByKind.put(o.kind, learned);
        }
      }
      DecisionEngine.Result result = DecisionEngine.evaluate(o, v, s, demand, learned, promo);
      results.add(result);
    }
    return OfferRanking.best(results);
  }

  private void handleOffers(
      AccessibilityNodeInfo root, TextCollector.Snapshot tree, List<Offer> cards, boolean fromOcr,
      boolean list, boolean hasAccept, CocoStore.Session session, boolean requireStable) {
    CocoStore store = CocoStore.get(this);
    // Refresh the session only when a real offer exists; map polling stays on the cheap cache.
    session = sessionSnapshot(store, true);
    SettingsStore.Values v = SettingsStore.load(this);
    int demand = demand(v);
    PromotionStore.Snapshot promo = promotionSnapshot(store, session);
    long decisionStartNs = SystemClock.elapsedRealtimeNanos();
    DecisionEngine.Result result = best(cards, v, session, demand, promo);
    long decisionEndNs = SystemClock.elapsedRealtimeNanos();
    if (result == null) return;
    for (Offer offer : cards) {
      if (offer != null && offer.hasEnoughForDecision()) {
        DecisionEngine.Result observation = offer == result.offer ? result
            : DecisionEngine.evaluate(offer, v, session, demand, calibration(store, offer.kind, v), promo);
        CocoDataStore.offer(this, session.id, offer, observation, fromOcr);
      }
    }

    // Critical ordering: decide -> click Cabify -> persist -> draw overlay. Window rendering must never
    // delay the Accept action.
    lastDispatchNs = 0L;
    int click = maybeClick(root, tree, cards, v, result, list, hasAccept, requireStable,
        fromOcr);
    long completedNs = SystemClock.elapsedRealtimeNanos();
    if (result.accept && v.autoAccept && click != CLICK_NONE) {
      long dispatchNs = lastDispatchNs > 0L ? lastDispatchNs : completedNs;
      android.util.Log.i(
          "COCO_PERF",
          "inspect_to_dispatch_ms=" + ((dispatchNs - inspectStartedNs) / 1_000_000.0)
              + " decision_ms=" + ((decisionEndNs - decisionStartNs) / 1_000_000.0)
              + " post_dispatch_ms=" + ((completedNs - dispatchNs) / 1_000_000.0)
              + " click=" + click
              + " ocr=" + fromOcr);
    }
    CocotaxiOverlayService.update(result, cards.indexOf(result.offer) + 1, cards.size(), demand, fromOcr);
    if (click != CLICK_NONE) CocotaxiOverlayService.autoAcceptResult(click == CLICK_SUCCESS);
  }

  private int maybeClick(
      AccessibilityNodeInfo root, TextCollector.Snapshot tree, List<Offer> cards,
      SettingsStore.Values v, DecisionEngine.Result r, boolean list, boolean hasAccept,
      boolean requireStable, boolean fromOcr) {
    if (!hasAccept || cards.isEmpty()) {
      stableKey = "";
      autoAcceptStatus = "Cabify no expone un botón Aceptar";
      return CLICK_NONE;
    }
    if (!v.autoAccept) { autoAcceptStatus = "Desactivado en Ajustes"; return CLICK_NONE; }
    if (r == null || !r.accept) { autoAcceptStatus = "Oferta fuera del filtro"; return CLICK_NONE; }
    // Accessibility often omits the comma and cents. Show the estimate, but never automatically
    // accept a rounded amount until a compatible OCR reading confirms the fare.
    if (!fromOcr && r.offer != null && !r.offer.fareFractionVisible) {
      autoAcceptStatus = "Esperando confirmar importe y centavos por OCR";
      return CLICK_NONE;
    }
    if (!canTargetAccept(cards, tree.acceptCount, list, r.offer)) {
      stableKey = "";
      autoAcceptStatus = "No se puede asociar la oferta a un botón único";
      return CLICK_NONE;
    }
    CocoStore store = CocoStore.get(this);
    // Cabify permits one consecutive ride. Never click a third offer while one is already queued.
    if (cachedTrip != null && store.nextTrip() != null) {
      autoAcceptStatus = "Ya existe un siguiente viaje";
      return CLICK_NONE;
    }

    long now = SystemClock.elapsedRealtime();
    String key = r.offer.key + "@" + r.offer.top;
    if (requireStable) {
      if (!stableKey.equals(key)) {
        stableKey = key;
        stableAt = now;
        autoAcceptStatus = "Confirmando oferta OCR";
        handler.removeCallbacks(stableRetry);
        handler.postDelayed(stableRetry, 110L);
        return CLICK_NONE;
      }
      if (now - stableAt < 80) {
        handler.removeCallbacks(stableRetry);
        handler.postDelayed(stableRetry, 90L);
        return CLICK_NONE;
      }
    } else {
      stableKey = key;
      stableAt = now;
    }
    if (now - clickedAt < 1200
        || now - attemptedKeys.getOrDefault(r.offer.key, -60001L) < 60000) {
      autoAcceptStatus = "Pulsación reciente; evitando duplicado";
      return CLICK_NONE;
    }

    // Reserve the debounce before dispatching: ACTION_CLICK may immediately generate a click event.
    clickedAt = now;
    boolean exactListTarget = list && r.offer != null && r.offer.bottom > r.offer.top;
    boolean clicked = exactListTarget ? TextCollector.clickOffer(root, r.offer) : tree.clickAccept();
    // Gesture fallback is safe only when there is exactly one visible Accept target.
    if (!clicked && tree.acceptCount == 1) clicked = tapAcceptWithGesture(tree.acceptBounds);
    lastDispatchNs = SystemClock.elapsedRealtimeNanos();

    // Persist only after dispatching the click. SQLite must not sit in front of the driver's Accept.
    String id = store.attempt(r.offer, r, true);
    if (clicked) {
      autoAcceptStatus = "Pulsación enviada a Cabify";
      attemptedKeys.put(r.offer.key, now);
      if (id != null) store.confirmAccepted(id);
      CocoStore.Trip accepted = id == null ? null : store.find(id);
      boolean queued = accepted != null && "SIGUIENTE".equals(accepted.status);
      diagnostic = queued ? "Siguiente viaje aceptado automáticamente" : "Viaje confirmado · en servicio";
    } else {
      autoAcceptStatus = "No se pudo pulsar Aceptar";
      if (id != null) store.status(id, "SIN BOTÓN ACCESIBLE");
      diagnostic = "Acepta manualmente: botón no accesible";
    }
    if (attemptedKeys.size() > 128)
      attemptedKeys.entrySet().removeIf(e -> now - e.getValue() > 60000L);
    cachedSessionAt = cachedTripAt = 0L;
    stableKey = "";
    return clicked ? CLICK_SUCCESS : CLICK_FAILURE;
  }

  static boolean canTargetAccept(List<Offer> cards, int acceptCount, boolean list, Offer offer) {
    if (cards == null || cards.isEmpty() || acceptCount <= 0 || offer == null) return false;
    if (!list) return cards.size() == 1;
    if (cards.size() == 1 && acceptCount == 1) return true;
    return offer.bottom > offer.top;
  }

  private static boolean resultNeedsOcr(List<Offer> cards) {
    for (Offer card : cards) if (card.hasFare() && !card.fareFractionVisible) return true;
    return false;
  }

  private boolean tapAcceptWithGesture(Rect b) {
    if (Build.VERSION.SDK_INT < 24 || b == null || b.isEmpty()) return false;
    Path path = new Path();
    path.moveTo(b.exactCenterX(), b.exactCenterY());
    GestureDescription gesture =
        new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 45))
            .build();
    try {
      return dispatchGesture(gesture, null, null);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private void requestScreenshot(int window) {
    requestScreenshot(window, false);
  }

  private void requestScreenshot(int window, boolean urgent) {
    long now = SystemClock.elapsedRealtime();
    long minGap = urgent ? 300L : 1000L;
    if (busy || now - ocrAt < minGap || Build.VERSION.SDK_INT < 30) return;
    busy = true;
    ocrAt = now;
    TakeScreenshotCallback cb =
        new TakeScreenshotCallback() {
          public void onSuccess(ScreenshotResult result) {
            Bitmap copy = null, hardware = null;
            HardwareBuffer buffer = result.getHardwareBuffer();
            try {
              hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
              if (hardware != null) copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception e) {
              diagnostic = "Captura no disponible";
            } finally {
              if (hardware != null) hardware.recycle();
              buffer.close();
            }
            if (copy == null) {
              busy = false;
              return;
            }
            processBitmap(copy, urgent);
          }

          public void onFailure(int error) {
            busy = false;
            diagnostic = "OCR no disponible (" + error + "); usa captura de respaldo";
          }
        };
    try {
      if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(window, screenshotExecutor, cb);
      else takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, cb);
    } catch (Exception e) {
      busy = false;
      diagnostic = "Captura OCR no disponible; usa captura de respaldo";
      android.util.Log.w("COCO_ACCESS", "requestScreenshot", e);
    }
  }

  public void processBitmap(Bitmap bitmap) {
    processBitmap(bitmap, true);
  }

  private void processBitmap(Bitmap bitmap, boolean urgent) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      final Bitmap postedBitmap = bitmap;
      handler.post(() -> processBitmap(postedBitmap, urgent));
      return;
    }
    if (destroyed || processing || !cabifyForeground()) {
      bitmap.recycle();
      busy = false;
      return;
    }
    // Passive screenshots ignore system bars and compare only stable UI/map regions. Moving clock,
    // battery icons and tiny map cars should not force ML Kit to OCR an otherwise unchanged frame.
    if (!urgent && !meaningfulVisualChange(bitmap)) {
      bitmap.recycle();
      busy = false;
      diagnostic = "Sin cambios relevantes · OCR omitido";
      return;
    }
    // Full-resolution 1080/2400 frames are unnecessarily expensive for OCR/heat detection on an
    // A52. Keep aspect ratio and cap width; ML Kit still gets ample text resolution.
    if (bitmap.getWidth() > 720) {
      int targetH = Math.max(1, Math.round(bitmap.getHeight() * (720f / bitmap.getWidth())));
      Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 720, targetH, true);
      if (scaled != bitmap) bitmap.recycle();
      bitmap = scaled;
    }
    final Bitmap workBitmap = bitmap;
    processing = true;
    final String captureSession = sessionSnapshot(CocoStore.get(this), false).id;
    if (ocr == null) ocr = new ScreenOcrEngine();
    ocr.processDetailed(
        workBitmap,
        new ScreenOcrEngine.DetailedCallback() {
          public void onResult(ScreenOcrEngine.Frame frame) {
            try {
              CocoStore store = CocoStore.get(CocotaxiAccessibilityService.this);
              CocoStore.Session s = sessionSnapshot(store, false);
              if (!destroyed
                  && s.active
                  && s.id.equals(captureSession)
                  && cabifyForeground()) {
                String text = frame.text;
                String normalized = OfferParser.normalize(text);
                if (s.recovering && store.recoverFromCabify(normalized)) {
                  cachedSessionAt = cachedTripAt = 0L;
                  s = sessionSnapshot(store, false);
                }
                if (s.paused) return;
                SettingsStore.Values settings =
                    SettingsStore.load(CocotaxiAccessibilityService.this);

                // Visual demand and zone labels are learned only from Cabify's own map. This path
                // never asks Android for device location.
                int visual = zoneVision.observe(CocotaxiAccessibilityService.this, workBitmap,
                    frame, normalized, System.currentTimeMillis());
                int textual = DemandModel.textSignalNormalized(normalized);
                int signal = Math.max(visual, textual);
                if (signal >= 0) {
                  mapSignal = signal;
                  mapAt = System.currentTimeMillis();
                }

                boolean assigned = AssignmentSignals.isAssignedNormalized(normalized);
                CocoStore.Trip trip = tripSnapshot(store, s);
                if (observeTripTerminal(normalized, assigned, store, s, trip)) {
                  CocotaxiOverlayService.clearOffer();
                  return;
                }
                observeAssignment(normalized, assigned, store, s, trip);
                boolean ocrOfferVisible = OfferParser.isLikelyOfferNormalized(normalized);
                if ((assigned && !ocrOfferVisible) || OfferParser.isHistoryNormalized(normalized)) {
                  CocotaxiOverlayService.clearOffer();
                  return;
                }

                List<Offer> cards = OfferParser.parseVisibleNormalized(normalized);
                if (!cards.isEmpty()) {
                  annotateOffers(cards);
                  if (cards.size() == 1) {
                    Offer candidate = cards.get(0);
                    // If Accessibility already saw the same card, fuse both readings instead of
                    // trusting whichever callback happened to arrive last.
                    if (recentDetail != null
                        && s.id.equals(detailSession)
                        && SystemClock.elapsedRealtime() - detailAt <= 5000)
                      candidate = OfferParser.merge(candidate, recentDetail);
                    annotateOffer(candidate);
                    cards = Collections.singletonList(candidate);
                    recentOcrOffer = candidate;
                    recentOcrAt = SystemClock.elapsedRealtime();
                    cacheUnambiguous(candidate);
                  }
                  rememberDetail(
                      cards,
                      cards.size() == 1
                          && normalized.contains("aceptar")
                          && !normalized.contains("viajes disponibles"));
                  int demand = demand(settings);
                  PromotionStore.Snapshot promo = promotionSnapshot(store, s);
                  DecisionEngine.Result result = best(cards, settings, s, demand, promo);
                  if (result != null) {
                    for (Offer observed : cards)
                      CocoDataStore.offer(CocotaxiAccessibilityService.this, s.id, observed,
                          observed == result.offer ? result : DecisionEngine.evaluate(observed,
                              settings, s, demand, calibration(store, observed.kind, settings), promo), true);
                    CocotaxiOverlayService.update(
                        result, cards.indexOf(result.offer) + 1, cards.size(), demand, true);
                  }
                  diagnostic = "OCR verificado: " + cards.size() + " ofertas";
                  // OCR often arrives after the last Accessibility event. Inspect the current
                  // Cabify tree now so a short-lived offer can still be accepted automatically.
                  if (settings.autoAccept && result != null && result.accept)
                    handler.post(() -> { if (!destroyed) safeInspect(null); });
                }
              }
            } finally {
              workBitmap.recycle();
              busy = false;
              processing = false;
            }
          }

          public void onError(Exception e) {
            workBitmap.recycle();
            busy = false;
            processing = false;
            diagnostic = "OCR: no se pudo leer";
          }
        });
  }

  static long regionHash(Bitmap bitmap, float left, float top, float right, float bottom) {
    if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return 0;
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int x0 = Math.max(0, Math.min(w - 1, Math.round(w * left)));
    int y0 = Math.max(0, Math.min(h - 1, Math.round(h * top)));
    int x1 = Math.max(x0 + 1, Math.min(w, Math.round(w * right)));
    int y1 = Math.max(y0 + 1, Math.min(h, Math.round(h * bottom)));
    int[] lum = new int[64];
    long sum = 0;
    for (int gy = 0; gy < 8; gy++) {
      int y = y0 + (int) (((gy + .5) / 8.0) * (y1 - y0 - 1));
      for (int gx = 0; gx < 8; gx++) {
        int x = x0 + (int) (((gx + .5) / 8.0) * (x1 - x0 - 1));
        int c = bitmap.getPixel(x, y);
        int value =
            (((c >> 16) & 255) * 299 + ((c >> 8) & 255) * 587 + (c & 255) * 114) / 1000;
        lum[gy * 8 + gx] = value;
        sum += value;
      }
    }
    int mean = (int) (sum / 64);
    long hash = 0;
    for (int i = 0; i < 64; i++) if (lum[i] >= mean) hash |= (1L << i);
    return hash;
  }

  private boolean meaningfulVisualChange(Bitmap bitmap) {
    // Lower panel catches offer/result cards; center catches meaningful Cabify state transitions.
    // Top ~14% and bottom navigation strip are intentionally excluded.
    long offer = regionHash(bitmap, .04f, .48f, .96f, .90f);
    long center = regionHash(bitmap, .10f, .18f, .90f, .82f);
    if (lastPassiveOfferHash == Long.MIN_VALUE || lastPassiveCenterHash == Long.MIN_VALUE) {
      lastPassiveOfferHash = offer;
      lastPassiveCenterHash = center;
      return true;
    }
    int offerDiff = Long.bitCount(offer ^ lastPassiveOfferHash);
    int centerDiff = Long.bitCount(center ^ lastPassiveCenterHash);
    lastPassiveOfferHash = offer;
    lastPassiveCenterHash = center;
    return offerDiff >= 5 || centerDiff >= 8;
  }

  @Override
  public void onInterrupt() {
    CocotaxiOverlayService.clearOffer();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    instance = null;
    stableKey = "";
    stableAt = 0L;
    if (ocr != null) ocr.close();
    screenshotExecutor.shutdownNow();
    CocotaxiOverlayService.clearOffer();
    super.onDestroy();
  }
}
