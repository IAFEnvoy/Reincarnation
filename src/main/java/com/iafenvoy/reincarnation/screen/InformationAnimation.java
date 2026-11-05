package com.iafenvoy.reincarnation.screen;

/**
 * A short, reversible transition driven by elapsed time rather than frame count.
 */
final class InformationAnimation {
    private final double duration;
    private double start, elapsed;
    double value, target;

    InformationAnimation(double value, double duration) {
        this.duration = duration;
        this.snapTo(value);
    }

    void snapTo(double value) {
        this.start = this.value = this.target = value;
        this.elapsed = this.duration;
    }

    void moveTo(double target) {
        if (this.target == target) return;
        this.start = this.value;
        this.target = target;
        this.elapsed = 0;
    }

    boolean advance(double seconds) {
        if (this.value == this.target) return false;
        this.elapsed = Math.min(this.duration, this.elapsed + Math.max(0, seconds));
        double remaining = 1 - this.elapsed / this.duration;
        this.value = this.target + (this.start - this.target) * remaining * remaining * remaining;
        return true;
    }

    void shift(double distance) {
        this.start += distance;
        this.value += distance;
        this.target += distance;
    }

    void clamp(double maximum) {
        double bounded = Math.clamp(this.value, 0, maximum);
        double destination = Math.clamp(this.target, 0, maximum);
        if (bounded != this.value) this.snapTo(bounded);
        this.moveTo(destination);
    }
}
