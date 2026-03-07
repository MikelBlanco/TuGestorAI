package com.tugestorai.servlet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import com.tugestorai.dao.FacturaDao;
import com.tugestorai.dao.UsuarioDao;
import com.tugestorai.exception.ServiceException;
import com.tugestorai.model.Factura;
import com.tugestorai.model.Usuario;
import com.tugestorai.service.FacturaService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API REST de facturas para el panel web.
 *
 * <pre>
 * GET  /api/facturas              → lista facturas del usuario autenticado
 * GET  /api/facturas/{id}         → detalle con líneas
 * POST /api/facturas              → crear factura desde presupuesto
 * PUT  /api/facturas/{id}/estado  → cambiar estado
 * </pre>
 */
@WebServlet("/api/facturas/*")
public class FacturaApiServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(FacturaApiServlet.class);

    private final FacturaDao facturaDao = new FacturaDao();
    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final FacturaService facturaService = new FacturaService();
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (src, type, ctx) ->
                            ctx.serialize(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .create();

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Optional<Usuario> usuarioOpt = resolverUsuario(req);
        if (usuarioOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }
        long usuarioId = usuarioOpt.get().getId();

        String pathInfo = req.getPathInfo();

        if (pathInfo == null || pathInfo.equals("/")) {
            String estado = req.getParameter("estado");
            List<Factura> lista = (estado != null && !estado.isBlank())
                    ? facturaDao.findByUsuarioIdAndEstado(usuarioId, estado)
                    : facturaDao.findByUsuarioId(usuarioId);
            resp.getWriter().write(gson.toJson(lista));

        } else {
            long id = parsearId(pathInfo, resp);
            if (id < 0) return;

            facturaDao.findById(id).ifPresentOrElse(
                    f -> {
                        if (!f.getUsuarioId().equals(usuarioId)) {
                            try { enviarError(resp, HttpServletResponse.SC_FORBIDDEN, "Sin acceso"); }
                            catch (IOException e) { log.error("Error enviando 403", e); }
                            return;
                        }
                        try { resp.getWriter().write(gson.toJson(f)); }
                        catch (IOException e) { log.error("Error escribiendo respuesta", e); }
                    },
                    () -> {
                        try { enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "No encontrada"); }
                        catch (IOException e) { log.error("Error enviando 404", e); }
                    }
            );
        }
    }

    // -------------------------------------------------------------------------
    // POST — crear factura desde presupuesto
    // -------------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Optional<Usuario> usuarioOpt = resolverUsuario(req);
        if (usuarioOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }
        Usuario usuario = usuarioOpt.get();

        // Body esperado: {"presupuesto_id": 123, "irpf_porcentaje": 15}
        @SuppressWarnings("unchecked")
        Map<String, Object> body = gson.fromJson(req.getReader(), Map.class);
        if (body == null || !body.containsKey("presupuesto_id")) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "Falta presupuesto_id");
            return;
        }

        long presupuestoId = ((Number) body.get("presupuesto_id")).longValue();
        java.math.BigDecimal irpf = body.containsKey("irpf_porcentaje")
                ? java.math.BigDecimal.valueOf(((Number) body.get("irpf_porcentaje")).doubleValue())
                : null;

        try {
            Factura factura = facturaService.crearDesdePresupuesto(presupuestoId, usuario, irpf);
            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.getWriter().write(gson.toJson(factura));
        } catch (ServiceException e) {
            int status = e.getMessage().contains("plan PRO")
                    ? HttpServletResponse.SC_FORBIDDEN
                    : HttpServletResponse.SC_BAD_REQUEST;
            enviarError(resp, status, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // PUT — cambio de estado
    // -------------------------------------------------------------------------

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Optional<Usuario> usuarioOpt = resolverUsuario(req);
        if (usuarioOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }
        long usuarioId = usuarioOpt.get().getId();

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || !pathInfo.endsWith("/estado")) {
            enviarError(resp, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Endpoint no soportado");
            return;
        }

        String idPart = pathInfo.replace("/estado", "").substring(1);
        long id;
        try {
            id = Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "ID inválido");
            return;
        }

        Optional<Factura> facturaOpt = facturaDao.findById(id);
        if (facturaOpt.isEmpty() || !facturaOpt.get().getUsuarioId().equals(usuarioId)) {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "No encontrada");
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> body = gson.fromJson(req.getReader(), Map.class);
        String nuevoEstado = body != null ? body.get("estado") : null;

        if (!esEstadoValido(nuevoEstado)) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "Estado inválido: " + nuevoEstado);
            return;
        }

        facturaDao.actualizarEstado(id, nuevoEstado);
        resp.getWriter().write("{\"ok\":true,\"estado\":\"" + nuevoEstado + "\"}");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Optional<Usuario> resolverUsuario(HttpServletRequest req) {
        String header = req.getHeader("X-Telegram-Id");
        if (header == null || header.isBlank()) return Optional.empty();
        try {
            return usuarioDao.findByTelegramId(Long.parseLong(header));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private long parsearId(String pathInfo, HttpServletResponse resp) throws IOException {
        try {
            return Long.parseLong(pathInfo.substring(1));
        } catch (NumberFormatException e) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "ID inválido");
            return -1;
        }
    }

    private boolean esEstadoValido(String estado) {
        return estado != null && List.of(
                Factura.ESTADO_BORRADOR,
                Factura.ESTADO_EMITIDA,
                Factura.ESTADO_PAGADA,
                Factura.ESTADO_ANULADA
        ).contains(estado);
    }

    private void prepararRespuestaJson(HttpServletResponse resp) {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
    }

    private void enviarError(HttpServletResponse resp, int status, String mensaje)
            throws IOException {
        resp.setStatus(status);
        resp.getWriter().write("{\"error\":\"" + mensaje + "\"}");
    }
}