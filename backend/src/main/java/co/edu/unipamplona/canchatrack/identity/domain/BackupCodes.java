package co.edu.unipamplona.canchatrack.identity.domain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Códigos de respaldo para quien pierde el teléfono: cada uno sirve una sola vez. Formato {@code XXXXX-XXXXX} con un
 * alfabeto sin caracteres confundibles (sin 0/O, 1/I/L).
 */
public final class BackupCodes {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int HALF = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCodes() {
    }

    public static List<String> generate(int count) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < count) {
            StringBuilder code = new StringBuilder(HALF * 2 + 1);
            for (int i = 0; i < HALF * 2; i++) {
                if (i == HALF) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return new ArrayList<>(codes);
    }

    /** Forma canónica: mayúsculas, sin espacios ni guiones. Es lo que se compara y se resume. */
    public static String normalize(String input) {
        return input == null ? "" : input.replace("-", "").replace(" ", "").trim().toUpperCase(Locale.ROOT);
    }

    /** {@code true} si, normalizado, tiene la forma de un código de respaldo. */
    public static boolean looksLikeBackupCode(String input) {
        String normalized = normalize(input);
        return normalized.length() == HALF * 2 && normalized.chars().allMatch(c -> ALPHABET.indexOf(c) >= 0);
    }
}
