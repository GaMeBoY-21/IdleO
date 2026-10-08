package com.idleo.controlplane;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Tells the API contract which fields of a request or response record are always there (ADR 0018). A record
 * component is required unless it's annotated {@code @Nullable}; a nullable one is optional, and its type allows
 * {@code null}; a nullable reference to another schema is {@code oneOf} that schema or {@code null}. Without this every field would be optional, and the web app's generated types would hide a
 * missing field instead of failing its type check.
 */
final class RecordFields implements ModelConverter {

    @Override
    public @Nullable Schema<?> resolve(
            AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        var schema = chain.next().resolve(type, context, chain);
        var raw = Json.mapper().constructType(type.getType()).getRawClass();
        if (schema == null || !raw.isRecord()) {
            return schema;
        }
        Schema<?> model = schema.get$ref() == null
                ? schema
                : context.getDefinedModels()
                        .get(schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1));
        if (model == null || model.getProperties() == null) {
            return schema;
        }
        var required = new ArrayList<String>();
        for (var component : raw.getRecordComponents()) {
            Schema<?> property = model.getProperties().get(component.getName());
            if (property == null) {
                continue;
            }
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                if (property.get$ref() != null) {
                    // A nullable reference: either the referenced schema or null.
                    var orNull = new Schema<>();
                    orNull.setOneOf(
                            List.of(new Schema<>().$ref(property.get$ref()), new Schema<>().types(Set.of("null"))));
                    orNull.setDescription(property.getDescription());
                    model.getProperties().put(component.getName(), orNull);
                } else {
                    allowNull(property);
                }
            } else {
                required.add(component.getName());
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
        return schema;
    }

    private static void allowNull(Schema<?> property) {
        if (property.get$ref() != null) {
            return;
        }
        var types = new LinkedHashSet<String>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        if (!types.isEmpty()) {
            types.add("null");
            property.setTypes(types);
        }
    }
}
