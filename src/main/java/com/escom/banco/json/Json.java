package com.escom.banco.json;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** gson centralizado (reemplaza el parser casero de la practica 38). */
public final class Json {
    public static final Gson GSON = new Gson();

    private Json() {}

    public static JsonObject parse(String body) {
        return JsonParser.parseString(body).getAsJsonObject();
    }

    public static String toJson(Object o) {
        return GSON.toJson(o);
    }
}
