package com.vivekpatel.simulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Behaviour knobs, bound from the {@code simulator.*} block in {@code application.yml} and
 * overridable at runtime through the control endpoint.
 *
 * <p>A record would be neater, but this is deliberately mutable: the whole point of the simulator
 * is that a test can flip it into {@code HTTP_500} mid-run without a restart.
 *
 * @see FailureMode
 */
@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    /** Current behaviour. Changed at runtime via {@code PUT /control/mode}. */
    private FailureMode mode = FailureMode.NORMAL;

    /** Latency applied in {@link FailureMode#NORMAL}. */
    private Duration baseLatency = Duration.ofMillis(120);

    /** Latency applied in {@link FailureMode#SLOW}. Tune it around the caller's read timeout. */
    private Duration slowLatency = Duration.ofSeconds(10);

    /** How long {@link FailureMode#TIMEOUT} sleeps before giving up on responding at all. */
    private Duration timeoutLatency = Duration.ofSeconds(60);

    /** Fraction of calls that fail in {@link FailureMode#INTERMITTENT}, from 0.0 to 1.0. */
    private double intermittentFailureRate = 0.3;

    /** How long {@link FailureMode#OUTAGE} refuses calls before recovering on its own. */
    private Duration outageDuration = Duration.ofSeconds(30);

    /** Fraction of authorisations that are declined even in NORMAL mode - real life is not 100%. */
    private double declineRate = 0.05;

    public FailureMode getMode() {
        return mode;
    }

    public void setMode(FailureMode mode) {
        this.mode = mode;
    }

    public Duration getBaseLatency() {
        return baseLatency;
    }

    public void setBaseLatency(Duration baseLatency) {
        this.baseLatency = baseLatency;
    }

    public Duration getSlowLatency() {
        return slowLatency;
    }

    public void setSlowLatency(Duration slowLatency) {
        this.slowLatency = slowLatency;
    }

    public Duration getTimeoutLatency() {
        return timeoutLatency;
    }

    public void setTimeoutLatency(Duration timeoutLatency) {
        this.timeoutLatency = timeoutLatency;
    }

    public double getIntermittentFailureRate() {
        return intermittentFailureRate;
    }

    public void setIntermittentFailureRate(double intermittentFailureRate) {
        this.intermittentFailureRate = intermittentFailureRate;
    }

    public Duration getOutageDuration() {
        return outageDuration;
    }

    public void setOutageDuration(Duration outageDuration) {
        this.outageDuration = outageDuration;
    }

    public double getDeclineRate() {
        return declineRate;
    }

    public void setDeclineRate(double declineRate) {
        this.declineRate = declineRate;
    }
}
