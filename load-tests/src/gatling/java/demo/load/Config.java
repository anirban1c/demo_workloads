package demo.load;

/** Run-time knobs (-Dname=value on the Gradle command line; see build.gradle.kts). */
public final class Config {
    public static final String GATEWAY = System.getProperty("gateway", "http://localhost:8080");
    public static final String WIREMOCK = System.getProperty("wiremock", "http://localhost:8089");
    /** Journeys started per second (each virtual user runs exactly one journey). */
    public static final double TPS = Double.parseDouble(System.getProperty("tps", "50"));
    public static final int DURATION_S = Integer.parseInt(System.getProperty("duration", "600"));
    public static final double MAX_TPS = Double.parseDouble(System.getProperty("maxTps", "150"));
    public static final double SPIKE_TPS = Double.parseDouble(System.getProperty("spikeTps", "300"));
    public static final int POLL_TIMEOUT_S = Integer.parseInt(System.getProperty("pollTimeout", "60"));
    public static final String JOURNEY = System.getProperty("journey", "add_funds");
    public static final String VENDOR = System.getProperty("vendor", "paylane");
    public static final int FAULT_DELAY_MS = Integer.parseInt(System.getProperty("faultDelay", "5000"));
    public static final int FAULT_PCT = Integer.parseInt(System.getProperty("faultPct", "30"));

    private Config() {}

    public static int intProp(String name, int def) {
        return Integer.parseInt(System.getProperty(name, String.valueOf(def)));
    }
}
