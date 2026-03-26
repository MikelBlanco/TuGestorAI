package org.gestorai.servlet;

import com.google.gson.Gson;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.filter.AuthFilter;
import org.gestorai.model.Autonomo;
import org.gestorai.util.TokenStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Endpoints de autenticación para el panel web.
 *
 * <pre>
 * POST /api/auth/login   — valida token de bot, crea sesión HTTP
 * POST /api/auth/logout  — invalida sesión
 * GET  /api/auth/me      — devuelve el autónomo de la sesión activa
 * </pre>
 *
 * El flujo de login es:
 * <ol>
 *   <li>El autónomo usa /panel en el bot → recibe un token de 10 min.</li>
 *   <li>El frontend envía {@code POST /api/auth/login} con {@code {"token": "abc123"}}.</li>
 *   <li>Si el token es válido, se crea una sesión HTTP con el autonomo_id.</li>
 *   <li>El frontend recibe los datos del autónomo y navega al panel.</li>
 * </ol>
 */
@WebServlet("/api/auth/*")
public class LoginApiServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(LoginApiServlet.class);

    private final TokenStore tokenStore = TokenStore.getInstance();
    private final AutonomoDao autonomoDao = new AutonomoDao();
    private final Gson gson = new Gson();

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        String pathInfo = req.getPathInfo(); // "/login" o "/logout"

        if ("/login".equals(pathInfo)) {
            procesarLogin(req, resp);
        } else if ("/logout".equals(pathInfo)) {
            procesarLogout(req, resp);
        } else {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "Endpoint no encontrado");
        }
    }

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {
        prepararRespuestaJson(resp);

        String pathInfo = req.getPathInfo();

        if ("/me".equals(pathInfo)) {
            procesarMe(req, resp);
        } else {
            enviarError(resp, HttpServletResponse.SC_NOT_FOUND, "Endpoint no encontrado");
        }
    }

    // -------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------

    private void procesarLogin(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, String> body = gson.fromJson(req.getReader(), Map.class);

        if (body == null || body.get("token") == null || body.get("token").isBlank()) {
            enviarError(resp, HttpServletResponse.SC_BAD_REQUEST, "Falta el token");
            return;
        }

        String token = body.get("token").trim();
        Optional<Long> autonomoIdOpt = tokenStore.validarToken(token);

        if (autonomoIdOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Token inválido o expirado");
            return;
        }

        long autonomoId = autonomoIdOpt.get();
        Optional<Autonomo> autonomoOpt = autonomoDao.findById(autonomoId);

        if (autonomoOpt.isEmpty()) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Autónomo no encontrado");
            return;
        }

        Autonomo autonomo = autonomoOpt.get();

        // Crear sesión nueva (protección contra session fixation)
        HttpSession session = req.getSession(false);
        if (session != null) session.invalidate();
        session = req.getSession(true);
        session.setAttribute(AuthFilter.SESSION_AUTONOMO_ID, autonomo.getId());

        log.info("Login correcto para autonomoId={}", autonomo.getId());
        resp.getWriter().write(gson.toJson(autonomoPublico(autonomo)));
    }

    private void procesarLogout(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        HttpSession session = req.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        resp.getWriter().write("{\"ok\":true}");
    }

    private void procesarMe(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }

        Long autonomoId = (Long) session.getAttribute(AuthFilter.SESSION_AUTONOMO_ID);
        if (autonomoId == null) {
            enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "No autenticado");
            return;
        }

        autonomoDao.findById(autonomoId).ifPresentOrElse(
                a -> {
                    try { resp.getWriter().write(gson.toJson(autonomoPublico(a))); }
                    catch (IOException e) { log.error("Error escribiendo respuesta /me", e); }
                },
                () -> {
                    try { enviarError(resp, HttpServletResponse.SC_UNAUTHORIZED, "Autónomo no encontrado"); }
                    catch (IOException e) { log.error("Error enviando 401 en /me", e); }
                }
        );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Devuelve solo los campos no sensibles del autónomo para enviar al frontend.
     * NUNCA incluir NIF, dirección, email ni teléfono en respuestas del panel.
     */
    private Map<String, Object> autonomoPublico(Autonomo a) {
        return Map.of(
                "id", a.getId(),
                "nombre", a.getNombre() != null ? a.getNombre() : "",
                "plan", a.getPlan() != null ? a.getPlan() : "free"
        );
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
