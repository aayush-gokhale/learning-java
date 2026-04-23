package com.beingop.questions;

import com.beingop.model.Product;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Question5 {

    public static void main(String[] args) {

        /*
         * extract product categories
         * */

        List<Product> products = Arrays.asList(
                new Product("Laptop", 1200.0, "Electronics"),
                new Product("Mouse", 25.0, "Electronics"),
                new Product("Keyboard", 75.0, "Electronics"),
                new Product("Jeans", 750.0, "Clothes"));

        List<String> collected = products.stream()
                .map(product -> product.getCategory())
                .distinct()
                .toList();

        System.out.println(collected);
    }
}
