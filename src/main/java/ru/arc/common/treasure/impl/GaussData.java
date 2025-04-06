package ru.arc.common.treasure.impl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class GaussData {

    Double min, max, mean, stdDev;

    public Map<String, Double> serialize() {
        Map<String, Double> map = new HashMap<>();
        if (min != null) map.put("min", min);
        if (max != null) map.put("max", max);
        if (mean != null) map.put("mean", mean);
        if (stdDev != null) map.put("stdDev", stdDev);
        return map;
    }

    public double random() {
        double v = ThreadLocalRandom.current().nextGaussian(mean, stdDev);
        if (v < min) {
            return min;
        } else if (v > max) {
            return max;
        }
        return v;
    }

    public static GaussData deserialize(Map<String, Double> map) {
        return GaussData.builder()
                .min(map.get("min"))
                .max(map.get("max"))
                .mean(map.get("mean"))
                .stdDev(map.get("stdDev"))
                .build();
    }

    public String toString() {
        return "min: " + min + ", max: " + max + ", mean: " + mean + ", stdDev: " + stdDev;
    }
}
