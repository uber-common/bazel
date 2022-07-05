package com.google.devtools.build.lib.util;

/**
 * Holder class for @{link MapResourceConverter} to hold the estimations of CPU and RAM usage.
 */
public class ResourceEstimation {
    private final Integer cpu;
    private final Integer ram;

    public ResourceEstimation(Integer cpu, Integer ram) {
        this.cpu = cpu;
        this.ram = ram;
    }

    public Integer getCpu() {
        return cpu;
    }

    public Integer getRam() {
        return ram;
    }
}