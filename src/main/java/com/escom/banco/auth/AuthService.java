package com.escom.banco.auth;

public class AuthService {

    private final UserRepository repo = UserRepository.get();

    /** Devuelve true si registro un usuario nuevo, false si ya existia. */
    public boolean register(String username, String password) {
        String hash = PasswordUtil.hash(password);
        return repo.saveIfAbsent(new User(username, hash));
    }

    /** Devuelve el JWT si las credenciales son validas, null si no. */
    public String login(String username, String password) {
        User u = repo.findByUsername(username);
        if (u == null) return null;
        if (!PasswordUtil.verify(password, u.getPasswordHash())) return null;
        return JWTUtil.generateToken(username);
    }
}
