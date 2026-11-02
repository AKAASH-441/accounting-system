package com.accounting.dao;

import java.sql.SQLException;

/** Unchecked wrapper so UI code isn't cluttered with try/catch for SQLException. */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, SQLException cause) {
        super(message + ": " + cause.getMessage(), cause);
    }
}
