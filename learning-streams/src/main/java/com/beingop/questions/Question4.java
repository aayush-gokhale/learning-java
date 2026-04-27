package com.beingop.questions;

import com.beingop.model.Product;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Question4 {

    public static void main(String[] args) {

        /*
         * calculate the total price of products by category.
         * */

        List<Product> products = Arrays.asList(
                new Product("Laptop", 1200.0, "Electronics"),
                new Product("Mouse", 25.0, "Electronics"),
                new Product("Keyboard", 75.0, "Electronics"),
                new Product("Jeans", 750.0, "Clothes"));

        Map<String, Double> collected = products.stream()
                .collect(Collectors.groupingBy(
                        product -> product.getCategory(),
                        Collectors.summingDouble(
                                product -> product.getPrice())));

        System.out.println(collected);
    }
}
