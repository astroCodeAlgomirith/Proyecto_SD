package com.escom.banco.auth;

import java.util.concurrent.ConcurrentHashMap;

public class UserRepository {

    private static final UserRepository INSTANCIA = new UserRepository();
    public static UserRepository get() { return INSTANCIA; }

    private final ConcurrentHashMap<String, User> db = new ConcurrentHashMap<>();

    public User findByUsername(String username) { return db.get(username); }

    /** Registra solo si no existe; devuelve true si lo guardo. */
    public boolean saveIfAbsent(User user) {
        return db.putIfAbsent(user.getUsername(), user) == null;
    }
}
