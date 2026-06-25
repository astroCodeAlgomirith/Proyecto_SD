package com.escom.banco.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.exceptions.JWTVerificationException;

/**
 * Emision y verificacion de JWT con HMAC256.
 * El SECRET debe ser EL MISMO en los 3 nodos para que un token emitido por el
 * lider valide en las replicas -> se toma de la variable de entorno.
 */
public class JWTUtil {

    private static final String SECRET =
            System.getenv().getOrDefault("BANCO_JWT_SECRET", "mi_clave_secreta_super_segura");
    private static final Algorithm ALGO = Algorithm.HMAC256(SECRET);
    private static final String ISSUER = "banco-sd";

    public static String generateToken(String username) {
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(username)
                .sign(ALGO);
    }

    public static DecodedJWT verify(String token) throws JWTVerificationException {
        return JWT.require(ALGO)
                .withIssuer(ISSUER)
                .build()
                .verify(token);
    }
}
