package org.gestorai.bot.session;

/**
 * Estados posibles de una sesión conversacional con el bot.
 */
public enum SessionState {

    /** Sin conversación activa. Estado por defecto. */
    IDLE,

    /** Se ha presentado un borrador de presupuesto y se espera confirmación. */
    ESPERANDO_CONFIRMACION,

    /** El usuario está editando un campo del presupuesto. */
    EDITANDO,

    /** El presupuesto ha sido confirmado y se espera que el usuario elija cómo recibir los documentos. */
    ESPERANDO_OPCION_ENVIO,

    /** El usuario debe aceptar el aviso de privacidad (RGPD) antes de registrarse. */
    PENDIENTE_CONSENTIMIENTO,

    /** Se está recogiendo el nombre del cliente durante /start. */
    REGISTRO_NOMBRE,

    /** Se está recogiendo el NIF durante /start. */
    REGISTRO_NIF,

    /** Se está recogiendo la dirección durante /start. */
    REGISTRO_DIRECCION
}