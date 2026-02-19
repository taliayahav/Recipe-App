package com.recipe;

import io.javalin.Javalin;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.URL;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import io.javalin.http.UploadedFile;
import java.util.Base64;
import java.io.OutputStream;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class App {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static Connection conn;
    private static final ExecutorService IMAGE_WORKER = Executors.newFixedThreadPool(2);

    // simple container for fetched image metadata
    private static class ImageResult {
        public final String path;
        public final String provider;
        public final String attribution;
        public ImageResult(String path, String provider, String attribution) {
            this.path = path;
            this.provider = provider;
            this.attribution = attribution;
        }
    }

    public static void main(String[] args) throws Exception {
        initDb();

        Javalin app = Javalin.create().start(7001);

        // SSE clients registry
        java.util.List<io.javalin.http.sse.SseClient> sseClients = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        app.sse("/events", client -> {
            sseClients.add(client);
            client.onClose(() -> sseClients.remove(client));
        });

        app.get("/uploads/{file}", ctx -> {
            String file = ctx.pathParam("file");
            Path p = Paths.get("uploads", file).toAbsolutePath();
            if (Files.exists(p)) {
                ctx.contentType(Files.probeContentType(p));
                ctx.result(new java.io.FileInputStream(p.toFile()));
            } else {
                ctx.status(404).result("Not found");
            }
        });

        app.before(ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
        });

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
            String title = ctx.formParam("title");
            String ingredients = ctx.formParam("ingredients");
            String instructions = ctx.formParam("instructions");
            String imagePath = null;

            java.util.List<UploadedFile> uploaded = ctx.uploadedFiles("image");
            boolean hadUpload = uploaded != null && !uploaded.isEmpty();
            if (hadUpload) {
                UploadedFile file = uploaded.get(0);
                Path uploadsDir = Paths.get("uploads");
                if (!Files.exists(uploadsDir)) Files.createDirectories(uploadsDir);
                String fileName = System.currentTimeMillis() + "-" + file.filename();
                Path dest = uploadsDir.resolve(fileName);
                try (InputStream in = file.content()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                imagePath = "/uploads/" + fileName;
            } else {
                if (title != null && !title.isEmpty()) {
                    imagePath = downloadImageForTitle(title);
                }
            }

            Recipe r = new Recipe();
            r.title = title;
            r.ingredients = ingredients;
            r.instructions = instructions;
            r.image = imagePath;
            Recipe created = RecipeRepository.create(r);
            ctx.status(201).json(created);

            // enqueue background image fetch if client did not upload an image
            if (!hadUpload && title != null && !title.isEmpty()) {
                int recipeId = created.id;
                IMAGE_WORKER.submit(() -> {
                    try {
                        // try up to 2 times
                        ImageResult result = null;
                        for (int attempt = 1; attempt <= 2; attempt++) {
                            result = fetchFromUnsplashAndSave(title);
                            if (result != null) break;
                            System.out.println("Unsplash fetch attempt " + attempt + " failed for '" + title + "'");
                            Thread.sleep(500 * attempt);
                        }
                        if (result != null) {
                            System.out.println("Unsplash fetched: " + result.path + " (" + result.attribution + ")");
                            RecipeRepository.addImageToRecipe(recipeId, result.path, result.provider, result.attribution, true);
                            // broadcast to SSE clients that recipe image updated
                            try {
                                String msg = "{\"id\":" + recipeId + ",\"image\":\"" + result.path + "\"}";
                                synchronized (sseClients) {
                                    for (io.javalin.http.sse.SseClient c : sseClients) {
                                        try { c.sendEvent("recipe-update", msg); } catch (Exception ignore) {}
                                    }
                                }
                            } catch (Exception ignore) {}
                        } else {
                            System.out.println("Unsplash: no image found for '" + title + "'. Falling back to immediate auto image if available.");
                            try {
                                // attempt to use the immediate auto image saved in recipes.image
                                try (PreparedStatement ps = conn.prepareStatement("SELECT image FROM recipes WHERE id = ?")) {
                                    ps.setInt(1, recipeId);
                                    try (ResultSet rs = ps.executeQuery()) {
                                        if (rs.next()) {
                                            String img = rs.getString("image");
                                            if (img != null && img.contains("-auto.")) {
                                                // record it in recipe_images as primary
                                                RecipeRepository.addImageToRecipe(recipeId, img, "fallback", "auto-generated", true);
                                            } else {
                                                System.out.println("No immediate auto image available to fallback for recipe " + recipeId);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                System.out.println("Error while falling back to immediate image: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        });

        app.put("/recipes/{id}", ctx -> {
            int id = Integer.parseInt(ctx.pathParam("id"));
            String title = ctx.formParam("title");
            String ingredients = ctx.formParam("ingredients");
            String instructions = ctx.formParam("instructions");
            String imagePath = null;
            java.util.List<UploadedFile> uploaded = ctx.uploadedFiles("image");
            if (uploaded != null && !uploaded.isEmpty()) {
                UploadedFile file = uploaded.get(0);
                Path uploadsDir = Paths.get("uploads");
                if (!Files.exists(uploadsDir)) Files.createDirectories(uploadsDir);
                String fileName = System.currentTimeMillis() + "-" + file.filename();
                Path dest = uploadsDir.resolve(fileName);
                try (InputStream in = file.content()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
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
            // create recipe_images table for storing multiple images (provider, attribution)
            st.execute("CREATE TABLE IF NOT EXISTS recipe_images (id INTEGER PRIMARY KEY AUTOINCREMENT, recipe_id INTEGER NOT NULL, filename TEXT NOT NULL, provider TEXT, attribution TEXT, is_primary INTEGER DEFAULT 0, FOREIGN KEY(recipe_id) REFERENCES recipes(id))");
            try {
                Path db = Paths.get("recipes.db");
                Path bak = Paths.get("recipes.db.bak");
                if (Files.exists(db) && !Files.exists(bak)) {
                    Files.copy(db, bak);
                }
            } catch (Exception e) { }
            try (ResultSet rs = st.executeQuery("PRAGMA table_info('recipes')")) {
                boolean hasImage = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("image".equalsIgnoreCase(name)) { hasImage = true; break; }
                }
                if (!hasImage) st.execute("ALTER TABLE recipes ADD COLUMN image TEXT");
            }
        }
        RecipeRepository.setConnection(conn);
    }

    private static String downloadImageForTitle(String title) {
        // only use source.unsplash with food qualifier to avoid non-food images
        String[] providers = new String[] {
            "https://source.unsplash.com/800x600/?%s,food"
        };
        for (String tmpl : providers) {
            try {
                String q = URLEncoder.encode(title, StandardCharsets.UTF_8.toString());
                String src = String.format(tmpl, q);
                URL url = new URL(src);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java) RecipeApp/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.setInstanceFollowRedirects(false);
                conn.connect();
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        url = new URL(loc);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Java) RecipeApp/1.0");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(10000);
                        conn.connect();
                        code = conn.getResponseCode();
                    }
                }
                if (code == 200) {
                    try (InputStream in = conn.getInputStream()) {
                        Path uploadsDir = Paths.get("uploads");
                        if (!Files.exists(uploadsDir)) Files.createDirectories(uploadsDir);
                        String fileName = System.currentTimeMillis() + "-auto.jpg";
                        Path dest = uploadsDir.resolve(fileName);
                        Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                        return "/uploads/" + fileName;
                    }
                }
            } catch (Exception e) {
                // try next provider
            }
        }
        return null;
    }

    // New Unsplash-backed fetcher using the official API (requires UNSPLASH_KEY env var)
    private static ImageResult fetchFromUnsplashAndSave(String title) {
        String key = System.getenv("UNSPLASH_KEY");
        if (key == null || key.isEmpty()) return null;
        // prepare a set of query variations to improve match rates
        String cleaned = title == null ? "" : title.replaceAll("[^A-Za-z0-9 ]", " ").trim();
        String firstLong = "";
        for (String w : cleaned.split("\\s+")) {
            if (w.length() > 3) { firstLong = w; break; }
        }
        String firstWord = cleaned.split("\\s+").length > 0 ? cleaned.split("\\s+")[0] : cleaned;
        // small keyword map to boost matching for common recipe names
        java.util.Map<String, String> keywordMap = new java.util.LinkedHashMap<>();
        keywordMap.put("carbonara", "pasta carbonara");
        keywordMap.put("bbq", "barbecue");
        keywordMap.put("ribs", "barbecue ribs");
        keywordMap.put("pancake", "pancakes");
        keywordMap.put("cookie", "cookies");
        keywordMap.put("chicken", "chicken");
        keywordMap.put("salad", "salad");
        keywordMap.put("soup", "soup");
        keywordMap.put("pizza", "pizza");
        keywordMap.put("burger", "burger");
        keywordMap.put("spaghetti", "spaghetti");
        keywordMap.put("taco", "tacos");

        String lc = title == null ? "" : title.toLowerCase();
        String mapped = null;
        // try exact contains first, then fuzzy-match tokens against keywords
        for (java.util.Map.Entry<String,String> e : keywordMap.entrySet()) {
            if (lc.contains(e.getKey())) { mapped = e.getValue(); break; }
        }
        if (mapped == null && !lc.isBlank()) {
            String[] tokens = lc.split("\\s+");
            for (String token : tokens) {
                for (java.util.Map.Entry<String,String> e : keywordMap.entrySet()) {
                    String mapKey = e.getKey();
                    int dist = levenshtein(token, mapKey);
                    // allow small typos: distance <= 2 or <= 25% of key length
                    int thresh = Math.max(1, Math.min(2, mapKey.length() / 4));
                    if (dist <= 2 || dist <= thresh) {
                        mapped = e.getValue();
                        break;
                    }
                }
                if (mapped != null) break;
            }
        }
        String[] queriesToTry;
        if (mapped != null && !mapped.isBlank()) {
            queriesToTry = new String[] {
                mapped + " food",
                mapped,
                title + " food",
                title,
                cleaned + " food",
                cleaned,
                firstLong + " food",
                firstWord
            };
        } else {
            queriesToTry = new String[] {
                title + " food",
                title,
                cleaned + " food",
                cleaned,
                firstLong + " food",
                firstWord
            };
        }

        try {
            ObjectMapper om = new ObjectMapper();
            for (String qraw : queriesToTry) {
                if (qraw == null || qraw.isBlank()) continue;
                String q = URLEncoder.encode(qraw, StandardCharsets.UTF_8.toString());
                String api = "https://api.unsplash.com/search/photos?query=" + q + "&per_page=3";
                System.out.println("Unsplash: querying API: " + api);
                URL u = new URL(api);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestProperty("Authorization", "Client-ID " + key);
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "RecipeApp/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000);
                conn.connect();
                int apiCode = conn.getResponseCode();
                System.out.println("Unsplash: API response code: " + apiCode + " for query='" + qraw + "'");
                if (apiCode != 200) {
                    try (InputStream err = conn.getErrorStream()) {
                        if (err != null) {
                            byte[] b = err.readAllBytes();
                            System.out.println("Unsplash API error body: " + new String(b));
                        }
                    } catch (Exception ignore) {}
                    continue;
                }
                try (InputStream in = conn.getInputStream()) {
                    JsonNode root = om.readTree(in);
                    JsonNode results = root.path("results");
                    if (results.isArray() && results.size() > 0) {
                        for (JsonNode first : results) {
                            // only accept images that appear to be food-related
                            boolean isFood = false;
                            // check topic_submissions keys
                            JsonNode topics = first.path("topic_submissions");
                            if (!topics.isMissingNode()) {
                                java.util.Iterator<String> it = topics.fieldNames();
                                while (it.hasNext()) {
                                    String topicKey = it.next();
                                    if (topicKey != null && topicKey.toLowerCase().contains("food")) { isFood = true; break; }
                                }
                            }
                            // check tags
                            if (!isFood) {
                                JsonNode tags = first.path("tags");
                                if (tags.isArray()) {
                                    for (JsonNode t : tags) {
                                        String tname = t.path("title").asText("").toLowerCase();
                                        if (tname.contains("food") || tname.contains("dish") || tname.contains("meal") || tname.contains("cooking") || tname.contains("breakfast") || tname.contains("dinner") || tname.contains("dessert") || tname.contains("snack")) { isFood = true; break; }
                                    }
                                }
                            }
                            // check description/alt
                            if (!isFood) {
                                String desc = first.path("alt_description").asText("") + " " + first.path("description").asText("");
                                desc = desc.toLowerCase();
                                if (desc.contains("food") || desc.contains("dish") || desc.contains("meal") || desc.contains("cooking") || desc.contains("breakfast") || desc.contains("dinner") || desc.contains("dessert") || desc.contains("snack")) isFood = true;
                            }

                            if (!isFood) {
                                System.out.println("Unsplash: skipped non-food image for query '" + qraw + "'");
                                continue;
                            }

                            String imageUrl = first.path("urls").path("regular").asText(null);
                            String photographer = first.path("user").path("name").asText(null);
                            String link = first.path("links").path("html").asText(null);
                            if (imageUrl == null || imageUrl.isBlank()) continue;
                            System.out.println("Unsplash: chosen image URL: " + imageUrl + " photographer=" + photographer);
                            try {
                                URL img = new URL(imageUrl);
                                HttpURLConnection ic = (HttpURLConnection) img.openConnection();
                                ic.setRequestProperty("User-Agent", "RecipeApp/1.0");
                                ic.setConnectTimeout(5000);
                                ic.setReadTimeout(10000);
                                ic.connect();
                                int imgCode = ic.getResponseCode();
                                System.out.println("Unsplash: image download HTTP code: " + imgCode + " for url=" + imageUrl);
                                if (imgCode != 200) {
                                    try (InputStream err = ic.getErrorStream()) {
                                        if (err != null) {
                                            byte[] b = err.readAllBytes();
                                            System.out.println("Unsplash image error body: " + new String(b));
                                        }
                                    } catch (Exception ignore) {}
                                    continue;
                                }
                                try (InputStream imgIn = ic.getInputStream()) {
                                    Path uploadsDir = Paths.get("uploads");
                                    if (!Files.exists(uploadsDir)) Files.createDirectories(uploadsDir);
                                        String fileName = System.currentTimeMillis() + "-unsplash.jpg";
                                        Path dest = uploadsDir.resolve(fileName);
                                        Files.copy(imgIn, dest, StandardCopyOption.REPLACE_EXISTING);
                                        // verify with classifier (if configured)
                                        if (!verifyImageIsFood(dest)) {
                                            System.out.println("Unsplash: classifier rejected image " + dest.toString());
                                            try { Files.deleteIfExists(dest); } catch (Exception ex) { }
                                            continue; // try next result
                                        }
                                        String path = "/uploads/" + fileName;
                                        String attribution = photographer != null ? (photographer + " - " + link) : link;
                                        System.out.println("Unsplash: saved image to " + dest.toString());
                                        return new ImageResult(path, "unsplash", attribution);
                                }
                            } catch (Exception ie) {
                                System.out.println("Unsplash: download error for url=" + imageUrl + " -> " + ie.getMessage());
                                // try next result
                            }
                        }
                    }
                }
            }
            // if we get here, Unsplash did not yield a usable image; try source.unsplash and picsum as background fallbacks
            System.out.println("Unsplash: no usable results for title='" + title + "' — trying source.unsplash food fallback");
            String[] providers = new String[] {
                "https://source.unsplash.com/800x600/?%s,food"
            };
            for (String tmpl : providers) {
                try {
                    String q = URLEncoder.encode(title == null ? "" : title, StandardCharsets.UTF_8.toString());
                    String src = String.format(tmpl, q);
                    URL url = new URL(src);
                    HttpURLConnection pc = (HttpURLConnection) url.openConnection();
                    pc.setRequestProperty("User-Agent", "Mozilla/5.0 (Java) RecipeApp/1.0");
                    pc.setConnectTimeout(5000);
                    pc.setReadTimeout(10000);
                    pc.setInstanceFollowRedirects(false);
                    pc.connect();
                    int code = pc.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = pc.getHeaderField("Location");
                        if (loc != null && !loc.isEmpty()) {
                            url = new URL(loc);
                            pc = (HttpURLConnection) url.openConnection();
                            pc.setRequestProperty("User-Agent", "Mozilla/5.0 (Java) RecipeApp/1.0");
                            pc.setConnectTimeout(5000);
                            pc.setReadTimeout(10000);
                            pc.connect();
                            code = pc.getResponseCode();
                        }
                    }
                    if (code == 200) {
                        try (InputStream in = pc.getInputStream()) {
                            Path uploadsDir = Paths.get("uploads");
                            if (!Files.exists(uploadsDir)) Files.createDirectories(uploadsDir);
                            String fileName = System.currentTimeMillis() + "-fallback.jpg";
                            Path dest = uploadsDir.resolve(fileName);
                            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                            // verify with classifier (if configured)
                            if (!verifyImageIsFood(dest)) {
                                System.out.println("Fallback: classifier rejected image " + dest.toString());
                                try { Files.deleteIfExists(dest); } catch (Exception ex) { }
                                continue;
                            }
                            String path = "/uploads/" + fileName;
                            String provider = tmpl.contains("picsum") ? "picsum" : "source";
                            System.out.println("Fallback: saved image from " + src + " to " + dest.toString());
                            return new ImageResult(path, provider, "fallback");
                        }
                    }
                } catch (Exception e) {
                    // try next provider
                    System.out.println("Fallback provider failed: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.out.println("Unsplash: exception while fetching image for '" + title + "': " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    // simple Levenshtein distance helper
    private static int levenshtein(String a, String b) {
        if (a == null) return b == null ? 0 : b.length();
        if (b == null) return a.length();
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i-1) == b.charAt(j-1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j-1] + 1, prev[j] + 1), prev[j-1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }

    // Verify saved image appears to be food using Clarifai or a configurable external classifier.
    // Environment variables:
    // - CLARIFAI_KEY: if present, calls Clarifai Predictions API to check for 'food' probability.
    // - FOOD_CONFIDENCE_THRESHOLD: double between 0 and 1 (default 0.6)
    private static boolean verifyImageIsFood(Path imagePath) {
        String apiKey = System.getenv("CLARIFAI_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            // no classifier configured => be permissive
            return true;
        }
        double threshold = 0.6;
        try {
            String t = System.getenv("FOOD_CONFIDENCE_THRESHOLD");
            if (t != null && !t.isBlank()) threshold = Double.parseDouble(t);
        } catch (Exception e) { }

        try {
            byte[] bytes = Files.readAllBytes(imagePath);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            // Build Clarifai request body (using general model with 'food' concept check)
            ObjectMapper om = new ObjectMapper();
            ObjectNode root = om.createObjectNode();
            ArrayNode inputs = root.putArray("inputs");
            ObjectNode input = inputs.addObject();
            ObjectNode data = input.putObject("data");
            ObjectNode image = data.putObject("image");
            image.put("base64", b64);
            // specify model and fields via URL

            String url = "https://api.clarifai.com/v2/models/food-item-recognition/outputs";
            URL u = new URL(url);
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Key " + apiKey);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            byte[] body = om.writeValueAsBytes(root);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code != 200 && code != 201) {
                try (InputStream err = c.getErrorStream()) {
                    if (err != null) { byte[] b = err.readAllBytes(); System.out.println("Clarifai error: " + new String(b)); }
                } catch (Exception ignore) {}
                return false;
            }
            try (InputStream in = c.getInputStream()) {
                JsonNode resp = om.readTree(in);
                // Clarifai model returns concepts or data.outputs.predictions depending on model; attempt to find 'food' or high scoring food concepts
                double bestFood = 0.0;
                // traverse for concepts
                JsonNode outputs = resp.path("outputs");
                if (outputs.isArray()) {
                    for (JsonNode out : outputs) {
                        JsonNode concepts = out.path("data").path("concepts");
                        if (concepts.isArray()) {
                            for (JsonNode cnode : concepts) {
                                String name = cnode.path("name").asText("").toLowerCase();
                                double val = cnode.path("value").asDouble(0.0);
                                if (name.contains("food") || name.contains("dish") || name.contains("meal") || name.contains("breakfast") || name.contains("dinner") || name.contains("dessert") || name.contains("snack")) {
                                    if (val > bestFood) bestFood = val;
                                }
                            }
                        }
                    }
                }
                System.out.println("Clarifai: bestFoodScore=" + bestFood + " threshold=" + threshold + " for " + imagePath.toString());
                return bestFood >= threshold;
            }
        } catch (Exception e) {
            System.out.println("Classifier error: " + e.getMessage());
            return false;
        }
    }
}
