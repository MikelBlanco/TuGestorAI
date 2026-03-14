package org.gestorai.servlet;

import org.gestorai.util.DbUtil;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;

/**
 * Endpoint de health check para el balanceador / monitorización.
 * GET /health → 200 OK si la app y la BD están operativas, 503 si no.
 */
@WebServlet("/health")
public class HealthServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(HealthServlet.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        boolean dbOk = comprobarBd();

        if (dbOk) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write("{\"status\":\"ok\",\"db\":\"ok\"}");
        } else {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write("{\"status\":\"error\",\"db\":\"unreachable\"}");
        }
    }

    private boolean comprobarBd() {
        try (Connection conn = DbUtil.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            log.warn("Health check BD fallido: {}", e.getMessage());
            return false;
        }
    }
}