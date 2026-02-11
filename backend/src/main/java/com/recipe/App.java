package com.recipe;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class App {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Connection conn;

    public static void main(String[] args) throws Exception {
        // init sqlite
        initDb();

    Javalin app = Javalin.create().start(7001);

        // simple CORS allow for local development
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
        });

        app.get("/recipes", ctx -> {
            List<Recipe> all = RecipeRepository.getAll();
            ctx.json(all);
        });

        app.post("/recipes", ctx -> {
            Recipe r = mapper.readValue(ctx.body(), Recipe.class);
            Recipe created = RecipeRepository.create(r);
            ctx.status(201).json(created);
        });

        app.put("/recipes/:id", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            Recipe r = mapper.readValue(ctx.body(), Recipe.class);
            r.id = id;
            boolean ok = RecipeRepository.update(r);
            if (ok) ctx.status(200).json(r);
            else ctx.status(404).result("Not found");
        });

        app.delete("/recipes/:id", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            boolean ok = RecipeRepository.delete(id);
            if (ok) ctx.status(204);
            else ctx.status(404).result("Not found");
        });

        app.get("/", ctx -> ctx.result("Recipe backend running"));
    }

    private static void initDb() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:recipes.db");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS recipes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, ingredients TEXT, instructions TEXT)");
        }
        RecipeRepository.setConnection(conn);
    }
}
