package com.beingop.questions;

import com.beingop.model.Employee;

import java.util.*;
import java.util.stream.Collectors;

public class Question6 {

    public static void main(String[] args) {

        /*
         * highest paid employee in each department
         * */
        List<Employee> employees = Arrays.asList(
                new Employee("Aayush", "IT", 50000),
                new Employee("Anmol", "HR", 45000),
                new Employee("Sipu", "IT", 60000),
                new Employee("Saurabh", "Finance", 55000),
                new Employee("Pranjal", "Finance", 70000),
                new Employee("Smit", "HR", 48000)
        );

        employees.stream()
                .collect(Collectors.groupingBy(emp -> emp.getDepartment(),
                        Collectors.summarizingDouble(emp -> emp.getSalary())))
                .forEach((department, stats) -> {
                    System.out.println(department + " : " + stats.getMax());
                });

        Map<String, Optional<Employee>> collected = employees.stream()
                .collect(Collectors.groupingBy(emp -> emp.getDepartment(),
                        Collectors.maxBy(Comparator.comparing(emp -> emp.getSalary()))));

        System.out.println("----------------------------------------------------");

        collected.forEach((department, employee) -> {
            System.out.println(department + " : " + employee.orElse(null));
        });
    }
}
