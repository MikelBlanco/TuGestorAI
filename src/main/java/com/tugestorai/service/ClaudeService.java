package com.tugestorai.service;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.tugestorai.exception.ServiceException;
import com.tugestorai.model.DatosPresupuesto;
import com.tugestorai.model.LineaDetalle;
import com.tugestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Estructuración de datos de presupuesto mediante Claude API (Haiku).
 * Transforma texto libre en español a un {@link DatosPresupuesto} estructurado.
 */
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODELO  = "claude-haiku-4-5-20251001";

    private static final String SYSTEM_PROMPT = """
            Eres un asistente que extrae datos de presupuestos a partir de texto dictado \
            por autónomos españoles. El texto viene de transcripción de voz, así que puede \
            contener errores o estar poco estructurado.

            Extrae los datos y responde SOLO con un JSON válido con esta estructura exacta:
            {
              "cliente_nombre": "string",
              "cliente_telefono": "string o null",
              "cliente_email": "string o null",
              "descripcion_general": "string - descripción breve del trabajo",
              "lineas": [
                {
                  "concepto": "string",
                  "tipo": "material | mano_obra | servicio",
                  "cantidad": number,
                  "precio_unitario": number
                }
              ],
              "notas": "string o null"
            }

            Reglas:
            - Si no se especifica cantidad, asumir 1
            - Separar siempre material de mano de obra si se mencionan ambos
            - Los precios son sin IVA salvo que se diga explícitamente "con IVA"
            - Si falta algún dato, poner null
            - NO añadir campos extra, solo los indicados
            - Responde ÚNICAMENTE con el JSON, sin texto adicional ni bloques de código
            """;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final Gson gson = new Gson();
    private final String apiKey = ConfigUtil.get("anthropic.api.key");

    /**
     * Analiza la transcripción de un audio y extrae los datos del presupuesto.
     * Reintenta una vez si el JSON de respuesta no es válido.
     *
     * @param transcripcion texto libre en español obtenido de Whisper
     * @return datos estructurados listos para crear el borrador
     * @throws ServiceException si la API falla o el JSON sigue siendo inválido tras el reintento
     */
    public DatosPresupuesto parsePresupuesto(String transcripcion) {
        String respuestaJson = llamarApi(transcripcion);
        try {
            return convertir(respuestaJson);
        } catch (JsonParseException e) {
            log.warn("JSON inválido en primer intento, reintentando. Respuesta: {}", respuestaJson);
            // Reintento único
            respuestaJson = llamarApi(transcripcion);
            try {
                return convertir(respuestaJson);
            } catch (JsonParseException e2) {
                log.error("JSON inválido tras reintento: {}", respuestaJson);
                throw new ServiceException(
                        "No pude interpretar los datos del presupuesto. Intenta enviar el audio de nuevo.");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private String llamarApi(String transcripcion) {
        String requestBody = gson.toJson(Map.of(
                "model", MODELO,
                "max_tokens", 1024,
                "system", SYSTEM_PROMPT,
                "messages", List.of(
                        Map.of("role", "user", "content",
                                "Transcripción del audio del autónomo:\n\n" + transcripcion)
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Claude API error {}: {}", response.statusCode(), response.body());
                throw new ServiceException("Error procesando el presupuesto con IA: HTTP " +
                        response.statusCode());
            }

            // Extraer el texto de la respuesta: content[0].text
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String texto = json.getAsJsonArray("content")
                    .get(0).getAsJsonObject()
                    .get("text").getAsString();

            log.debug("Respuesta Claude: {}", texto);
            return limpiarJson(texto);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ServiceException("Error comunicando con Claude API", e);
        }
    }

    /**
     * Elimina posibles bloques de código markdown que Claude añada a veces
     * a pesar del prompt (```json ... ```).
     */
    private String limpiarJson(String respuesta) {
        String limpio = respuesta.trim();
        if (limpio.startsWith("```json")) {
            limpio = limpio.substring(7);
        } else if (limpio.startsWith("```")) {
            limpio = limpio.substring(3);
        }
        if (limpio.endsWith("```")) {
            limpio = limpio.substring(0, limpio.length() - 3);
        }
        return limpio.trim();
    }

    /**
     * Convierte el JSON de Claude al DTO de dominio.
     * Usa una clase interna para mapear los nombres snake_case de Claude.
     */
    private DatosPresupuesto convertir(String json) {
        RespuestaClaude raw = gson.fromJson(json, RespuestaClaude.class);

        DatosPresupuesto datos = new DatosPresupuesto();
        datos.setClienteNombre(raw.clienteNombre);
        datos.setClienteTelefono(raw.clienteTelefono);
        datos.setClienteEmail(raw.clienteEmail);
        datos.setDescripcion(raw.descripcionGeneral);
        datos.setNotas(raw.notas);

        if (raw.lineas != null) {
            for (int i = 0; i < raw.lineas.size(); i++) {
                RespuestaClaude.LineaRaw l = raw.lineas.get(i);
                LineaDetalle linea = new LineaDetalle();
                linea.setConcepto(l.concepto);
                linea.setTipo(normalizarTipo(l.tipo));
                linea.setCantidad(BigDecimal.valueOf(l.cantidad > 0 ? l.cantidad : 1));
                linea.setPrecioUnitario(BigDecimal.valueOf(l.precioUnitario));
                linea.setImporte(linea.getCantidad().multiply(linea.getPrecioUnitario()));
                linea.setOrden(i);
                datos.getLineas().add(linea);
            }
        }

        log.info("Presupuesto estructurado: cliente='{}', {} líneas, total={}",
                datos.getClienteNombre(), datos.getLineas().size(), datos.calcularTotal());
        return datos;
    }

    /**
     * Normaliza el tipo que devuelve Claude a las constantes de {@link LineaDetalle}.
     * {@code mano_obra} se trata como servicio (no existe tipo específico en el modelo).
     */
    private String normalizarTipo(String tipo) {
        if (tipo == null) return LineaDetalle.TIPO_SERVICIO;
        return switch (tipo.toLowerCase()) {
            case "material"  -> LineaDetalle.TIPO_MATERIAL;
            default          -> LineaDetalle.TIPO_SERVICIO;
        };
    }

    // -------------------------------------------------------------------------
    // DTO interno para mapear el JSON de Claude (snake_case → camelCase)
    // -------------------------------------------------------------------------

    private static class RespuestaClaude {
        @SerializedName("cliente_nombre")    String clienteNombre;
        @SerializedName("cliente_telefono")  String clienteTelefono;
        @SerializedName("cliente_email")     String clienteEmail;
        @SerializedName("descripcion_general") String descripcionGeneral;
        @SerializedName("notas")             String notas;
        @SerializedName("lineas")            List<LineaRaw> lineas;

        private static class LineaRaw {
            @SerializedName("concepto")         String concepto;
            @SerializedName("tipo")             String tipo;
            @SerializedName("cantidad")         double cantidad;
            @SerializedName("precio_unitario")  double precioUnitario;
        }
    }
}