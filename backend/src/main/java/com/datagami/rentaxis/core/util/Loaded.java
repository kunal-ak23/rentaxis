package com.datagami.rentaxis.core.util;

import org.hibernate.Hibernate;

import java.util.Collection;
import java.util.function.Function;

/**
 * Initialises the lazy associations an entity-returning endpoint serialises.
 *
 * <p>Open-session-in-view is off (scale P1-11), so an entity handed to Jackson after its
 * service transaction has ended can no longer load a lazy {@code @ManyToOne} on the way
 * out. The endpoints that return entities directly (units, buildings, ...) call this inside
 * their {@code @Transactional} service method for the associations their JSON carries; with
 * {@code default_batch_fetch_size} a list's proxies load in a few batched queries, not one
 * per row.</p>
 */
public final class Loaded {

    private Loaded() {
    }

    @SafeVarargs
    public static <T> T with(T entity, Function<T, ?>... associations) {
        if (entity != null) {
            for (Function<T, ?> a : associations) {
                Hibernate.initialize(a.apply(entity));
            }
        }
        return entity;
    }

    @SafeVarargs
    public static <T, C extends Collection<T>> C all(C entities, Function<T, ?>... associations) {
        for (T e : entities) {
            with(e, associations);
        }
        return entities;
    }
}
