package com.datagami.rentaxis.core.service;

import org.springframework.stereotype.Service;

import java.util.function.Predicate;

@Service
public class SlugService {

    private static final int MAX_LENGTH = 80;
    private static final int MAX_ATTEMPTS = 20;

    public String slugify(String input) {
        if (input == null) {
            return "";
        }
        String slug = input.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return slug;
    }

    public String uniqueSlug(String base, Predicate<String> existsCheck) {
        if (!existsCheck.test(base)) {
            return base;
        }
        for (int i = 2; i <= MAX_ATTEMPTS; i++) {
            String candidate = base + "-" + i;
            if (!existsCheck.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Exhausted slug attempts for base: " + base);
    }
}
