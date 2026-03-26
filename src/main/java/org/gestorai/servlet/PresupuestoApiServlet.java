package org.gestorai.servlet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.filter.AuthFilter;
import org.gestorai.model.Autonomo;
import org.gestorai.model.Presupuesto;
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
 * API REST de presupuestos para el panel web.
 *
 * <pre>
 * GET  /api/presupuestos          → lista presupuestos del usuario autenticado
 * GET  /api/presupuestos/{id}     → detalle con líneas
 * PUT  /api/presupuestos/{id}/estado → cambia estado del presupuesto
 * </pre>
 */
@WebServlet("/api/presupuestos/*")
public class PresupuestoApiServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(PresupuestoApiServlet.class);

    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final AutonomoDao autonomoDao = new AutonomoDao();
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

        Optional<Autonomo> autonomoOpt = resolverAutonomo(req);
        if (autonomoOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }
        long autonomoId = autonomoOpt.get().getId();

        String pathInfo = req.getPathInfo(); // null, "/" o "/{id}"

        if (pathInfo == null || pathInfo.equals("/")) {
            // Listado
            String estado = req.getParameter("estado");
            List<Presupuesto> lista = (estado != null && !estado.isBlank())
                    ? presupuestoDao.findByAutonomoIdAndEstado(autonomoId, estado)
                    : presupuestoDao.findByAutonomoId(autonomoId);
            resp.getWriter().write(gson.toJson(lista));

        } else {
            // Detalle
            long id = parsearId(pathInfo, resp);
            if (id < 0) return;

            presupuestoDao.findById(id).ifPresentOrElse(
                    p -> {
                        if (!p.getAutonomoId().equals(autonomoId)) {
                            try { enviarError(resp, HttpServletResponse.SC_FORBIDDEN, "Sin acceso"); }
                            catch (IOException e) { log.error("Error enviando 403", e); }
                            return;
                        }
                        try { resp.getWriter().write(gson.toJson(p)); }
                        catch (IOException e) { log.error("Error escribiendo respuesta", e); }
                    },
                    () -> {
                        try { enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "No encontrado"); }
                        catch (IOException e) { log.error("Error enviando 404", e); }
                    }
            );
        }
    }

    // -------------------------------------------------------------------------
    // PUT — cambio de estado
    // -------------------------------------------------------------------------

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Optional<Autonomo> autonomoOpt = resolverAutonomo(req);
        if (autonomoOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }
        long autonomoId = autonomoOpt.get().getId();

        // Solo admitimos PUT /api/presupuestos/{id}/estado
        String pathInfo = req.getPathInfo(); // "/{id}/estado"
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

        // Verificar pertenencia
        Optional<Presupuesto> presupuestoOpt = presupuestoDao.findById(id);
        if (presupuestoOpt.isEmpty() || !presupuestoOpt.get().getAutonomoId().equals(autonomoId)) {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "No encontrado");
            return;
        }

        // Leer nuevo estado del body JSON: {"estado": "aceptado"}
        @SuppressWarnings("unchecked")
        Map<String, String> body = gson.fromJson(req.getReader(), Map.class);
        String nuevoEstado = body != null ? body.get("estado") : null;

        if (!esEstadoValido(nuevoEstado)) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "Estado inválido: " + nuevoEstado);
            return;
        }

        presupuestoDao.actualizarEstado(id, nuevoEstado);
        resp.getWriter().write("{\"ok\":true,\"estado\":\"" + nuevoEstado + "\"}");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Resuelve el autónomo a partir del {@code autonomo_id} propagado por {@link AuthFilter}.
     */
    private Optional<Autonomo> resolverAutonomo(HttpServletRequest req) {
        Long autonomoId = (Long) req.getAttribute(AuthFilter.REQUEST_AUTONOMO_ID);
        if (autonomoId == null) return Optional.empty();
        return autonomoDao.findById(autonomoId);
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
                Presupuesto.ESTADO_BORRADOR,
                Presupuesto.ESTADO_ENVIADO,
                Presupuesto.ESTADO_ACEPTADO,
                Presupuesto.ESTADO_RECHAZADO
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