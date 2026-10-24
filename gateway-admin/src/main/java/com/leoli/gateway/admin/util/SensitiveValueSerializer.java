package com.leoli.gateway.admin.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Field-level serializer that masks credentials inside a stored JSON snapshot.
 *
 * <p>Applied to {@code AuditLogEntity.oldValue} / {@code newValue} so that <b>every</b> Jackson
 * serialisation of an audit row is masked - the paginated list, the detail endpoint, the JSON
 * export (which builds its own {@code ObjectMapper}) and any endpoint added later. Masking at the
 * entity rather than in each controller is the point: the export path was missed once already,
 * which is how {@code GET /api/audit-logs/export/json} returned every stored JWT secret, API key
 * and OAuth client secret to any authenticated (non-admin) user.
 *
 * <p>Deserialisation and JPA are unaffected, so {@code AuditLogService.rollback} still restores
 * the real value.
 *
 * @author leoli
 */
public class SensitiveValueSerializer extends JsonSerializer<String> {

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(SensitiveDataMasker.mask(value));
    }
}
