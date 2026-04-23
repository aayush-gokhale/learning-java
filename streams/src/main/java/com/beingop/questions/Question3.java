package com.beingop.questions;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Question3 {

    public static void main(String[] args) {

        /*
         * create a map with key being the word itself & the length being the value.
         * */
        List<String> names = Arrays.asList("Aayush", "Aayush", "Anmol", "Sipu", "Saurabh", "Pranjal", "Smit");

        Map<String, Integer> collected = names.stream()
                .collect(Collectors.toMap(word -> word,
                        word -> word.length(),
                        (oldLength, newLength) -> oldLength + newLength));

        System.out.println(collected);
    }
}
