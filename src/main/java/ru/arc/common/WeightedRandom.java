package ru.arc.common;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class WeightedRandom<T> {
    public record Pair<T>(T value, double weight) {
    }

    private final TreeMap<Double, Pair<T>> map = new TreeMap<>();
    private double totalWeight = 0;

    public Collection<T> values() {
        return map.values().stream()
                .map(Pair::value)
                .toList();
    }

    public void add(T value, double weight) {
        if (weight <= 0) return;
        totalWeight += weight;
        map.put(totalWeight, new Pair<>(value, weight));
    }

    public int size() {
        return map.size();
    }

    public T random() {
        if (map.isEmpty()) {
            log.info("Random called on empty WeightedRandom");
            return null;
        }
        double value = ThreadLocalRandom.current().nextDouble(0, totalWeight);
        return map.ceilingEntry(value).getValue().value();
    }

    public Set<T> getNRandom(int n) {
        if (map.isEmpty()) {
            log.info("NRandom called on empty WeightedRandom");
            return Set.of();
        }
        Set<T> result = new HashSet<>();
        for (int i = 0; i < 10; i++) {
            List<Double> values = ThreadLocalRandom.current().doubles(0, totalWeight).limit(n).sorted().boxed().toList();
            for (Double value : values) {
                result.add(map.ceilingEntry(value).getValue().value());
                if (result.size() == n) return result;
            }
        }
        if (result.size() < n) {
            log.info("Not enough unique values in WeightedRandom");
            result.stream().findAny().ifPresent(a -> log.info("First value: {}", a));
        }
        return result;
    }

    public boolean remove(T value) {
        List<Pair<T>> remaining = new ArrayList<>();
        boolean found = false;
        for (Pair<T> pair : map.values()) {
            if (pair.value().equals(value)) {
                found = true;
            } else {
                remaining.add(pair);
            }
        }
        if(!found) return false;
        map.clear();
        totalWeight = 0;
        for (Pair<T> pair : remaining) {
            add(pair.value(), pair.weight());
        }
        return true;
    }
}
