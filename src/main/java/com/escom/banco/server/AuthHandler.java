package com.escom.banco.server;

import com.escom.banco.auth.AuthService;
import com.escom.banco.json.Json;
import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;
import com.google.gson.JsonObject;

/**
 * POST /api/register y POST /api/login. Va al pool WORKER porque AuthService usa
 * bcrypt (~50-100ms): correrlo en un reactor estancaria todas sus conexiones.
 */
public class AuthHandler implements Manejador {

    private final AuthService auth = new AuthService();

    @Override
    public Respuesta manejar(Solicitud s) {
        if (!"POST".equalsIgnoreCase(s.metodo())) return Respuesta.sinCuerpo(405);
        String path = s.path();
        if ("/api/register".equals(path)) return registrar(s);
        if ("/api/login".equals(path)) return login(s);
        return Respuesta.error(404, "Recurso no encontrado");
    }

    private Respuesta registrar(Solicitud s) {
        JsonObject in = parse(s);
        if (in == null) return Respuesta.error(400, "JSON invalido");
        if (!in.has("username") || !in.has("password")) {
            return Respuesta.error(400, "Faltan username/password");
        }
        boolean creado = auth.register(in.get("username").getAsString(),
                                       in.get("password").getAsString());
        if (creado) return Respuesta.json(201, "{\"status\":\"registrado\"}");
        return Respuesta.error(409, "Usuario ya existe");
    }

    private Respuesta login(Solicitud s) {
        JsonObject in = parse(s);
        if (in == null) return Respuesta.error(400, "JSON invalido");
        if (!in.has("username") || !in.has("password")) {
            return Respuesta.error(400, "Faltan username/password");
        }
        String token = auth.login(in.get("username").getAsString(),
                                  in.get("password").getAsString());
        if (token != null) return Respuesta.json(200, "{\"token\":\"" + token + "\"}");
        return Respuesta.error(401, "Credenciales invalidas");
    }

    private static JsonObject parse(Solicitud s) {
        try { return Json.parse(s.bodyTexto()); }
        catch (Exception e) { return null; }
    }
}
