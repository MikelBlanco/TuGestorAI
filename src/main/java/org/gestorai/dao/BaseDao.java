package org.gestorai.dao;

/**
 * Clase base para los DAOs del proyecto.
 */
public abstract class BaseDao {

    protected void validateAutonomoId(long autonomoId) {
        if (autonomoId <= 0) {
            throw new IllegalArgumentException("autonomoId debe ser mayor que 0, recibido: " + autonomoId);
        }
    }
}