package com.leoli.license.client.exception;

/**
 * Exception thrown when a feature is not allowed by the current license.
 */
public class LicenseFeatureNotAllowedException extends RuntimeException {

    private final String feature;
    private final String requiredEdition;
    private final String currentEdition;

    public LicenseFeatureNotAllowedException(String feature, String requiredEdition, String currentEdition) {
        super(String.format("Feature '%s' requires %s edition, but current license is %s", 
            feature, requiredEdition, currentEdition));
        this.feature = feature;
        this.requiredEdition = requiredEdition;
        this.currentEdition = currentEdition;
    }

    public String getFeature() { return feature; }
    public String getRequiredEdition() { return requiredEdition; }
    public String getCurrentEdition() { return currentEdition; }
}