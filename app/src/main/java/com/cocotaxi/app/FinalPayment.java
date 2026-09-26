package com.cocotaxi.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Amounts displayed by Cabify after a ride, never prices from an offer or wallet. */
public final class FinalPayment {
  private static final Pattern SUMMARY = Pattern.compile(
      "(?:\\$|uyu)\\s*([0-9][0-9.]*(?:,[0-9]{2})?)\\s+para ti\\b");
  private static final Pattern HISTORY = Pattern.compile(
      "\\b(hoy|ayer),?\\s*([0-2]?[0-9]):([0-5][0-9])\\s+cabify(?:\\s+electrico)?"
          + "\\s+(?:\\$|uyu)\\s*([0-9][0-9.]*(?:,[0-9]{2}))\\b");

  public static final class Entry {
    public final long at;
    public final double amount;
    Entry(long at, double amount) { this.at = at; this.amount = amount; }
  }

  private FinalPayment() {}

  public static double summary(String normalized) {
    if (normalized == null || !normalized.contains("viaje finalizado")
        || !normalized.contains("ver detalles")) return Double.NaN;
    Matcher m = SUMMARY.matcher(normalized);
    if (!m.find()) return Double.NaN;
    double amount = Money.parse(m.group(1));
    return m.find() ? Double.NaN : amount;
  }

  public static List<Entry> history(String normalized, long now) {
    List<Entry> out = new ArrayList<>();
    if (normalized == null || !normalized.contains("historial de viajes")) return out;
    String flattened = normalized.replaceAll("\\s+", " ");
    LocalDate today = Instant.ofEpochMilli(now).atZone(CocoStore.ZONE).toLocalDate();
    Matcher m = HISTORY.matcher(flattened);
    while (m.find()) {
      int hour = Integer.parseInt(m.group(2));
      if (hour > 23) continue;
      LocalDate day = m.group(1).equals("ayer") ? today.minusDays(1) : today;
      long at = day.atTime(LocalTime.of(hour, Integer.parseInt(m.group(3))))
          .atZone(CocoStore.ZONE).toInstant().toEpochMilli();
      double amount = Money.parse(m.group(4));
      if (Double.isFinite(amount) && amount > 0 && at <= now + 60000L)
        out.add(new Entry(at, amount));
    }
    return out;
  }
}
