package com.beingop.questions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Question1 {

    public static void main(String[] args) {

        /*
         * Collecting names by length
         * */
        List<String> names = Arrays.asList("Aayush", "Anmol", "Sipu", "Saurabh", "Pranjal", "Smit");

        Map<Integer, List<String>> collected = names.stream().collect(Collectors.groupingBy(name -> name.length()));

        System.out.println(collected);
    }
}
