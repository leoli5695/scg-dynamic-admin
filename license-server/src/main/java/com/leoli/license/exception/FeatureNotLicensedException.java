package com.leoli.license.exception;

import com.leoli.license.model.LicenseFeature;

/**
 * Feature Not Licensed Exception
 * <p>
 * Thrown when attempting to use a feature not authorized in the license
 */
public class FeatureNotLicensedException extends LicenseException {

    private final LicenseFeature feature;
    private final String requiredEdition;
    private final String currentEdition;

    public FeatureNotLicensedException(LicenseFeature feature, String currentEdition) {
        super(feature.getUpgradeHint(currentEdition), 40303);
        this.feature = feature;
        this.requiredEdition = feature.getRequiredEdition();
        this.currentEdition = currentEdition;
    }

    public FeatureNotLicensedException(String featureName, String requiredEdition, String currentEdition) {
        super("Feature [" + featureName + "] requires " + requiredEdition + " edition. Current: " + currentEdition, 40303);
        this.feature = null;
        this.requiredEdition = requiredEdition;
        this.currentEdition = currentEdition;
    }

    public LicenseFeature getFeature() {
        return feature;
    }

    public String getRequiredEdition() {
        return requiredEdition;
    }

    public String getCurrentEdition() {
        return currentEdition;
    }
}