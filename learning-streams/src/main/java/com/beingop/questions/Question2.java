package com.beingop.questions;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Question2 {

    public static void main(String[] args) {

        /*
         * counting word occurrences
         * */
        String statement = "Hello Hi Bye Hello Bye Hi Thank you See you later Bye Bye Hello again";

        Map<String, Long> collected = Arrays.stream(statement.split(" "))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        System.out.println(collected);
    }
}
