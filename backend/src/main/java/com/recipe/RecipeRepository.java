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
}
