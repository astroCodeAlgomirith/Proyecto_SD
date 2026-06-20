package com.escom.banco.auth;

import org.mindrot.jbcrypt.BCrypt;

/** Hash de contrasenas con BCrypt (reutilizado de la practica 38). */
public class PasswordUtil {
    public static String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt());
    }

    public static boolean verify(String password, String hash) {
        return BCrypt.checkpw(password, hash);
    }
}
