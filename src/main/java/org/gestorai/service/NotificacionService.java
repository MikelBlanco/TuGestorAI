package org.gestorai.service;

import org.gestorai.dao.AutonomoDao;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.model.Autonomo;
import org.gestorai.model.Presupuesto;
import org.gestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * Servicio de notificaciones automáticas por email.
 *
 * <p>Ejecuta dos tareas periódicas mediante {@link ScheduledExecutorService}:</p>
 * <ul>
 *   <li><b>Recordatorio semanal</b> — lunes a las 9:00 (configurable): lista de presupuestos
 *       en estado ACEPTADO sin facturar. Solo se envía si hay pendientes.</li>
 *   <li><b>Resumen mensual</b> — día 1 de cada mes a las 8:00 (configurable): resumen
 *       estadístico del mes anterior. Solo se envía si hubo actividad.</li>
 * </ul>
 *
 * <p>Configuración en {@code config.properties}:</p>
 * <pre>
 *   notificacion.recordatorio.enabled=true
 *   notificacion.recordatorio.dia=MONDAY
 *   notificacion.recordatorio.hora=09:00
 *   notificacion.resumen.enabled=true
 *   notificacion.resumen.dia=1
 *   notificacion.resumen.hora=08:00
 * </pre>
 *
 * <p>Llamar a {@link #arrancar()} en el inicio del contexto y a {@link #parar()} en el cierre.</p>
 */
public class NotificacionService {

    private static final Logger log = LoggerFactory.getLogger(NotificacionService.class);
    private static final ZoneId ZONA = ZoneId.of("Europe/Madrid");

    private final AutonomoDao autonomoDao       = new AutonomoDao();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final EmailService emailService     = new EmailService();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            1, r -> { Thread t = new Thread(r, "notificacion"); t.setDaemon(true); return t; });

    // -------------------------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------------------------

    /**
     * Arranca el planificador. Debe llamarse al inicializar el contexto de la aplicación.
     */
    public void arrancar() {
        if (isEnabled("notificacion.recordatorio.enabled")) {
            programarRecordatorioSemanal();
        } else {
            log.info("Recordatorio semanal desactivado por configuración");
        }
        if (isEnabled("notificacion.resumen.enabled")) {
            programarResumenMensual();
        } else {
            log.info("Resumen mensual desactivado por configuración");
        }
    }

    /**
     * Para el planificador. Debe llamarse al destruir el contexto de la aplicación.
     */
    public void parar() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Planificador de notificaciones detenido");
    }

    // -------------------------------------------------------------------------
    // Programación de tareas
    // -------------------------------------------------------------------------

    private void programarRecordatorioSemanal() {
        DayOfWeek dia = leerDiaSemana("notificacion.recordatorio.dia", DayOfWeek.MONDAY);
        int[] hm = leerHoraMinuto("notificacion.recordatorio.hora", 9, 0);
        long delay = segundosHastaProximoDia(dia, hm[0], hm[1]);

        scheduler.scheduleAtFixedRate(this::ejecutarRecordatorio,
                delay, 7 * 24 * 3600L, TimeUnit.SECONDS);

        log.info("Recordatorio semanal programado: {} a las {}:{}, primer envío en {} min",
                dia, hm[0], hm[1], delay / 60);
    }

    private void programarResumenMensual() {
        int[] hm = leerHoraMinuto("notificacion.resumen.hora", 8, 0);
        long delay = segundosHastaProximaHora(hm[0], hm[1]);

        scheduler.scheduleAtFixedRate(this::ejecutarResumenSiProcede,
                delay, 24 * 3600L, TimeUnit.SECONDS);

        log.info("Resumen mensual programado: día {} a las {}:{}, primer check en {} min",
                leerDiaResumen(), hm[0], hm[1], delay / 60);
    }

    // -------------------------------------------------------------------------
    // Tareas
    // -------------------------------------------------------------------------

    private void ejecutarRecordatorio() {
        log.info("Iniciando envío de recordatorios semanales");
        try {
            List<Autonomo> todos = autonomoDao.findAll();
            int enviados = 0;
            for (Autonomo a : todos) {
                try { if (recordatorioSiProcede(a)) enviados++; }
                catch (Exception e) {
                    log.error("Error en recordatorio autónomo id={}: {}", a.getId(), e.getMessage());
                }
            }
            log.info("Recordatorios semanales: {}/{} enviados", enviados, todos.size());
        } catch (Exception e) {
            log.error("Error en tarea de recordatorio semanal", e);
        }
    }

    private boolean recordatorioSiProcede(Autonomo autonomo) {
        if (autonomo.getEmail() == null || autonomo.getEmail().isBlank()) return false;

        List<Presupuesto> pendientes = presupuestoDao.listarPendientes(autonomo.getId());
        if (pendientes.isEmpty()) return false;

        LocalDate hoy = LocalDate.now(ZONA);
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Recordatorio semanal — TuGestorAI\n\n");
        sb.append(String.format("Tienes %d presupuesto%s aceptado%s sin facturar:\n\n",
                pendientes.size(),
                pendientes.size() == 1 ? "" : "s",
                pendientes.size() == 1 ? "" : "s"));

        BigDecimal totalPendiente = BigDecimal.ZERO;
        for (Presupuesto p : pendientes) {
            long dias = p.getCreatedAt() != null
                    ? ChronoUnit.DAYS.between(p.getCreatedAt().toLocalDate(), hoy) : 0;
            String cuando = dias == 0 ? "hoy" : dias == 1 ? "ayer" : "hace " + dias + " días";
            sb.append(String.format("• %s — %s — %s € (%s)\n",
                    p.getNumero(), nvl(p.getClienteNombre()), formatImporte(p.getTotal()), cuando));
            if (p.getTotal() != null) totalPendiente = totalPendiente.add(p.getTotal());
        }

        sb.append(String.format("\n💰 Total pendiente: %s €\n\n", formatImporte(totalPendiente)));
        sb.append("Usa /facturado P-XXXX-XXXX cuando lo pases a TicketBAI.\n");
        sb.append("Usa /pendientes en el bot para ver la lista completa.");

        emailService.enviarConAdjuntos(
                autonomo.getEmail(),
                "Tienes presupuestos pendientes de facturar — TuGestorAI",
                sb.toString());

        log.info("Recordatorio enviado a autónomo id={} ({} pendientes)", autonomo.getId(), pendientes.size());
        return true;
    }

    private void ejecutarResumenSiProcede() {
        LocalDate hoy = LocalDate.now(ZONA);
        if (hoy.getDayOfMonth() != leerDiaResumen()) return;

        LocalDate mes = hoy.minusMonths(1);
        log.info("Iniciando envío de resúmenes mensuales ({}/{})", mes.getMonthValue(), mes.getYear());
        try {
            List<Autonomo> todos = autonomoDao.findAll();
            int enviados = 0;
            for (Autonomo a : todos) {
                try { if (enviarResumenMensual(a, mes.getYear(), mes.getMonthValue())) enviados++; }
                catch (Exception e) {
                    log.error("Error en resumen mensual autónomo id={}: {}", a.getId(), e.getMessage());
                }
            }
            log.info("Resúmenes {}/{}: {}/{} enviados", mes.getMonthValue(), mes.getYear(), enviados, todos.size());
        } catch (Exception e) {
            log.error("Error en tarea de resumen mensual", e);
        }
    }

    private boolean enviarResumenMensual(Autonomo autonomo, int year, int month) {
        if (autonomo.getEmail() == null || autonomo.getEmail().isBlank()) return false;

        PresupuestoDao.ResumenMensual r = presupuestoDao.resumenMensual(autonomo.getId(), year, month);
        if (r.generados() == 0) return false;

        String nombreMes = Month.of(month).getDisplayName(TextStyle.FULL, new Locale("es", "ES"));
        String titulo = Character.toUpperCase(nombreMes.charAt(0)) + nombreMes.substring(1) + " " + year;
        int tasa = r.enviados() > 0 ? (int) Math.round(100.0 * r.aceptados() / r.enviados()) : 0;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Resumen mensual — %s\n\n", titulo));
        sb.append(String.format("Presupuestos generados:   %d\n",  r.generados()));
        sb.append(String.format("Presupuestos enviados:    %d\n",  r.enviados()));
        sb.append(String.format("Presupuestos aceptados:   %d\n",  r.aceptados()));
        sb.append(String.format("Presupuestos rechazados:  %d\n",  r.rechazados()));
        sb.append(String.format("Presupuestos facturados:  %d\n",  r.facturados()));
        sb.append(String.format("Presupuestos cancelados:  %d\n",  r.cancelados()));
        sb.append("\n");
        sb.append(String.format("💰 Total presupuestado:   %s €\n", formatImporte(r.totalPresupuestado())));
        sb.append(String.format("💰 Total aceptado:        %s €\n", formatImporte(r.totalAceptado())));
        sb.append(String.format("💰 Total facturado:       %s €\n", formatImporte(r.totalFacturado())));
        if (r.enviados() > 0) {
            sb.append(String.format("📈 Tasa de conversión:    %d%%\n", tasa));
        }

        emailService.enviarConAdjuntos(
                autonomo.getEmail(),
                String.format("Resumen mensual %s — TuGestorAI", titulo),
                sb.toString());

        log.info("Resumen {}/{} enviado a autónomo id={}", month, year, autonomo.getId());
        return true;
    }

    // -------------------------------------------------------------------------
    // Timing
    // -------------------------------------------------------------------------

    private long segundosHastaProximoDia(DayOfWeek dia, int hora, int minuto) {
        ZonedDateTime ahora = ZonedDateTime.now(ZONA);
        ZonedDateTime proximo = ahora
                .with(TemporalAdjusters.nextOrSame(dia))
                .withHour(hora).withMinute(minuto).withSecond(0).withNano(0);
        if (!proximo.isAfter(ahora)) proximo = proximo.plusWeeks(1);
        return ChronoUnit.SECONDS.between(ahora, proximo);
    }

    private long segundosHastaProximaHora(int hora, int minuto) {
        ZonedDateTime ahora = ZonedDateTime.now(ZONA);
        ZonedDateTime proximo = ahora.withHour(hora).withMinute(minuto).withSecond(0).withNano(0);
        if (!proximo.isAfter(ahora)) proximo = proximo.plusDays(1);
        return ChronoUnit.SECONDS.between(ahora, proximo);
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private boolean isEnabled(String key) {
        String val = ConfigUtil.get(key);
        return val == null || !val.equalsIgnoreCase("false");
    }

    private DayOfWeek leerDiaSemana(String key, DayOfWeek defecto) {
        String val = ConfigUtil.get(key);
        if (val == null || val.isBlank()) return defecto;
        try { return DayOfWeek.valueOf(val.toUpperCase().trim()); }
        catch (IllegalArgumentException e) {
            log.warn("Valor inválido para {}: '{}'. Usando: {}", key, val, defecto);
            return defecto;
        }
    }

    private int[] leerHoraMinuto(String key, int horaDefecto, int minutoDefecto) {
        String val = ConfigUtil.get(key);
        if (val != null && val.contains(":")) {
            try {
                String[] p = val.split(":");
                return new int[]{ Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()) };
            } catch (NumberFormatException e) {
                log.warn("Formato inválido para {}: '{}'", key, val);
            }
        }
        return new int[]{ horaDefecto, minutoDefecto };
    }

    private int leerDiaResumen() {
        String val = ConfigUtil.get("notificacion.resumen.dia");
        if (val != null) {
            try {
                int d = Integer.parseInt(val.trim());
                if (d >= 1 && d <= 28) return d;
            } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    // -------------------------------------------------------------------------
    // Misc
    // -------------------------------------------------------------------------

    private String formatImporte(BigDecimal v) {
        return v != null ? String.format("%.2f", v).replace(".", ",") : "0,00";
    }

    private String nvl(String v) { return v != null ? v : "—"; }
}
