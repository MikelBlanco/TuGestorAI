package org.gestorai.service;

import org.gestorai.exception.ServiceException;
import org.gestorai.util.ConfigUtil;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import jakarta.activation.DataHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Envío de emails con adjuntos mediante Jakarta Mail (angus-mail).
 *
 * <p>Los PDFs se reciben en memoria ({@code byte[]}) y se adjuntan sin escribirlos en disco.</p>
 *
 * <p>Configuración vía {@code config.properties}:</p>
 * <pre>
 *   email.smtp.host     — servidor SMTP (ej: smtp.gmail.com)
 *   email.smtp.port     — puerto SMTP (587 para STARTTLS, 465 para SSL)
 *   email.smtp.user     — usuario SMTP
 *   email.smtp.password — contraseña SMTP
 *   email.from          — dirección "From" (opcional, usa smtp.user si está vacío)
 * </pre>
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final String smtpHost     = ConfigUtil.get("email.smtp.host");
    private final String smtpPort     = ConfigUtil.get("email.smtp.port");
    private final String smtpUser     = ConfigUtil.get("email.smtp.user");
    private final String smtpPassword = ConfigUtil.get("email.smtp.password");
    private final String emailFrom    = ConfigUtil.get("email.from");

    /**
     * Envía un PDF en memoria por email al destinatario indicado.
     *
     * @param destinatario  dirección de correo electrónico del destinatario
     * @param asunto        asunto del mensaje
     * @param cuerpo        cuerpo del mensaje en texto plano (UTF-8)
     * @param pdfBytes      bytes del PDF a adjuntar
     * @param nombreFichero nombre del fichero adjunto (ej: {@code presupuesto_P-2026-0001.pdf})
     * @throws ServiceException si la configuración SMTP es incorrecta o hay error de envío
     */
    public void enviarConAdjunto(String destinatario, String asunto,
                                  String cuerpo, byte[] pdfBytes, String nombreFichero) {
        validarConfiguracion();

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort != null && !smtpPort.isBlank() ? smtpPort : "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUser, smtpPassword);
            }
        });

        try {
            MimeMessage mensaje = new MimeMessage(session);
            String remitente = (emailFrom != null && !emailFrom.isBlank()) ? emailFrom : smtpUser;
            mensaje.setFrom(new InternetAddress(remitente));
            mensaje.setRecipients(Message.RecipientType.TO, InternetAddress.parse(destinatario));
            mensaje.setSubject(asunto, "UTF-8");

            // Parte de texto
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(cuerpo, "UTF-8");

            // Parte del adjunto PDF (desde memoria, sin tocar disco)
            MimeBodyPart adjuntoPart = new MimeBodyPart();
            adjuntoPart.setDataHandler(
                    new DataHandler(new ByteArrayDataSource(pdfBytes, "application/pdf")));
            adjuntoPart.setFileName(nombreFichero);

            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);
            multipart.addBodyPart(adjuntoPart);
            mensaje.setContent(multipart);

            Transport.send(mensaje);
            log.info("Email enviado a {} con adjunto {}", destinatario, nombreFichero);

        } catch (Exception e) {
            throw new ServiceException("Error enviando email a " + destinatario, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validarConfiguracion() {
        if (smtpHost == null || smtpHost.isBlank()) {
            throw new ServiceException(
                    "El servidor SMTP no está configurado. " +
                    "Añade email.smtp.host en config.properties o como variable de entorno.");
        }
        if (smtpUser == null || smtpUser.isBlank()) {
            throw new ServiceException(
                    "El usuario SMTP no está configurado (email.smtp.user).");
        }
    }
}
