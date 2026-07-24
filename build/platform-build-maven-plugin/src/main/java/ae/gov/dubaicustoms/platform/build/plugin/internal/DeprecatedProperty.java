package ae.gov.dubaicustoms.platform.build.plugin.internal;

// A deprecated configuration property declared in a platform jar's spring-configuration-metadata.json.
// replacement/reason/level may be null when the metadata omits them.
public record DeprecatedProperty(String name, String replacement, String reason, String level) {
}
