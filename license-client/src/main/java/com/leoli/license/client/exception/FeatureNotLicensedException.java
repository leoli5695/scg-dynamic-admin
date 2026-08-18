package com.leoli.license.client.exception;

import com.leoli.license.client.model.LicenseInfo;

/**
 * Feature Not Licensed Exception
 * <p>
 * Thrown when attempting to use a feature not authorized in the license
 */
public class FeatureNotLicensedException extends RuntimeException {

    private final LicenseInfo.Feature feature;
    private final String requiredEdition;
    private final String currentEdition;

    public FeatureNotLicensedException(LicenseInfo.Feature feature, String currentEdition) {
        super(buildMessage(feature, currentEdition));
        this.feature = feature;
        this.requiredEdition = feature.getRequiredEdition();
        this.currentEdition = currentEdition;
    }

    public FeatureNotLicensedException(String featureName, String requiredEdition, String currentEdition) {
        super("Feature [" + featureName + "] requires " + requiredEdition + " edition. Current: " + currentEdition);
        this.feature = null;
        this.requiredEdition = requiredEdition;
        this.currentEdition = currentEdition;
    }

    private static String buildMessage(LicenseInfo.Feature feature, String currentEdition) {
        return "Feature [" + feature.name() + "] requires " + feature.getRequiredEdition() 
            + " edition. Current: " + currentEdition;
    }

    public LicenseInfo.Feature getFeature() {
        return feature;
    }

    public String getRequiredEdition() {
        return requiredEdition;
    }

    public String getCurrentEdition() {
        return currentEdition;
    }

    /**
     * Get error code for API response
     */
    public int getErrorCode() {
        return 40303;
    }
}