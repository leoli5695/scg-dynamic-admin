package com.leoli.gateway.admin.converter;

import com.leoli.gateway.admin.enums.AuthType;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA Attribute Converter for AuthType enum.
 * Converts AuthType enum to String for database storage and vice versa.
 *
 * @author leoli
 */
@Converter(autoApply = true)
public class AuthTypeConverter implements AttributeConverter<AuthType, String> {

    @Override
    public String convertToDatabaseColumn(AuthType authType) {
        if (authType == null) {
            return null;
        }
        return authType.getCode();
    }

    @Override
    public AuthType convertToEntityAttribute(String code) {
        if (code == null || code.isEmpty()) {
            return AuthType.NONE;
        }
        return AuthType.fromCode(code);
    }
}