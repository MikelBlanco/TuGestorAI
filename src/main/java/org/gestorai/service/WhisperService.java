package org.gestorai.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gestorai.exception.ServiceException;
import org.gestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Transcripción de audio mediante Whisper API (OpenAI).
 * Usa {@code java.net.http.HttpClient} sin dependencias adicionales.
 */
public class WhisperService {

    private static final Logger log = LoggerFactory.getLogger(WhisperService.class);

    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final long TAMANO_MINIMO_BYTES = 1024; // 1 KB — descartar audios vacíos/corruptos

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiKey = ConfigUtil.get("openai.api.key");

    /**
     * Transcribe un fichero de audio OGG (Telegram) a texto en español.
     *
     * @param audioFile fichero de audio descargado de Telegram
     * @return texto transcrito
     * @throws ServiceException si el audio es inválido o la API devuelve error
     */
    public String transcribe(File audioFile) {
        validarAudio(audioFile);

        String boundary = "----TuGestorBoundary" + System.currentTimeMillis();

        try {
            byte[] body = construirMultipart(boundary, audioFile);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WHISPER_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Whisper API error {}: {}", response.statusCode(), response.body());
                throw new ServiceException("Error en Whisper API: HTTP " + response.statusCode());
            }

            String texto = extraerTexto(response.body());

            if (texto.isBlank()) {
                throw new ServiceException("Whisper no pudo transcribir el audio (respuesta vacía). " +
                        "Prueba a hablar más cerca del micrófono.");
            }

            log.info("Audio transcrito ({} bytes) → {} caracteres", audioFile.length(), texto.length());
            return texto;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ServiceException("Error comunicando con Whisper API", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private void validarAudio(File audioFile) {
        if (audioFile == null || !audioFile.exists()) {
            throw new ServiceException("El fichero de audio no existe");
        }
        if (audioFile.length() < TAMANO_MINIMO_BYTES) {
            throw new ServiceException("El audio es demasiado corto o está vacío");
        }
    }

    private String extraerTexto(String responseBody) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        return json.get("text").getAsString().trim();
    }

    /**
     * Construye el cuerpo multipart/form-data con los campos requeridos por Whisper API.
     */
    private byte[] construirMultipart(String boundary, File audioFile) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Campo: file
        escribirCampoFichero(baos, boundary, audioFile);

        // Campo: model
        escribirCampoTexto(baos, boundary, "model", "whisper-1");

        // Campo: language — forzar español para mayor precisión
        escribirCampoTexto(baos, boundary, "language", "es");

        // Campo: prompt — vocabulario del dominio para mejorar reconocimiento
        escribirCampoTexto(baos, boundary, "prompt",
                "Presupuesto, factura, material, mano de obra, IVA, cliente, " +
                "instalación, reparación, fontanería, electricidad, euros");

        // Cierre del multipart
        baos.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return baos.toByteArray();
    }

    private void escribirCampoFichero(ByteArrayOutputStream baos, String boundary, File file)
            throws IOException {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"audio.ogg\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        baos.write("Content-Type: audio/ogg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        baos.write(Files.readAllBytes(file.toPath()));
        baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void escribirCampoTexto(ByteArrayOutputStream baos, String boundary,
                                    String nombre, String valor) throws IOException {
        baos.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        baos.write(("Content-Disposition: form-data; name=\"" + nombre + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        baos.write((valor + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}