package com.escom.banco.server;

import com.escom.banco.auth.AuthService;
import com.escom.banco.json.Json;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** POST /api/register y POST /api/login. */
public class AuthHandler implements HttpHandler {

    private final AuthService auth = new AuthService();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        if ("/api/register".equals(path)) registrar(ex);
        else if ("/api/login".equals(path)) login(ex);
        else HttpUtil.error(ex, 404, "Recurso no encontrado");
    }

    private void registrar(HttpExchange ex) throws IOException {
        JsonObject in;
        try { in = Json.parse(HttpUtil.leerBody(ex)); }
        catch (Exception e) { HttpUtil.error(ex, 400, "JSON invalido"); return; }

        if (!in.has("username") || !in.has("password")) {
            HttpUtil.error(ex, 400, "Faltan username/password"); return;
        }
        boolean creado = auth.register(in.get("username").getAsString(),
                                       in.get("password").getAsString());
        if (creado) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("status", "registrado");
            HttpUtil.enviarJson(ex, 201, Json.toJson(m));
        } else {
            HttpUtil.error(ex, 409, "Usuario ya existe");
        }
    }

    private void login(HttpExchange ex) throws IOException {
        JsonObject in;
        try { in = Json.parse(HttpUtil.leerBody(ex)); }
        catch (Exception e) { HttpUtil.error(ex, 400, "JSON invalido"); return; }

        if (!in.has("username") || !in.has("password")) {
            HttpUtil.error(ex, 400, "Faltan username/password"); return;
        }
        String token = auth.login(in.get("username").getAsString(),
                                  in.get("password").getAsString());
        if (token != null) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("token", token);
            HttpUtil.enviarJson(ex, 200, Json.toJson(m));
        } else {
            HttpUtil.error(ex, 401, "Credenciales invalidas");
        }
    }
}
