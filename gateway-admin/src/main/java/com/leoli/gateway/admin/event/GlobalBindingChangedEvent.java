package com.leoli.gateway.admin.event;

/**
 * Event fired when global plugin bindings change.
 * Triggers refresh of all enabled routes to apply new global bindings.
 *
 * @author leoli
 */
public class GlobalBindingChangedEvent {

    private final ChangeType changeType;
    private final String bindingId;
    private final String pluginId;

    public GlobalBindingChangedEvent(ChangeType changeType, String bindingId, String pluginId) {
        this.changeType = changeType;
        this.bindingId = bindingId;
        this.pluginId = pluginId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getBindingId() {
        return bindingId;
    }

    public String getPluginId() {
        return pluginId;
    }

    /**
     * Type of change that triggered the event.
     */
    public enum ChangeType {
        CREATED,
        UPDATED,
        DELETED,
        ENABLED,
        DISABLED
    }
}