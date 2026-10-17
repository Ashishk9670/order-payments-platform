package com.example.shop.common.contract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backward-compatibility rules for an event schema, checked against the previously released ("baseline") version.
 * Events stay in Kafka and are replayed, so a NEW consumer must still read OLD messages and an OLD consumer must
 * still read NEW ones. That forbids: removing or retyping a property, and adding or dropping a required property.
 * Adding an optional property is fine.
 */
final class SchemaCompatibility {

    private SchemaCompatibility() {
    }

    static List<String> breakingChanges(JsonNode baseline, JsonNode candidate) {
        List<String> problems = new ArrayList<>();
        compare("$", baseline, candidate, problems);
        return problems;
    }

    private static void compare(String path, JsonNode old, JsonNode now, List<String> problems) {
        JsonNode oldType = old.get("type");
        JsonNode newType = now.get("type");
        if (oldType != null && !oldType.equals(newType)) {
            problems.add(path + ": type changed from " + oldType + " to " + newType);
            return;
        }
        if (old.has("format") && !old.get("format").equals(now.get("format"))) {
            problems.add(path + ": format changed from " + old.get("format") + " to " + now.get("format"));
        }
        Set<String> oldRequired = names(old.get("required"));
        Set<String> newRequired = names(now.get("required"));
        for (String r : newRequired) {
            if (!oldRequired.contains(r)) {
                problems.add(path + ": new required property '" + r + "' (old messages do not have it)");
            }
        }
        for (String r : oldRequired) {
            if (!newRequired.contains(r)) {
                problems.add(path + ": property '" + r + "' is no longer required (old consumers rely on it)");
            }
        }
        JsonNode oldProps = old.get("properties");
        JsonNode newProps = now.get("properties");
        if (oldProps != null) {
            Iterator<Map.Entry<String, JsonNode>> it = oldProps.fields();
            while (it.hasNext()) {
                var entry = it.next();
                JsonNode counterpart = newProps == null ? null : newProps.get(entry.getKey());
                if (counterpart == null) {
                    problems.add(path + "." + entry.getKey() + ": property removed");
                } else {
                    compare(path + "." + entry.getKey(), entry.getValue(), counterpart, problems);
                }
            }
        }
        if (old.has("items") && now.has("items")) {
            compare(path + "[]", old.get("items"), now.get("items"), problems);
        } else if (old.has("items")) {
            problems.add(path + ": array items definition removed");
        }
    }

    private static Set<String> names(JsonNode array) {
        Set<String> out = new HashSet<>();
        if (array != null) {
            array.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
