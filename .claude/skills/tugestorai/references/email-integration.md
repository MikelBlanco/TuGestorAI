# Envío de Email

## Dependencia Maven

Jakarta Mail (compatible con Tomcat 10):
```xml
<dependency>
    <groupId>org.eclipse.angus</groupId>
    <artifactId>angus-mail</artifactId>
    <version>2.0.3</version>
</dependency>
```

## EmailService
```java
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    public void enviarPresupuesto(String destinatario, File pdf, Presupuesto presupuesto) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", ConfigUtil.get("mail.smtp.host"));
            props.put("mail.smtp.port", ConfigUtil.get("mail.smtp.port", "587"));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                        ConfigUtil.get("mail.user"),
                        ConfigUtil.get("mail.password")
                    );
                }
            });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(ConfigUtil.get("mail.from")));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(destinatario));
            message.setSubject("Presupuesto " + presupuesto.getNumero());

            // Cuerpo + adjunto
            MimeMultipart multipart = new MimeMultipart();

            MimeBodyPart texto = new MimeBodyPart();
            texto.setText("Adjunto el presupuesto " + presupuesto.getNumero()
                + " generado con TuGestorAI.", "UTF-8");
            multipart.addBodyPart(texto);

            MimeBodyPart adjunto = new MimeBodyPart();
            adjunto.attachFile(pdf);
            adjunto.setFileName(pdf.getName());
            multipart.addBodyPart(adjunto);

            message.setContent(multipart);
            Transport.send(message);

            log.info("Email enviado a {} con presupuesto {}", destinatario, presupuesto.getNumero());
        } catch (Exception e) {
            throw new ServiceException("Error enviando email", e);
        }
    }
}
```

## Configuración en config.properties
```properties
mail.smtp.host=smtp.gmail.com
mail.smtp.port=587
mail.user=tu_email@gmail.com
mail.password=tu_app_password
mail.from=tu_email@gmail.com
```

Para Gmail usar una "App Password" (no la contraseña normal).

## Consideraciones

- El email del autónomo se configura en su perfil (`usuarios.email`)
- En el futuro, también se podrá enviar directamente al cliente
- Usar envío asíncrono para no bloquear la respuesta del bot