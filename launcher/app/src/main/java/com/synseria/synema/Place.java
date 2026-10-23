package com.synseria.synema;

import java.util.Collection;
import java.util.Map;

/**
 * A place's readings (outside, a room): each value with the sensor it comes from, so its trend can be
 * read from History. The top line, the panel's rooms and a room page show them alike (HouseHeader).
 */
final class Place {
    /** A value and its sensor; either may be missing. */
    static final class Reading {
        static final Reading NONE = new Reading("", null);
        final String sensor;
        final Double value;

        Reading(String sensor, Double value) {
            this.sensor = sensor == null ? "" : sensor;
            this.value = value;
        }

        /** The sensor's value in {@code states}; NONE without a sensor. */
        static Reading of(String sensor, Map<String, HomeAssistant.State> states) {
            return sensor == null || sensor.isEmpty() ? NONE : new Reading(sensor, HouseText.number(states.get(sensor)));
        }
    }

    final Reading temperature, humidity, pressure, co2, noise;

    Place(Reading temperature, Reading humidity, Reading pressure, Reading co2, Reading noise) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.pressure = pressure;
        this.co2 = co2;
        this.noise = noise;
    }

    /** home.json's place, read from {@code states}. */
    static Place of(HomeConfig.Climate c, Map<String, HomeAssistant.State> states) {
        return new Place(Reading.of(c.entity, states), Reading.of(c.humidity, states), Reading.of(c.pressure, states),
                Reading.of(c.co2, states), Reading.of(c.noise, states));
    }

    void addSensors(Collection<String> out) {
        for (Reading r : new Reading[]{temperature, humidity, pressure, co2, noise}) if (!r.sensor.isEmpty()) out.add(r.sensor);
    }

    /** What the place shows, and the arrows it would show: a change of it is worth a redraw. */
    String signature() {
        StringBuilder sb = new StringBuilder();
        Kind[] kinds = Kind.values();
        Reading[] all = {temperature, humidity, pressure, co2, noise};
        for (int i = 0; i < all.length; i++) sb.append(all[i].value).append(kinds[i].trend(all[i].sensor)).append('|');
        return sb.toString();
    }

    /**
     * What a reading is, for its trend: over how long it is measured and from what change it counts.
     * The pressure's are the weather services' (three hours; under 1 hPa steady, a fall of 3 hPa quick:
     * worse weather on the way); the others are read over an hour, against what a sensor tells apart.
     * Only the pressure shows that it is steady: elsewhere a steady arrow on every line was noise.
     */
    enum Kind {
        TEMPERATURE(1, 0.3, 0), HUMIDITY(1, 3, 0), PRESSURE(3, 1, 3), CO2(1, 100, 0), NOISE(1, 5, 0);

        final long spanMs;
        private final double steady, fastFall;

        Kind(int hours, double steady, double fastFall) {
            this.spanMs = hours * History.HOUR_MS;
            this.steady = steady;
            this.fastFall = fastFall;
        }

        /** The arrow for the sensor's change, or null: before its history is read, or steady but for the pressure. */
        Trend trend(String sensor) {
            Double d = sensor.isEmpty() ? null : History.change(sensor, spanMs);
            if (d == null) return null;
            if (fastFall > 0 && d <= -fastFall) return Trend.FALLING_FAST;
            if (d <= -steady) return Trend.FALLING;
            if (d >= steady) return Trend.RISING;
            return this == PRESSURE ? Trend.STEADY : null;
        }
    }

    enum Trend {
        STEADY("→", HouseHeader.TEXT), RISING("↗", HouseHeader.TEXT), FALLING("↘", HouseHeader.TEXT),
        FALLING_FAST("↘", HouseHeader.FAIR);

        final String arrow;
        final int color;

        Trend(String arrow, int color) {
            this.arrow = arrow;
            this.color = color;
        }
    }
}
