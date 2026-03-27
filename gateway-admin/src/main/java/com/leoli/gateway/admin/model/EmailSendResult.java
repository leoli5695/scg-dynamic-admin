package com.leoli.gateway.admin.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Email send result.
 *
 * @author leoli
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailSendResult {
    
    private boolean success;
    private String errorMessage;
    
    public static EmailSendResult success() {
        return new EmailSendResult(true, null);
    }
    
    public static EmailSendResult failure(String errorMessage) {
        return new EmailSendResult(false, errorMessage);
    }
}