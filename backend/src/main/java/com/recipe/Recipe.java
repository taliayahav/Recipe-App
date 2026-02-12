package com.recipe;

public class Recipe {
    public Integer id;
    public String title;
    public String ingredients; // simple newline-separated
    public String instructions;
    public String image; // URL path to uploaded image (e.g. /uploads/xxx.jpg)

    public Recipe() {}

    public Recipe(Integer id, String title, String ingredients, String instructions) {
        this.id = id;
        this.title = title;
        this.ingredients = ingredients;
        this.instructions = instructions;
    }

    public Recipe(Integer id, String title, String ingredients, String instructions, String image) {
        this.id = id;
        this.title = title;
        this.ingredients = ingredients;
        this.instructions = instructions;
        this.image = image;
    }
}
