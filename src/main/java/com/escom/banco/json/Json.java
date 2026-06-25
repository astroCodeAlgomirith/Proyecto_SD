package com.escom.banco.json;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Utilidades JSON centralizadas sobre gson. */
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
