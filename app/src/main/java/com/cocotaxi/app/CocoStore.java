package com.cocotaxi.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.SystemClock;
import android.provider.Settings;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent sessions and trip ledger. Attempts, assignment and payment are separate facts. */
public final class CocoStore extends SQLiteOpenHelper {
  public static final ZoneId ZONE = ZoneId.of("America/Montevideo");
  private static CocoStore instance;
  private final Context context;
  // In-process revision used by the accessibility hot path to invalidate short-lived caches.
  private final AtomicLong revision = new AtomicLong(1L);

  public long revision() {
    return revision.get();
  }

  private void touch() {
    revision.incrementAndGet();
  }

  private CocoStore(Context c) {
    super(c, "cocotaxi_v3.db", null, 10);
    context = c.getApplicationContext();
  }

  public static synchronized CocoStore get(Context c) {
    if (instance == null) {
      instance = new CocoStore(c);
      // A fresh process must freeze a persisted active ride before any caller can advance
      // session()/En servicio using stale monotonic anchors. This closes the race where the
      // Activity opens before AccessibilityService.onServiceConnected().
      instance.beginRecovery();
    }
    return instance;
  }

  public static synchronized void closeForTests() {
    if (instance != null) instance.close();
    instance = null;
  }

  @Override
  public void onCreate(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TABLE sessions(id TEXT PRIMARY KEY,start INTEGER,end INTEGER,active INTEGER,paused"
            + " INTEGER,elapsed INTEGER,wall INTEGER,boot INTEGER,ms INTEGER DEFAULT 0,recovering INTEGER DEFAULT 0)");
    d.execSQL(
        "CREATE TABLE trips(id TEXT PRIMARY KEY,session TEXT,at INTEGER,status TEXT,offered"
            + " REAL,kind TEXT,estimate INTEGER,actual INTEGER,cost INTEGER,ordinary REAL,pair"
            + " INTEGER DEFAULT 0,minutes REAL,accepted INTEGER,accepted_wall INTEGER DEFAULT 0,accepted_boot INTEGER DEFAULT -1,key TEXT,label TEXT,deleted INTEGER"
            + " DEFAULT 0,trip INTEGER DEFAULT 1,planned REAL DEFAULT 0,connected_adjusted INTEGER DEFAULT 0,connected_delta REAL DEFAULT 0,prior_status TEXT DEFAULT '',origin TEXT DEFAULT 'CABIFY',accepted_at INTEGER DEFAULT 0,completed_at INTEGER DEFAULT 0,corrected_at INTEGER DEFAULT 0)");
    d.execSQL("CREATE INDEX trips_session ON trips(session,at)");
    ensureTripSessionTrigger(d);
    d.execSQL("CREATE TABLE intervals(start INTEGER,end INTEGER,minutes REAL,session TEXT)");
    d.execSQL(
        "CREATE TABLE billing(day TEXT PRIMARY KEY,total REAL,tips REAL,bonus REAL,tolls"
            + " REAL,history REAL,eligible INTEGER DEFAULT 0)");
  }

  @Override
  public void onUpgrade(SQLiteDatabase d, int old, int next) {
    if (old < 2 && !hasColumn(d, "billing", "eligible"))
      d.execSQL("ALTER TABLE billing ADD COLUMN eligible INTEGER DEFAULT 0");
    if (old < 3) {
      if (!hasColumn(d, "trips", "planned"))
        d.execSQL("ALTER TABLE trips ADD COLUMN planned REAL DEFAULT 0");
      if (!hasColumn(d, "trips", "connected_adjusted"))
        d.execSQL("ALTER TABLE trips ADD COLUMN connected_adjusted INTEGER DEFAULT 0");
      // Through 2.6.9 every manual entry added its minutes to Connected. Preserve that fact so
      // later edits/deletes can reverse the historical adjustment correctly.
      d.execSQL(
          "UPDATE trips SET connected_adjusted=1 WHERE offered<=0 AND accepted<=0"
              + " AND status='FINALIZADO'");
    }
    if (old < 4) {
      if (!hasColumn(d, "trips", "accepted_wall"))
        d.execSQL("ALTER TABLE trips ADD COLUMN accepted_wall INTEGER DEFAULT 0");
      if (!hasColumn(d, "trips", "accepted_boot"))
        d.execSQL("ALTER TABLE trips ADD COLUMN accepted_boot INTEGER DEFAULT -1");
      // Existing active records predate the recovery anchors. Their offer timestamp is the safest
      // wall-clock approximation available; recovery still requires Cabify screen evidence.
      d.execSQL(
          "UPDATE trips SET accepted_wall=at WHERE status='ASIGNADO'"
              + " AND accepted_wall=0 AND accepted>0");
    }
    if (old < 5 && !hasColumn(d, "sessions", "recovering"))
      d.execSQL("ALTER TABLE sessions ADD COLUMN recovering INTEGER DEFAULT 0");
    if (old < 6) ensureTripSessionTrigger(d);
    if (old < 7) {
      if (!hasColumn(d, "trips", "connected_delta")) {
        d.execSQL("ALTER TABLE trips ADD COLUMN connected_delta REAL DEFAULT 0");
        // Earlier releases always added the full duration for manually recorded trips.
        d.execSQL("UPDATE trips SET connected_delta=minutes WHERE connected_adjusted=1 AND deleted=0");
      }
    }
    if (old < 8) {
      if (!hasColumn(d, "trips", "prior_status"))
        d.execSQL("ALTER TABLE trips ADD COLUMN prior_status TEXT DEFAULT ''");
      // 3.1.3 kept this marker on deleted rows so restore() could add the historical time back.
      // Migration 7 skipped them, leaving connected_delta at zero.
      d.execSQL("UPDATE trips SET connected_delta=minutes WHERE deleted=1"
          + " AND connected_adjusted=1 AND connected_delta=0");
    }
    if (old < 9) {
      if (!hasColumn(d, "trips", "origin"))
        d.execSQL("ALTER TABLE trips ADD COLUMN origin TEXT DEFAULT 'CABIFY'");
      // Old manually entered rows have no offer and no acceptance anchor. A zero-fare observed
      // Cabify trip can share those values after completion; the label disambiguates it.
      d.execSQL("UPDATE trips SET origin='MANUAL' WHERE offered<=0 AND accepted<=0"
          + " AND status='FINALIZADO' AND COALESCE(label,'') NOT IN"
          + " ('Aceptación automática','Aceptación manual detectada')"
          + " AND COALESCE(label,'') NOT LIKE 'Viaje aceptado en Cabify%'");
    }
    if (old < 10) {
      if (!hasColumn(d, "trips", "accepted_at"))
        d.execSQL("ALTER TABLE trips ADD COLUMN accepted_at INTEGER DEFAULT 0");
      if (!hasColumn(d, "trips", "completed_at"))
        d.execSQL("ALTER TABLE trips ADD COLUMN completed_at INTEGER DEFAULT 0");
      if (!hasColumn(d, "trips", "corrected_at"))
        d.execSQL("ALTER TABLE trips ADD COLUMN corrected_at INTEGER DEFAULT 0");
      // Historical `at` may already be the completion/correction instant. Do not invent an
      // original acceptance timestamp when the old schema did not preserve it.
    }
  }

  private static void ensureTripSessionTrigger(SQLiteDatabase d) {
    d.execSQL(
        "CREATE TRIGGER IF NOT EXISTS trips_require_session BEFORE INSERT ON trips "
            + "WHEN NEW.session IS NULL OR NEW.session='' OR NOT EXISTS "
            + "(SELECT 1 FROM sessions WHERE id=NEW.session) BEGIN "
            + "SELECT RAISE(ABORT,'trip requires session'); END");
  }

  private static boolean hasColumn(SQLiteDatabase d, String table, String column) {
    try (Cursor q = d.rawQuery("PRAGMA table_info(" + table + ")", null)) {
      int name = q.getColumnIndex("name");
      while (q.moveToNext()) if (column.equals(q.getString(name))) return true;
    }
    return false;
  }

  private int boot() {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
  }

  public static long dayStart(long at) {
    return Instant.ofEpochMilli(at)
        .atZone(ZONE)
        .toLocalDate()
        .atStartOfDay(ZONE)
        .toInstant()
        .toEpochMilli();
  }

  public static long weekStart(long at) {
    LocalDate d = Instant.ofEpochMilli(at).atZone(ZONE).toLocalDate();
    return d.minusDays(d.getDayOfWeek().getValue() - 1)
        .atStartOfDay(ZONE)
        .toInstant()
        .toEpochMilli();
  }

  public synchronized Session session() {
    SQLiteDatabase d = getWritableDatabase();
    Session s = new Session();
    try (Cursor q =
        d.rawQuery(
            "SELECT id,start,end,active,paused,elapsed,wall,boot,ms,recovering FROM sessions ORDER BY start"
                + " DESC, rowid DESC LIMIT 1",
            null)) {
      if (!q.moveToFirst()) return s;
      s.id = q.getString(0);
      s.start = q.getLong(1);
      s.end = q.getLong(2);
      s.active = q.getInt(3) == 1;
      s.paused = q.getInt(4) == 1;
      s.recovering = q.getInt(9) == 1;
      long elapsed = q.getLong(5),
          wall = q.getLong(6),
          ms = q.getLong(8),
          now = System.currentTimeMillis(),
          mono = SystemClock.elapsedRealtime();
      if (s.active) {
        if (q.getInt(7) != boot() || mono < elapsed) {
          boolean wasPaused = s.paused;
          ContentValues v = new ContentValues();
          v.put("paused", 1);
          v.put("recovering", wasPaused ? 0 : 1);
          v.put("boot", boot());
          v.put("elapsed", mono);
          d.update("sessions", v, "id=?", new String[] {s.id});
          // A reboot invalidates monotonic anchors. Preserve the last persisted counters and let
          // Accessibility reconcile Cabify before resuming a previously running session.
          s.paused = true;
          s.recovering = !wasPaused;
          touch();
        } else if (!s.paused) {
          long delta = mono - elapsed;
          s.minutes = (ms + delta) / 60000.0;
          if (delta >= 5000) {
            d.beginTransaction();
            try {
              long from = Math.abs(now - wall - delta) > 60000 ? now - delta : wall;
              ContentValues interval = new ContentValues();
              interval.put("start", from);
              interval.put("end", now);
              interval.put("minutes", delta / 60000.0);
              interval.put("session", s.id);
              d.insertOrThrow("intervals", null, interval);
              ContentValues v = new ContentValues();
              v.put("ms", ms + delta);
              v.put("elapsed", mono);
              v.put("wall", now);
              d.update("sessions", v, "id=?", new String[] {s.id});
              checkpointLiveServiceSegment(d, s.id, mono, now);
              d.setTransactionSuccessful();
            } finally {
              d.endTransaction();
            }
          }
        }
      }
      if (!s.active || s.paused) s.minutes = ms / 60000.0;
    }
    Totals t = totals("session=?", new String[] {s.id});
    s.money = t.money;
    s.estimated = t.estimated;
    s.earned = t.earned;
    s.trips = t.trips;
    // Finalized/manual rides store their service duration. A live assigned ride uses a real
    // stopwatch that freezes together with the Coco session; the offer estimate is never a cap.
    s.service = Math.min(s.minutes, t.service + liveServiceMinutes(s.id));
    s.pending = t.pending;
    return s;
  }

  /** Persist the current active-service segment together with the 5 s session checkpoint. */
  private void checkpointLiveServiceSegment(
      SQLiteDatabase db, String sessionId, long mono, long wall) {
    if (sessionId == null || sessionId.isEmpty()) return;
    try (Cursor q =
        db.rawQuery(
            "SELECT id,accepted,minutes,accepted_boot FROM trips WHERE session=? AND deleted=0"
                + " AND status='ASIGNADO' ORDER BY at DESC LIMIT 1",
            new String[] {sessionId})) {
      if (!q.moveToFirst()) return;
      long accepted = q.getLong(1);
      int acceptedBoot = q.getInt(3);
      if (accepted <= 0 || acceptedBoot != boot() || mono < accepted) return;
      ContentValues v = new ContentValues();
      v.put("minutes", Math.max(0, q.getDouble(2)) + (mono - accepted) / 60000.0);
      v.put("accepted", mono);
      v.put("accepted_wall", wall);
      v.put("accepted_boot", boot());
      db.update("trips", v, "id=?", new String[] {q.getString(0)});
    }
  }

  private double liveServiceMinutes(String sessionId) {
    if (sessionId == null || sessionId.isEmpty()) return 0;
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT accepted,minutes,accepted_boot FROM trips WHERE session=? AND deleted=0 AND status='ASIGNADO'"
                    + " ORDER BY at DESC LIMIT 1",
                new String[] {sessionId})) {
      if (!q.moveToFirst()) return 0;
      long accepted = q.getLong(0);
      double saved = Math.max(0, q.getDouble(1));
      int acceptedBoot = q.getInt(2);
      long now = SystemClock.elapsedRealtime();
      // Monotonic anchors cannot cross a device reboot. Freeze at the last persisted value until
      // INITIALIZANDO confirms from Cabify that the ride is still active.
      double elapsed =
          accepted > 0 && acceptedBoot == boot() && now >= accepted
              ? (now - accepted) / 60000.0
              : 0;
      return saved + elapsed;
    }
  }

  public synchronized void start() {
    if (session().active) return;
    ContentValues v = new ContentValues();
    v.put("id", UUID.randomUUID().toString());
    v.put("start", System.currentTimeMillis());
    v.put("end", 0);
    v.put("active", 1);
    v.put("paused", 0);
    v.put("recovering", 0);
    v.put("elapsed", SystemClock.elapsedRealtime());
    v.put("wall", System.currentTimeMillis());
    v.put("boot", boot());
    getWritableDatabase().insertOrThrow("sessions", null, v);
    touch();
  }

  public synchronized void pause() {
    Session s = session();
    if (!s.active) return;
    if (!s.paused) {
      checkpointRemainder(s);
      checkpointActiveService(s.id, false);
    } else {
      checkpointActiveService(s.id, true);
    }
    ContentValues v = new ContentValues();
    v.put("paused", s.paused ? 0 : 1);
    v.put("recovering", 0);
    v.put("elapsed", SystemClock.elapsedRealtime());
    v.put("wall", System.currentTimeMillis());
    v.put("boot", boot());
    getWritableDatabase().update("sessions", v, "id=?", new String[] {s.id});
    touch();
  }

  /** Freeze/resume En servicio exactly together with the Coco session. */
  private void checkpointActiveService(String sessionId, boolean resume) {
    if (sessionId == null || sessionId.isEmpty()) return;
    SQLiteDatabase db = getWritableDatabase();
    try (Cursor q =
        db.rawQuery(
            "SELECT id,accepted,minutes,accepted_boot FROM trips WHERE session=? AND deleted=0"
                + " AND status='ASIGNADO' ORDER BY at DESC LIMIT 1",
            new String[] {sessionId})) {
      if (!q.moveToFirst()) return;
      String id = q.getString(0);
      long accepted = q.getLong(1), now = SystemClock.elapsedRealtime();
      double saved = Math.max(0, q.getDouble(2));
      int acceptedBoot = q.getInt(3);
      ContentValues values = new ContentValues();
      if (resume) {
        if (accepted <= 0) {
          values.put("accepted", now);
          values.put("accepted_wall", System.currentTimeMillis());
          values.put("accepted_boot", boot());
        }
      } else {
        double elapsed =
            accepted > 0 && acceptedBoot == boot() && now >= accepted
                ? (now - accepted) / 60000.0
                : 0;
        values.put("minutes", saved + elapsed);
        values.put("accepted", 0);
        values.put("accepted_wall", 0);
        values.put("accepted_boot", boot());
      }
      if (values.size() > 0) db.update("trips", values, "id=?", new String[] {id});
    }
  }

  private void checkpointRemainder(Session s) {
    if (!s.active || s.paused) return;
    SQLiteDatabase d = getWritableDatabase();
    try (Cursor q =
        d.rawQuery("SELECT elapsed,wall,ms FROM sessions WHERE id=?", new String[] {s.id})) {
      if (q.moveToFirst()) {
        long now = System.currentTimeMillis(),
            mono = SystemClock.elapsedRealtime(),
            delta = Math.max(0, mono - q.getLong(0));
        if (delta == 0) return;
        d.beginTransaction();
        try {
          ContentValues i = new ContentValues();
          i.put("start", now - delta);
          i.put("end", now);
          i.put("minutes", delta / 60000.0);
          i.put("session", s.id);
          d.insertOrThrow("intervals", null, i);
          ContentValues v = new ContentValues();
          v.put("ms", q.getLong(2) + delta);
          v.put("elapsed", mono);
          v.put("wall", now);
          d.update("sessions", v, "id=?", new String[] {s.id});
          d.setTransactionSuccessful();
        } finally {
          d.endTransaction();
        }
      }
    }
  }

  public synchronized void stop() {
    Session s = session();
    if (!s.active) return;
    checkpointRemainder(s);
    checkpointActiveService(s.id, false);
    ContentValues v = new ContentValues();
    v.put("active", 0);
    v.put("paused", 0);
    v.put("recovering", 0);
    v.put("end", System.currentTimeMillis());
    getWritableDatabase().update("sessions", v, "id=?", new String[] {s.id});
    invalidatePending(s.id);
    touch();
  }

  private void invalidatePending(String sessionId) {
    ContentValues v = new ContentValues();
    v.put("status", "SIN CONFIRMAR");
    getWritableDatabase()
        .update(
            "trips", v, "session=? AND status IN ('INTENTO','ASIGNADO','INTENTO_SIGUIENTE','SIGUIENTE')", new String[] {sessionId});
  }

  public synchronized String attempt(Offer o, DecisionEngine.Result result, boolean automatic) {
    Session s = session();
    if (!s.active || s.paused) return null;
    // ACTION_CLICK, an accessibility click event and the polling loop can all see the same card.
    // The second observation must not become an accepted consecutive ride.
    if (o == null || o.key == null || o.key.isEmpty()) return null;
    try (Cursor duplicate = getReadableDatabase().rawQuery(
        "SELECT 1 FROM trips WHERE session=? AND deleted=0 AND key=?"
            + " AND status IN ('INTENTO','ASIGNADO','INTENTO_SIGUIENTE','SIGUIENTE')"
            + " AND at>? LIMIT 1",
        new String[] {s.id, o.key, Long.toString(System.currentTimeMillis() - 60000L)})) {
      if (duplicate.moveToFirst()) return null;
    }
    Trip current = activeTrip();
    boolean next = current != null;
    if (next && (!"ASIGNADO".equals(current.status) || nextTrip() != null)) return null;
    String id = UUID.randomUUID().toString();
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("session", s.id);
    v.put("at", System.currentTimeMillis());
    v.put("status", next ? "INTENTO_SIGUIENTE" : "INTENTO");
    v.put("offered", o.fare);
    v.put("kind", o.kind);
    v.put("estimate", Money.cents(result.payout));
    v.put("cost", Money.cents(result.cost));
    v.put("minutes", 0);
    v.put("planned", Math.max(0, result.minutes));
    v.put("accepted", 0);
    v.put("key", o.key);
    v.put("origin", "CABIFY");
    v.put("label", automatic ? "Aceptación automática" : "Selección manual detectada");
    getWritableDatabase().insertOrThrow("trips", null, v);
    touch();
    return id;
  }

  /**
   * Marks an acceptance as confirmed immediately after the Cabify Accept action succeeds.
   * Cabify can still revoke it later (cancelled/taken by another driver); observe() handles that.
   */
  public synchronized boolean confirmAccepted(String id) {
    Trip t = find(id);
    if (t == null) return false;
    if (t.status.equals("ASIGNADO") || t.status.equals("SIGUIENTE")) return true;
    if (!t.status.equals("INTENTO") && !t.status.equals("INTENTO_SIGUIENTE")) return false;
    ContentValues v = new ContentValues();
    boolean queued = t.status.equals("INTENTO_SIGUIENTE");
    v.put("status", queued ? "SIGUIENTE" : "ASIGNADO");
    v.put("accepted", queued ? 0 : SystemClock.elapsedRealtime());
    v.put("accepted_wall", queued ? 0 : System.currentTimeMillis());
    v.put("accepted_at", System.currentTimeMillis());
    v.put("accepted_boot", boot());
    boolean changed =
        getWritableDatabase()
                .update(
                    "trips",
                    v,
                    "id=? AND status=?",
                    new String[] {id, t.status})
            == 1;
    if (changed) touch();
    return changed;
  }

  /**
   * Records an assignment visible on Cabify's pickup screen even if the driver tapped Accept
   * manually and the offer card disappeared before Coco captured its amount. A recent known offer
   * keeps its estimate; otherwise a zero-value assigned trip starts the real live service clock and
   * the payment can be corrected later.
   */
  public synchronized String observedAssignment(
      Offer offer, DecisionEngine.Result result, boolean automatic) {
    Session s = session();
    if (!s.active || s.paused) return null;
    Trip active = activeTrip();
    if (active != null) return active.id;

    String id = UUID.randomUUID().toString();
    ContentValues v = new ContentValues();
    v.put("id", id);
    v.put("session", s.id);
    v.put("at", System.currentTimeMillis());
    v.put("status", "ASIGNADO");
    v.put("offered", offer == null ? 0 : offer.fare);
    v.put("kind", offer == null ? "UNKNOWN" : offer.kind);
    v.put("estimate", Money.cents(result == null ? 0 : Math.max(0, result.payout)));
    v.put("cost", Money.cents(result == null ? 0 : Math.max(0, result.cost)));
    v.put("minutes", 0);
    v.put("planned", result == null ? 0 : Math.max(0, result.minutes));
    v.put("accepted", SystemClock.elapsedRealtime());
    v.put("accepted_wall", System.currentTimeMillis());
    v.put("accepted_at", System.currentTimeMillis());
    v.put("accepted_boot", boot());
    v.put("key", offer == null ? "" : offer.key);
    v.put("origin", "CABIFY");
    v.put(
        "label",
        offer == null
            ? "Viaje aceptado en Cabify · importe pendiente"
            : automatic ? "Aceptación automática" : "Aceptación manual detectada");
    v.put("trip", 1);
    getWritableDatabase().insertOrThrow("trips", null, v);
    touch();
    return id;
  }

  /** Remove the default trip confirmation without deleting the record. */
  public synchronized void unconfirm(String id) {
    Trip t = find(id);
    if (t == null || t.deleted) return;
    if (!t.status.equals("ASIGNADO") && !t.status.equals("FINALIZADO")) return;
    ContentValues v = new ContentValues();
    v.put("status", "SIN CONFIRMAR");
    v.put("prior_status", t.status);
    if ("ASIGNADO".equals(t.status))
      v.put("minutes", Math.max(0, t.minutes) + activeSegmentMinutes(t));
    v.put("accepted", 0);
    v.put("accepted_wall", 0);
    v.put("accepted_boot", boot());
    getWritableDatabase().update("trips", v, "id=?", new String[] {id});
    touch();
  }

  /** Restore a trip the driver previously marked as unconfirmed. */
  public synchronized void reconfirm(String id) {
    Trip t = find(id);
    if (t == null || t.deleted || !t.status.equals("SIN CONFIRMAR")) return;
    Session s = session();
    boolean resume = "ASIGNADO".equals(t.priorStatus)
        && s.active && t.session.equals(s.id) && activeTrip() == null;
    boolean finalized = "FINALIZADO".equals(t.priorStatus) ||
        ("ASIGNADO".equals(t.priorStatus) && !resume);
    // Legacy unconfirmed records without a persisted origin cannot safely be re-anchored.
    if (!resume && !finalized) finalized = true;
    ContentValues v = new ContentValues();
    v.put("status", resume ? "ASIGNADO" : "FINALIZADO");
    v.put("prior_status", "");
    if (resume && !s.paused && !s.recovering) {
      v.put("accepted", SystemClock.elapsedRealtime());
      v.put("accepted_wall", System.currentTimeMillis());
      v.put("accepted_boot", boot());
    }
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.update("trips", v, "id=? AND status='SIN CONFIRMAR'", new String[] {id});
      if (finalized && s.active && t.session.equals(s.id) && activeTrip() == null)
        promoteNext(db, t.session);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
  }

  public synchronized void observe(String raw) {
    observeNormalized(OfferParser.normalize(raw));
  }

  public synchronized void observeNormalized(String text) {
    Session s = session();
    if (!s.active) return;
    Trip t = activeTrip();
    if (t == null) return;
    if (OfferParser.isHistoryNormalized(text)) return;
    if (AssignmentSignals.isCancelledNormalized(text)) {
      cancelObserved(t.id);
      return;
    }
    if (t.status.equals("INTENTO") && (text.contains("otro conductor")
        || text.contains("ya no esta disponible")
        || text.contains("no se pudo aceptar")
        || text.contains("error al aceptar"))) {
      status(t.id, "NO ASIGNADO");
      return;
    }
    if (t.status.equals("ASIGNADO") && AssignmentSignals.isCompletedNormalized(text)) {
      complete(t.id);
      return;
    }
    if (s.paused) return;
    if (t.status.equals("INTENTO")) {
      if (System.currentTimeMillis() - t.at > 45000) {
        status(t.id, "SIN CONFIRMAR");
        return;
      }
      boolean assigned =
          AssignmentSignals.isAssignedNormalized(text)
              || text.contains("ir a recoger")
              || text.contains("he llegado")
              || text.contains("iniciar viaje")
              || text.contains("recoger al pasajero");
      if (assigned && !text.contains("aceptar")) confirmAccepted(t.id);
    }
  }

  public synchronized boolean cancelObserved(String id) {
    Trip t = find(id);
    if (t == null || t.deleted) return false;
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    boolean changed;
    try {
      ContentValues v = new ContentValues();
      v.put("status", "CANCELADO");
      if ("ASIGNADO".equals(t.status)) {
        v.put("minutes", Math.max(0, t.minutes) + activeSegmentMinutes(t));
        v.put("accepted", 0);
        v.put("accepted_wall", 0);
        v.put("accepted_boot", boot());
      }
      changed = db.update("trips", v, "id=?", new String[] {id}) == 1;
      if (changed && "ASIGNADO".equals(t.status)) promoteNext(db, t.session);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    if (changed) touch();
    return changed;
  }

  private double activeSegmentMinutes(Trip t) {
    if (t == null || t.accepted <= 0) return 0;
    if (t.acceptedBoot == boot()) {
      long now = SystemClock.elapsedRealtime();
      return now >= t.accepted ? (now - t.accepted) / 60000.0 : 0;
    }
    // A monotonic anchor from another boot/process-recovery window is intentionally not extended
    // with wall clock time. The persisted 5 s checkpoint is authoritative until Cabify confirms.
    return 0;
  }

  /** Freeze an active ride at the last persisted checkpoint until Cabify is inspected. */
  public synchronized boolean beginRecovery() {
    SQLiteDatabase db = getWritableDatabase();
    String sessionId = null;
    boolean active = false, paused = false, recovering = false, rebooted = false;
    try (Cursor q =
        db.rawQuery(
            "SELECT id,active,paused,recovering,boot FROM sessions ORDER BY start DESC,rowid DESC LIMIT 1",
            null)) {
      if (q.moveToFirst()) {
        sessionId = q.getString(0);
        active = q.getInt(1) == 1;
        paused = q.getInt(2) == 1;
        recovering = q.getInt(3) == 1;
        rebooted = q.getInt(4) != boot();
      }
    }
    if (!active || sessionId == null || (paused && !recovering)) return false;
    String tripId = null;
    try (Cursor q =
        db.rawQuery(
            "SELECT id FROM trips WHERE session=? AND deleted=0 AND status='ASIGNADO'"
                + " ORDER BY at DESC LIMIT 1",
            new String[] {sessionId})) {
      if (q.moveToFirst()) tripId = q.getString(0);
    }
    if (tripId == null) {
      if (rebooted) session(); // invalidate old boot anchors without counting device downtime
      if (recovering || rebooted) {
        resumeSessionAfterRecovery(sessionId);
        touch();
      }
      return false;
    }
    ContentValues sv = new ContentValues();
    sv.put("paused", 1);
    sv.put("recovering", 1);
    sv.put("elapsed", SystemClock.elapsedRealtime());
    sv.put("boot", boot());
    db.update("sessions", sv, "id=?", new String[] {sessionId});
    ContentValues tv = new ContentValues();
    tv.put("accepted", 0);
    tv.put("accepted_wall", 0);
    tv.put("accepted_boot", boot());
    db.update("trips", tv, "id=?", new String[] {tripId});
    touch();
    return true;
  }

  /**
   * Reconcile a persisted active ride after a service/process restart or device reboot. While this
   * flag is set neither Connected nor En servicio advances until Cabify provides screen evidence.
   */
  public synchronized boolean recoverFromCabify(String normalized) {
    Session s = session();
    if (!s.active || !s.recovering) return false;
    Trip t = activeTrip();
    if (t == null || !"ASIGNADO".equals(t.status)) return false;

    String text = OfferParser.normalize(normalized == null ? "" : normalized);
    if (AssignmentSignals.isCancelledNormalized(text)) {
      cancelObserved(t.id);
      resumeSessionAfterRecovery(s.id);
      return true;
    }
    if (AssignmentSignals.isCompletedNormalized(text)) {
      complete(t.id);
      resumeSessionAfterRecovery(s.id);
      return true;
    }
    if (AssignmentSignals.isStrongIdleMainMapNormalized(text)) {
      // Cabify is no longer in a journey, but without an explicit result we cannot safely invent
      // either payment or duration. Keep the record for manual confirmation and resume searching.
      ContentValues v = new ContentValues();
      v.put("status", "SIN CONFIRMAR");
      v.put("prior_status", "ASIGNADO");
      v.put("accepted", 0);
      v.put("accepted_wall", 0);
      v.put("accepted_boot", boot());
      getWritableDatabase().update("trips", v, "id=?", new String[] {t.id});
      resumeSessionAfterRecovery(s.id);
      touch();
      return true;
    }
    if (AssignmentSignals.isAssignedNormalized(text) || AssignmentSignals.looksFinishAction(text)) {
      ContentValues v = new ContentValues();
      v.put("minutes", Math.max(0, t.minutes));
      v.put("accepted", SystemClock.elapsedRealtime());
      v.put("accepted_wall", System.currentTimeMillis());
      v.put("accepted_boot", boot());
      getWritableDatabase().update("trips", v, "id=?", new String[] {t.id});
      resumeSessionAfterRecovery(s.id);
      touch();
      return true;
    }
    return false;
  }

  private void resumeSessionAfterRecovery(String sessionId) {
    ContentValues v = new ContentValues();
    v.put("paused", 0);
    v.put("recovering", 0);
    v.put("elapsed", SystemClock.elapsedRealtime());
    v.put("wall", System.currentTimeMillis());
    v.put("boot", boot());
    getWritableDatabase().update("sessions", v, "id=?", new String[] {sessionId});
  }

  /**
   * Finalizes an assigned trip. Its value leaves the live-estimated bucket and immediately becomes
   * earned for the session. If no corrected real payment exists yet, the accepted estimate remains
   * the best available amount; correcting the trip later replaces it without double-counting.
   */
  public synchronized boolean complete(String id) {
    Trip t = find(id);
    if (t == null || !t.status.equals("ASIGNADO")) return false;
    ContentValues v = new ContentValues();
    v.put("status", "FINALIZADO");
    v.put("completed_at", System.currentTimeMillis());
    double elapsed = activeSegmentMinutes(t);
    v.put("minutes", Math.max(0, t.minutes) + elapsed);
    v.put("accepted", 0);
    v.put("accepted_wall", 0);
    v.put("accepted_boot", boot());
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      db.update("trips", v, "id=? AND status='ASIGNADO'", new String[] {id});
      promoteNext(db, t.session);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
    return true;
  }

  /** Reconcile the just completed Cabify ride with its summary or exact history payout. */
  public synchronized boolean recordFinalPayment(String sessionId, long shownAt,
      double amount, boolean fromHistory) {
    if (!Double.isFinite(amount) || amount <= 0 || shownAt <= 0)
      return false;
    long tolerance = fromHistory ? 15 * 60000L : 5 * 60000L;
    String selected = null;
    long selectedActual = Long.MIN_VALUE;
    int candidates = 0;
    String sessionFilter = sessionId == null ? "" : "session=? AND ";
    String[] args = sessionId == null
        ? new String[] {"" + shownAt, "" + tolerance}
        : new String[] {sessionId, "" + shownAt, "" + tolerance};
    try (Cursor q = getReadableDatabase().rawQuery(
        "SELECT id,actual FROM trips WHERE " + sessionFilter + "origin='CABIFY' AND deleted=0"
            + " AND status='FINALIZADO' AND completed_at>0 AND corrected_at=0"
            + " AND ABS(completed_at-?)<=? ORDER BY completed_at DESC",
        args)) {
      while (q.moveToNext()) {
        if (fromHistory && !q.isNull(1)
            && (long) Math.floor(q.getLong(1) / 100.0) != (long) Math.floor(amount))
          continue; // A provisional summary must belong to the same integer fare.
        selected = q.getString(0);
        selectedActual = q.isNull(1) ? Long.MIN_VALUE : q.getLong(1);
        candidates++;
      }
    }
    if (candidates != 1 || (!fromHistory && selectedActual != Long.MIN_VALUE)
        || selectedActual == Money.cents(amount)) return false;
    ContentValues values = new ContentValues();
    values.put("actual", Money.cents(amount));
    int updated = getWritableDatabase().update("trips", values,
        "id=? AND status='FINALIZADO' AND corrected_at=0",
        new String[] {selected});
    if (updated > 0) touch();
    return updated > 0;
  }

  /** Starts an already accepted consecutive ride only after the current ride ends. */
  private void promoteNext(SQLiteDatabase db, String sessionId) {
    try (Cursor q =
        db.rawQuery(
            "SELECT id FROM trips WHERE session=? AND deleted=0 AND status='SIGUIENTE' ORDER BY at ASC LIMIT 1",
            new String[] {sessionId})) {
      if (!q.moveToFirst()) return;
      ContentValues next = new ContentValues();
      next.put("status", "ASIGNADO");
      next.put("accepted", SystemClock.elapsedRealtime());
      next.put("accepted_wall", System.currentTimeMillis());
      next.put("accepted_boot", boot());
      db.update("trips", next, "id=? AND status='SIGUIENTE'", new String[] {q.getString(0)});
    }
  }

  public synchronized void status(String id, String status) {
    if ("SIN CONFIRMAR".equals(status)) {
      Trip t = find(id);
      if (t != null && ("ASIGNADO".equals(t.status) || "FINALIZADO".equals(t.status))) {
        unconfirm(id);
        return;
      }
    }
    ContentValues v = new ContentValues();
    v.put("status", status);
    getWritableDatabase().update("trips", v, "id=?", new String[] {id});
    touch();
  }

  public synchronized String manual(double payout, double minutes, String label) {
    return manual(payout, minutes, label, true);
  }

  public synchronized String manual(double payout, double minutes, String label, boolean trip) {
    Session s = session();
    return manualForSession(s.id, payout, minutes, label, trip);
  }

  /**
   * Adds a completed manual entry to an existing session. A manual trip is historical work already
   * performed. An active or paused session only gains the missing coverage needed to keep Connected
   * >= En servicio. A closed session gains the entire duration. The exact addition is stored on the
   * trip for corrections and deletion; a manual trip never starts a live stopwatch.
   * A non-trip extra/bonus contributes money only and does not invent service or connected minutes.
   */
  public synchronized String manualForSession(
      String sessionId, double payout, double minutes, String label, boolean trip) {
    if (sessionId == null
        || sessionId.isEmpty()
        || !sessionExists(sessionId)
        || !Double.isFinite(payout)
        || payout < 0
        || !Double.isFinite(minutes)
        || minutes < 0)
      throw new IllegalArgumentException("Elige una sesión válida y revisa los importes");

    Session current = session();
    boolean currentSession = sessionId.equals(current.id);
    double storedMinutes = trip ? minutes : 0;
    if (current.active && currentSession && !current.paused) checkpointRemainder(current);
    current = session();
    boolean targetActive = currentSession && current.active;
    double connectedDelta =
        !trip ? 0 : targetActive
            ? Math.max(0, current.service + storedMinutes - current.minutes)
            : storedMinutes;

    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    String id = UUID.randomUUID().toString();
    try {
      ContentValues v = new ContentValues();
      v.put("id", id);
      v.put("session", sessionId);
      v.put("at", manualTimestamp(sessionId));
      v.put("status", "FINALIZADO");
      v.put("origin", "MANUAL");
      v.put("completed_at", System.currentTimeMillis());
      v.put("offered", 0);
      v.put("kind", "UNKNOWN");
      v.put("estimate", 0);
      v.put("actual", Money.cents(payout));
      v.put("cost", 0);
      v.put("minutes", storedMinutes);
      v.put("planned", 0);
      v.put("accepted", 0);
      v.put("connected_adjusted", connectedDelta > 0 ? 1 : 0);
      v.put("connected_delta", connectedDelta);
      v.put("label", label);
      v.put("trip", trip ? 1 : 0);
      db.insertOrThrow("trips", null, v);
      if (connectedDelta > 0) addConnectedMinutes(db, sessionId, connectedDelta);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
    return id;
  }

  private boolean sessionExists(String sessionId) {
    try (Cursor q =
        getReadableDatabase()
            .rawQuery("SELECT 1 FROM sessions WHERE id=? LIMIT 1", new String[] {sessionId})) {
      return q.moveToFirst();
    }
  }

  private long manualTimestamp(String sessionId) {
    try (Cursor q =
        getReadableDatabase()
            .rawQuery("SELECT start,end FROM sessions WHERE id=? LIMIT 1", new String[] {sessionId})) {
      if (!q.moveToFirst()) return System.currentTimeMillis();
      long start = q.getLong(0);
      long end = q.getLong(1);
      // Historical entries belong to the selected session also in day/week analytics.
      return end > 0 ? end : Math.max(start, System.currentTimeMillis());
    }
  }

  private void addConnectedMinutes(SQLiteDatabase db, String sessionId, double minutes) {
    if (!Double.isFinite(minutes) || minutes == 0) return;
    long deltaMs = Math.round(minutes * 60000.0);
    db.execSQL(
        "UPDATE sessions SET ms=MAX(0,ms+?) WHERE id=?",
        new Object[] {deltaMs, sessionId});
  }

  public synchronized void correct(String id, double actual, double cost, boolean pair, double ordinary) {
    correct(id, actual, cost, pair, ordinary, Double.NaN);
  }

  /**
   * Corrects a trip. For manually registered entries, manualMinutes may also replace the recorded
   * service duration. This never creates an ASIGNADO trip, so the live service timer is not started.
   */
  public synchronized void correct(
      String id, double actual, double cost, boolean pair, double ordinary, double manualMinutes) {
    if (!Double.isFinite(actual)
        || actual < 0
        || !Double.isFinite(cost)
        || cost < 0
        || pair && (!Double.isFinite(ordinary) || ordinary <= 0))
      throw new IllegalArgumentException("Importes no válidos");
    Trip t = find(id);
    if (t == null) return;
    boolean manualEntry = t.manual;
    if (manualEntry && Double.isFinite(manualMinutes) && manualMinutes < 0)
      throw new IllegalArgumentException("Minutos no válidos");
    if (!manualEntry && !"ASIGNADO".equals(t.status) && !"FINALIZADO".equals(t.status)
        && !"INTENTO".equals(t.status))
      throw new IllegalArgumentException("Este viaje aún no está confirmado");
    Session current = session();
    if (manualEntry
        && Double.isFinite(manualMinutes)
        && current.active
        && t.session.equals(current.id)) checkpointRemainder(current);

    current = session();
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("actual", Money.cents(actual));
      v.put("cost", Money.cents(cost));
      v.put("ordinary", ordinary);
      v.put("pair", pair && t.offered > 0 ? 1 : 0);
      if (manualEntry && Double.isFinite(manualMinutes)) {
        double correctedMinutes = t.trip ? manualMinutes : 0;
        v.put("minutes", correctedMinutes);
        double delta = current.active && t.session.equals(current.id)
            ? Math.max(0, current.service - t.minutes + correctedMinutes
                - (current.minutes - t.connectedDelta))
            : Math.max(0, t.connectedDelta + correctedMinutes - t.minutes);
        addConnectedMinutes(db, t.session, delta - t.connectedDelta);
        v.put("connected_delta", delta);
        v.put("connected_adjusted", delta > 0 ? 1 : 0);
      }
      if ("ASIGNADO".equals(t.status)) {
        v.put("minutes", Math.max(0, t.minutes) + activeSegmentMinutes(t));
        v.put("accepted", 0);
        v.put("accepted_wall", 0);
        v.put("accepted_boot", boot());
        v.put("completed_at", System.currentTimeMillis());
      }
      v.put("status", "FINALIZADO");
      v.put("corrected_at", System.currentTimeMillis());
      db.update("trips", v, "id=?", new String[] {id});
      if ("ASIGNADO".equals(t.status)) promoteNext(db, t.session);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
  }

  public synchronized void delete(String id) {
    Trip t = find(id);
    if (t == null || t.deleted) return;
    boolean manualEntry = t.manual;
    Session current = session();
    if (manualEntry && current.active && t.session.equals(current.id)) checkpointRemainder(current);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("deleted", 1);
      db.update("trips", v, "id=?", new String[] {id});
      if (manualEntry) addConnectedMinutes(db, t.session, -t.connectedDelta);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
  }

  public synchronized void restore(String id) {
    Trip t = find(id);
    if (t == null || !t.deleted) return;
    boolean manualEntry = t.manual;
    Session current = session();
    if (manualEntry && current.active && t.session.equals(current.id)) checkpointRemainder(current);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues v = new ContentValues();
      v.put("deleted", 0);
      db.update("trips", v, "id=?", new String[] {id});
      if (manualEntry) addConnectedMinutes(db, t.session, t.connectedDelta);
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
    touch();
  }

  public Trip activeTrip() {
    ContentValues expired = new ContentValues();
    expired.put("status", "SIN CONFIRMAR");
    int expiredCount =
        getWritableDatabase()
            .update(
                "trips",
                expired,
                "status='INTENTO' AND at<?",
                new String[] {"" + (System.currentTimeMillis() - 45000)});
    if (expiredCount > 0) touch();
    Session current = session();
    List<Trip> ts =
        query(
            "session=? AND deleted=0 AND status IN ('INTENTO','ASIGNADO')",
            new String[] {current.id});
    return ts.isEmpty() ? null : ts.get(0);
  }

  public Trip nextTrip() {
    ContentValues expired = new ContentValues();
    expired.put("status", "SIN CONFIRMAR");
    int expiredCount =
        getWritableDatabase()
            .update(
                "trips",
                expired,
                "status='INTENTO_SIGUIENTE' AND at<?",
                new String[] {"" + (System.currentTimeMillis() - 45000)});
    if (expiredCount > 0) touch();
    Session current = session();
    List<Trip> ts =
        query(
            "session=? AND deleted=0 AND status IN ('INTENTO_SIGUIENTE','SIGUIENTE')",
            new String[] {current.id});
    return ts.isEmpty() ? null : ts.get(0);
  }

  public Trip find(String id) {
    List<Trip> ts = query("id=?", new String[] {id});
    return ts.isEmpty() ? null : ts.get(0);
  }

  public List<Trip> trips(String session) {
    return query("session=?", new String[] {session});
  }

  private List<Trip> query(String where, String[] args) {
    List<Trip> out = new ArrayList<>();
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT"
                    + " id,session,at,status,offered,kind,estimate,actual,cost,ordinary,pair,minutes,accepted,accepted_wall,accepted_boot,key,label,deleted,planned,connected_adjusted,trip,connected_delta,prior_status,origin"
                    + " FROM trips WHERE "
                    + where
                    + " ORDER BY at DESC",
                args)) {
      while (q.moveToNext()) {
        Trip t = new Trip();
        t.id = q.getString(0);
        t.session = q.getString(1);
        t.at = q.getLong(2);
        t.status = q.getString(3);
        t.offered = q.getDouble(4);
        t.kind = q.getString(5);
        t.estimate = q.getLong(6) / 100.0;
        t.confirmed = !q.isNull(7);
        t.actual = q.getLong(7) / 100.0;
        t.cost = q.getLong(8) / 100.0;
        t.ordinary = q.getDouble(9);
        t.pair = q.getInt(10) == 1;
        t.minutes = q.getDouble(11);
        t.accepted = q.getLong(12);
        t.acceptedWall = q.getLong(13);
        t.acceptedBoot = q.getInt(14);
        t.key = q.getString(15);
        t.label = q.getString(16);
        t.deleted = q.getInt(17) == 1;
        t.planned = q.getDouble(18);
        t.connectedAdjusted = q.getInt(19) == 1;
        t.trip = q.getInt(20) == 1;
        t.connectedDelta = q.getDouble(21);
        t.priorStatus = q.getString(22);
        t.manual = "MANUAL".equals(q.getString(23));
        out.add(t);
      }
    }
    return out;
  }

  public List<Session> sessions() {
    List<Session> out = new ArrayList<>();
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT id,start,end,ms FROM sessions ORDER BY start DESC, rowid DESC", null)) {
      while (q.moveToNext()) {
        Session s = new Session();
        s.id = q.getString(0);
        s.start = q.getLong(1);
        s.end = q.getLong(2);
        s.minutes = q.getLong(3) / 60000.0;
        Totals t = totals("session=?", new String[] {s.id});
        s.money = t.money;
        s.trips = t.trips;
        out.add(s);
      }
    }
    return out;
  }

  public Totals period(long from, long to) {
    return totals("at>=? AND at<?", new String[] {"" + from, "" + to});
  }

  private Totals totals(String where, String[] args) {
    Totals t = new Totals();
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT COALESCE(SUM(COALESCE(actual,estimate)-cost),0),"
                    + "COALESCE(SUM(CASE WHEN status IN ('ASIGNADO','SIGUIENTE') THEN estimate-cost ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN status='FINALIZADO' THEN COALESCE(actual,estimate)-cost ELSE 0 END),0),"
                    + "COALESCE(SUM(trip),0),"
                    + "COALESCE(SUM(CASE WHEN status='FINALIZADO' AND trip=1 THEN minutes ELSE 0 END),0),"
                    + "COALESCE(SUM(CASE WHEN status IN ('ASIGNADO','SIGUIENTE') THEN 1 ELSE 0 END),0)"
                    + " FROM trips WHERE deleted=0 AND status IN ('ASIGNADO','SIGUIENTE','FINALIZADO') AND "
                    + where,
                args)) {
      if (q.moveToFirst()) {
        t.money = q.getLong(0) / 100.0;
        t.estimated = q.getLong(1) / 100.0;
        t.earned = q.getLong(2) / 100.0;
        t.trips = q.getInt(3);
        t.service = q.getDouble(4);
        t.pending = q.getInt(5);
      }
    }
    return t;
  }

  public double periodMinutes(long from, long to) {
    session();
    double sum = 0;
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT start,end,minutes FROM intervals WHERE start<? AND end>?",
                new String[] {"" + to, "" + from})) {
      while (q.moveToNext()) {
        long a = q.getLong(0), b = q.getLong(1);
        if (b > a) sum += q.getDouble(2) * (Math.min(b, to) - Math.max(a, from)) / (b - a);
      }
    }
    return sum;
  }

  public Calibration calibration(String kind) {
    Calibration c = new Calibration();
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*),SUM(offered),SUM(ordinary) FROM trips WHERE deleted=0 AND pair=1"
                    + " AND status='FINALIZADO' AND offered>0 AND ordinary>0 AND kind=? AND at>=?",
                new String[] {kind, "" + (System.currentTimeMillis() - 28L * 86400000)})) {
      if (q.moveToFirst()) {
        c.count = q.getInt(0);
        c.offered = q.getDouble(1);
        c.paid = q.getDouble(2);
        c.factor = c.offered > 0 ? c.paid / c.offered : 1;
      }
    }
    return c;
  }

  public void saveBilling(
      String day, double total, double tips, double bonus, double tolls, double history) {
    saveBilling(day, total, tips, bonus, tolls, history, false);
  }

  public void saveBilling(
      String day,
      double total,
      double tips,
      double bonus,
      double tolls,
      double history,
      boolean eligible) {
    LocalDate.parse(day);
    for (double x : new double[] {total, tips, bonus, tolls, history})
      if (!Double.isFinite(x) || x < 0) throw new IllegalArgumentException("Importes no válidos");
    if (tips + bonus + tolls > total)
      throw new IllegalArgumentException("Exclusiones mayores que el total");
    if (eligible) {
      if (!LocalDate.parse(day).isBefore(LocalDate.now(ZONE)))
        throw new IllegalArgumentException("Espera al cierre del día; hoy aún es parcial");
      double ratio = history > 0 ? (total - tips - bonus - tolls) / history : 0;
      if (!Double.isFinite(ratio) || ratio < .1 || ratio > 2)
        throw new IllegalArgumentException("Revisa la suma completa del historial y el total");
    }
    ContentValues v = new ContentValues();
    v.put("day", day);
    v.put("total", total);
    v.put("tips", tips);
    v.put("bonus", bonus);
    v.put("tolls", tolls);
    v.put("history", history);
    v.put("eligible", eligible ? 1 : 0);
    getWritableDatabase().insertWithOnConflict("billing", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    touch();
  }

  public Calibration calibrationForOffer(String kind, SettingsStore.Values values) {
    return values.payoutMode == 3 ? dailyCalibration() : calibration(kind);
  }

  public Calibration dailyCalibration() {
    Calibration c = new Calibration();
    c.daily = true;
    LocalDate today = LocalDate.now(ZONE);
    try (Cursor q =
        getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*),SUM(history),SUM(total-tips-bonus-tolls) FROM billing"
                    + " WHERE eligible=1 AND history>0 AND day>=? AND day<?",
                new String[] {today.minusDays(28).toString(), today.toString()})) {
      if (q.moveToFirst()) {
        c.count = q.getInt(0);
        c.offered = q.getDouble(1);
        c.paid = q.getDouble(2);
      }
    }
    // Initial factor after reconciling midnight carryovers and excluding tips on both sides.
    // History reconciliation is not proof of every original offer-to-payment pair.
    c.provisional = c.count == 0;
    c.factor = c.offered > 0 ? c.paid / c.offered : 1.0;
    return c;
  }

  public static class Totals {
    public double money, estimated, earned, service;
    public int trips, pending;
  }

  public static class Session extends Totals {
    public String id = "";
    public long start, end;
    public boolean active, paused, recovering;
    public double minutes;

    public double hourly() {
      return minutes > 0 ? money * 60 / minutes : 0;
    }
  }

  public static class Trip {
    public String id, session, status, priorStatus, kind, key, label;
    public long at, accepted, acceptedWall;
    public int acceptedBoot;
    public boolean confirmed, pair, deleted, connectedAdjusted, trip, manual;
    public double connectedDelta;
    public double offered, estimate, actual, cost, ordinary, minutes, planned;

    public double net() {
      return (confirmed ? actual : estimate) - cost;
    }
  }

  public static class Calibration {
    public int count;
    public double offered, paid, factor = 1;
    public boolean daily, provisional;

    public boolean usable() {
      return (daily ? count >= 1 || provisional : count >= 5)
          && Double.isFinite(factor)
          && factor >= .1
          && factor <= 2;
    }
  }
}
