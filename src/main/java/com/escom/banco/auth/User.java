package com.escom.banco.auth;

/** Credencial de acceso (compuerta JWT), independiente de las 820k cuentas. */
public class User {
    private final String username;
    private final String passwordHash;

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
}
