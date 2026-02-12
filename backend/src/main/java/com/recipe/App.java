package com.recipe;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.List;
import io.javalin.http.UploadedFile;

public class App {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Connection conn;

    public static void main(String[] args) throws Exception {
        // init sqlite
        initDb();

    Javalin app = Javalin.create().start(7001);

        // serve uploaded files from /uploads folder
        app.get("/uploads/{file}", ctx -> {
            String file = ctx.pathParam("file");
            java.nio.file.Path p = java.nio.file.Paths.get("uploads", file).toAbsolutePath();
            if (java.nio.file.Files.exists(p)) {
                ctx.contentType(java.nio.file.Files.probeContentType(p));
                ctx.result(new java.io.FileInputStream(p.toFile()));
            } else {
                ctx.status(404).result("Not found");
            }
        });

        // simple CORS allow for local development
        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
        });

        // respond to preflight CORS requests
        app.options("/*", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
            ctx.status(200);
        });

        app.get("/recipes", ctx -> {
            List<Recipe> all = RecipeRepository.getAll();
            ctx.json(all);
        });

        app.post("/recipes", ctx -> {
            // accept multipart/form-data with optional 'image' file and form fields
            String title = ctx.formParam("title");
            String ingredients = ctx.formParam("ingredients");
            String instructions = ctx.formParam("instructions");
            String imagePath = null;
            var uploaded = ctx.uploadedFiles("image");
            if (uploaded != null && !uploaded.isEmpty()) {
                UploadedFile file = uploaded.get(0);
                java.nio.file.Path uploadsDir = java.nio.file.Paths.get("uploads");
                if (!java.nio.file.Files.exists(uploadsDir)) java.nio.file.Files.createDirectories(uploadsDir);
                String fileName = System.currentTimeMillis() + "-" + file.filename();
                java.nio.file.Path dest = uploadsDir.resolve(fileName);
                try (java.io.InputStream in = file.content()) {
                    java.nio.file.Files.copy(in, dest);
                }
                imagePath = "/uploads/" + fileName;
            }
            Recipe r = new Recipe();
            r.title = title;
            r.ingredients = ingredients;
            r.instructions = instructions;
            r.image = imagePath;
            Recipe created = RecipeRepository.create(r);
            ctx.status(201).json(created);
        });

        // support multipart PUT as well for updating image
        app.put("/recipes/{id}", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            String title = ctx.formParam("title");
            String ingredients = ctx.formParam("ingredients");
            String instructions = ctx.formParam("instructions");
            String imagePath = null;
            var uploaded = ctx.uploadedFiles("image");
            if (uploaded != null && !uploaded.isEmpty()) {
                UploadedFile file = uploaded.get(0);
                java.nio.file.Path uploadsDir = java.nio.file.Paths.get("uploads");
                if (!java.nio.file.Files.exists(uploadsDir)) java.nio.file.Files.createDirectories(uploadsDir);
                String fileName = System.currentTimeMillis() + "-" + file.filename();
                java.nio.file.Path dest = uploadsDir.resolve(fileName);
                try (java.io.InputStream in = file.content()) {
                    java.nio.file.Files.copy(in, dest);
                }
                imagePath = "/uploads/" + fileName;
            }
            Recipe r = new Recipe();
            r.id = id;
            r.title = title;
            r.ingredients = ingredients;
            r.instructions = instructions;
            r.image = imagePath;
            boolean ok = RecipeRepository.update(r);
            if (ok) ctx.status(200).json(r);
            else ctx.status(404).result("Not found");
        });

        app.delete("/recipes/{id}", ctx -> {
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
            st.execute("CREATE TABLE IF NOT EXISTS recipes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, ingredients TEXT, instructions TEXT, image TEXT)");

            // ensure 'image' column exists for older DBs: backup then ALTER TABLE ADD COLUMN if needed
            try {
                java.nio.file.Path db = java.nio.file.Paths.get("recipes.db");
                java.nio.file.Path bak = java.nio.file.Paths.get("recipes.db.bak");
                if (java.nio.file.Files.exists(db) && !java.nio.file.Files.exists(bak)) {
                    java.nio.file.Files.copy(db, bak);
                    System.out.println("DB backup created: recipes.db.bak");
                }
            } catch (Exception e) {
                System.err.println("Warning: could not create DB backup: " + e.getMessage());
            }

            try (ResultSet rs = st.executeQuery("PRAGMA table_info('recipes')")) {
                boolean hasImage = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("image".equalsIgnoreCase(name)) { hasImage = true; break; }
                }
                if (!hasImage) {
                    st.execute("ALTER TABLE recipes ADD COLUMN image TEXT");
                    System.out.println("DB migration: added 'image' column to recipes table");
                }
            }
        }
        RecipeRepository.setConnection(conn);
    }
}
