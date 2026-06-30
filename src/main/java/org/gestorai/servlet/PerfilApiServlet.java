package org.gestorai.servlet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSerializer;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.filter.AuthFilter;
import org.gestorai.model.Autonomo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * API REST para la gestión del perfil del autónomo.
 *
 * <pre>
 * GET  /api/perfil     → obtiene el perfil completo del autónomo autenticado
 * PUT  /api/perfil     → actualiza los datos fiscales del autónomo autenticado
 * </pre>
 */
@WebServlet("/api/perfil")
public class PerfilApiServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(PerfilApiServlet.class);

    private final AutonomoDao autonomoDao = new AutonomoDao();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class,
                    (JsonSerializer<LocalDateTime>) (src, type, ctx) ->
                            ctx.serialize(src.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .create();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Long autonomoId = (Long) req.getAttribute(AuthFilter.REQUEST_AUTONOMO_ID);
        if (autonomoId == null) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }

        Optional<Autonomo> autonomoOpt = autonomoDao.findById(autonomoId);
        if (autonomoOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "Autónomo no encontrado");
            return;
        }

        Autonomo a = autonomoOpt.get();
        int presupuestosMes = presupuestoDao.contarPorAutonomoEnMes(autonomoId);
        a.setPresupuestosMes(presupuestosMes);

        resp.getWriter().write(gson.toJson(a));
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        Long autonomoId = (Long) req.getAttribute(AuthFilter.REQUEST_AUTONOMO_ID);
        if (autonomoId == null) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }

        Optional<Autonomo> autonomoOpt = autonomoDao.findById(autonomoId);
        if (autonomoOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "Autónomo no encontrado");
            return;
        }

        Autonomo a = autonomoOpt.get();

        try {
            Autonomo datosInput = gson.fromJson(req.getReader(), Autonomo.class);
            if (datosInput == null) {
                enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "Cuerpo de petición inválido o vacío");
                return;
            }

            // Validar campos obligatorios
            if (datosInput.getNombre() == null || datosInput.getNombre().trim().isBlank()) {
                enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "El nombre es obligatorio");
                return;
            }

            // Validar formato del email
            if (datosInput.getEmail() != null && !datosInput.getEmail().trim().isEmpty() &&
                    !datosInput.getEmail().trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "El email no tiene un formato válido");
                return;
            }

            // Modificar los campos permitidos del autónomo
            a.setNombre(datosInput.getNombre().trim());
            a.setNombreComercial(datosInput.getNombreComercial() != null ? datosInput.getNombreComercial().trim() : null);
            a.setNif(datosInput.getNif() != null ? datosInput.getNif().trim() : null);
            a.setDireccion(datosInput.getDireccion() != null ? datosInput.getDireccion().trim() : null);
            a.setTelefono(datosInput.getTelefono() != null ? datosInput.getTelefono().trim() : null);
            a.setEmail(datosInput.getEmail() != null ? datosInput.getEmail().trim() : null);

            autonomoDao.actualizarPerfil(a);

            // Obtener el objeto actualizado para retornar
            int presupuestosMes = presupuestoDao.contarPorAutonomoEnMes(autonomoId);
            a.setPresupuestosMes(presupuestosMes);

            resp.getWriter().write(gson.toJson(a));
        } catch (Exception e) {
            log.error("Error al actualizar perfil de autónomo id={}", autonomoId, e);
            enviarError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error interno al actualizar el perfil");
        }
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
