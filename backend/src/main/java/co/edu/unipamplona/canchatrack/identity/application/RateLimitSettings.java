package co.edu.unipamplona.canchatrack.identity.application;

import java.util.EnumMap;
import java.util.Map;

import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

/**
 * Límites vigentes.
 *
 * @param enabled {@code false} apaga el control por completo (solo pruebas y desarrollo)
 * @param rules   una regla por cubeta; todas deben estar presentes
 */
public record RateLimitSettings(boolean enabled, Map<RateLimitBucket, RateLimitRule> rules) {

    public RateLimitSettings {
        EnumMap<RateLimitBucket, RateLimitRule> copy = new EnumMap<>(RateLimitBucket.class);
        copy.putAll(rules);
        for (RateLimitBucket bucket : RateLimitBucket.values()) {
            if (copy.get(bucket) == null) {
                throw new IllegalArgumentException("missing rate limit rule for " + bucket);
            }
        }
        rules = Map.copyOf(copy);
    }

    public RateLimitRule ruleFor(RateLimitBucket bucket) {
        return rules.get(bucket);
    }
}
