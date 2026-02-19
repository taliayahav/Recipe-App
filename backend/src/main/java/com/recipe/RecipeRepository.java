package com.recipe;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RecipeRepository {
    private static Connection conn;

    public static void setConnection(Connection c) {
        conn = c;
    }

    public static Recipe create(Recipe r) throws SQLException {
        String sql = "INSERT INTO recipes(title, ingredients, instructions, image) VALUES(?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.title);
            ps.setString(2, r.ingredients);
            ps.setString(3, r.instructions);
            ps.setString(4, r.image);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    r.id = rs.getInt(1);
                }
            }
        }
        return r;
    }

    public static boolean update(Recipe r) throws SQLException {
        String sql = "UPDATE recipes SET title = ?, ingredients = ?, instructions = ?, image = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.title);
            ps.setString(2, r.ingredients);
            ps.setString(3, r.instructions);
            ps.setString(4, r.image);
            ps.setInt(5, r.id == null ? -1 : r.id);
            int updated = ps.executeUpdate();
            return updated > 0;
        }
    }

    public static boolean delete(int id) throws SQLException {
        String sql = "DELETE FROM recipes WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            int deleted = ps.executeUpdate();
            return deleted > 0;
        }
    }

    public static List<Recipe> getAll() throws SQLException {
        List<Recipe> list = new ArrayList<>();
    String sql = "SELECT id, title, ingredients, instructions, image FROM recipes ORDER BY id DESC";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Recipe(rs.getInt("id"), rs.getString("title"), rs.getString("ingredients"), rs.getString("instructions"), rs.getString("image")));
            }
        }
        return list;
    }

    // Add image row for a recipe (filename should be the stored path, e.g. /uploads/1234.jpg)
    public static void addImageToRecipe(int recipeId, String filename, String provider, String attribution, boolean isPrimary) throws SQLException {
        String sql = "INSERT INTO recipe_images(recipe_id, filename, provider, attribution, is_primary) VALUES(?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, recipeId);
            ps.setString(2, filename);
            ps.setString(3, provider);
            ps.setString(4, attribution);
            ps.setInt(5, isPrimary ? 1 : 0);
            ps.executeUpdate();
            System.out.println("RecipeRepository: inserted image for recipe " + recipeId + " -> " + filename + " (provider=" + provider + ")");
        }
        if (isPrimary) {
            updateImageColumn(recipeId, filename);
        }
    }

    // Update the recipes.image column for the primary image
    public static void updateImageColumn(int recipeId, String filename) throws SQLException {
        String sql = "UPDATE recipes SET image = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, filename);
            ps.setInt(2, recipeId);
            ps.executeUpdate();
            System.out.println("RecipeRepository: updated recipes.image for " + recipeId + " -> " + filename);
        }
    }

    public static List<String> getImagesForRecipe(int recipeId) throws SQLException {
        List<String> images = new ArrayList<>();
        String sql = "SELECT filename FROM recipe_images WHERE recipe_id = ? ORDER BY id ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, recipeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) images.add(rs.getString("filename"));
            }
        }
        return images;
    }
}
