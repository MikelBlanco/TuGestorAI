package org.gestorai.filter;

import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Filtro de autenticación para el panel web.
 *
 * <p>Protege todas las rutas {@code /api/*} excepto {@code /api/auth/*} (login/logout/me)
 * y {@code /health} (monitorización).
 *
 * <p>Comprueba que existe una sesión HTTP activa con el atributo {@code autonomo_id}.
 * Si la sesión es válida, propaga el {@code autonomo_id} como atributo de la request
 * para que los servlets lo lean sin tocar la sesión directamente.
 *
 * <p>Si no hay sesión válida, devuelve HTTP 401 con JSON de error.
 */
@WebFilter("/api/*")
public class AuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    /** Nombre del atributo de sesión donde se almacena el ID del autónomo autenticado. */
    public static final String SESSION_AUTONOMO_ID = "autonomo_id";

    /** Nombre del atributo de request que los servlets deben leer. */
    public static final String REQUEST_AUTONOMO_ID = "autonomo_id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // Rutas públicas: login, logout, me
        String path = req.getServletPath();
        if (path != null && path.startsWith("/api/auth")) {
            chain.doFilter(request, response);
            return;
        }

        // Verificar sesión
        HttpSession session = req.getSession(false);
        if (session == null) {
            rechazar(resp, "Sesión no iniciada");
            return;
        }

        Long autonomoId = (Long) session.getAttribute(SESSION_AUTONOMO_ID);
        if (autonomoId == null || autonomoId <= 0) {
            rechazar(resp, "No autenticado");
            return;
        }

        // Propagar el autonomoId a los servlets como atributo de request
        req.setAttribute(REQUEST_AUTONOMO_ID, autonomoId);
        chain.doFilter(request, response);
    }

    private void rechazar(HttpServletResponse resp, String mensaje) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write("{\"error\":\"" + mensaje + "\"}");
        log.debug("Acceso denegado: {}", mensaje);
    }
}
