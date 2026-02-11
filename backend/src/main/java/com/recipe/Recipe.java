package com.recipe;

public class Recipe {
    public Integer id;
    public String title;
    public String ingredients; // simple newline-separated
    public String instructions;

    public Recipe() {}

    public Recipe(Integer id, String title, String ingredients, String instructions) {
        this.id = id;
        this.title = title;
        this.ingredients = ingredients;
        this.instructions = instructions;
    }
}
