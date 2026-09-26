package com.cocotaxi.app;

import static org.junit.Assert.*;
import java.time.*;
import java.util.List;
import org.junit.Test;

public class FinalPaymentTest {
  @Test public void summaryIsProvisionalAndRequiresFinalScreen() {
    assertEquals(226, FinalPayment.summary(OfferParser.normalize(
        "Viaje finalizado $ 226 para ti PAGO EN LA APP Ver detalles Entendido")), .001);
    assertTrue(Double.isNaN(FinalPayment.summary(OfferParser.normalize(
        "Aceptar $ 226 para ti Ver detalles"))));
  }

  @Test public void historyReadsExactCentsAndLocalDay() {
    long now = LocalDate.of(2026, 9, 26).atTime(0, 15).atZone(CocoStore.ZONE)
        .toInstant().toEpochMilli();
    List<FinalPayment.Entry> rows = FinalPayment.history(OfferParser.normalize(
        "Historial de viajes Esta semana Ayer, 23:29 Cabify $ 226,87 "
            + "Ayer, 23:08 Cabify Eléctrico $ 382,89"), now);
    assertEquals(2, rows.size());
    assertEquals(226.87, rows.get(0).amount, .001);
    assertEquals(LocalDate.of(2026, 9, 25).atTime(23, 29).atZone(CocoStore.ZONE)
        .toInstant().toEpochMilli(), rows.get(0).at);
  }
}
