package net.flameslight.zones.types.zoneDefinition;

import net.flameslight.zones.types.BiomeMatcher;
import net.flameslight.zones.logger.ModLogger;

import java.util.*;

/**
 * Hand-written parser for the compact zoneDefinitions entry format:
 *   key:value,key:value,listKey:[value,value],objectListKey:[{key:value,key:value},{...}]
 */
public class ZoneDefinitionParser {
    private static final String LOG_PREFIX = "In 'zoneDefinitions':";
    private static final float MIN_ZONE_DENSITY = 0.001f;
    private final String text;
    private int pos;

    private ZoneDefinitionParser(String text) {
        this.text = text;
        this.pos = 0;
    }

    /** Returns null (and logs why) if the entry can't be parsed at all. */
    public static ZoneDefinition parse(String raw) {
        ZoneDefinitionParser parser = new ZoneDefinitionParser(raw.trim());
        try {
            Map<String, Object> fields = parser.parseFieldList('\0');
            return toZoneDefinition(fields, raw);
        } catch (ParseException e) {
            ModLogger.warn("{} failed to parse entry ({}): --> {}", LOG_PREFIX, e.getMessage(), raw);
            return null;
        }
    }

    // ---- Grammar ----

    private Map<String, Object> parseFieldList(char terminator) {
        Map<String, Object> fields = new LinkedHashMap<>();
        skipWhitespace();
        while (pos < text.length() && peek() != terminator) {
            String key = readKey();
            expect(':');
            skipWhitespace();
            Object value = parseValue();
            fields.put(key, value);
            skipWhitespace();
            if (pos < text.length() && peek() == ',') {
                pos++;
                skipWhitespace();
            } else {
                break;
            }
        }
        if (terminator != '\0') {
            expect(terminator);
        }
        return fields;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw new ParseException("unexpected end of input at position " + pos + ", expected a value");
        }
        char c = peek();
        if (c == '[') {
            return parseList();
        }
        if (c == '{') {
            pos++; // consume '{'; parseFieldList consumes the matching '}' itself
            return parseFieldList('}');
        }
        return parseScalar();
    }

    private List<Object> parseList() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (pos < text.length() && peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            int elementStart = pos;
            Object value = parseValue();
            String sourceText = text.substring(elementStart, pos).trim();
            list.add(new ListElement(value, sourceText));
            skipWhitespace();
            if (pos >= text.length()) {
                throw new ParseException("unterminated list at position " + pos + ", expected ']'");
            }
            if (peek() == ',') {
                pos++;
                skipWhitespace();
            } else if (peek() == ']') {
                pos++;
                break;
            } else {
                throw new ParseException("expected ',' or ']' at position " + pos + ", found '" + peek() + "'");
            }
        }
        return list;
    }

    private String parseScalar() {
        int start = pos;
        while (pos < text.length() && ",[]{}".indexOf(text.charAt(pos)) < 0) {
            pos++;
        }
        String value = text.substring(start, pos).trim();
        if (value.isEmpty()) {
            throw new ParseException("empty value at position " + start + " where one was expected");
        }
        return value;
    }

    private String readKey() {
        int start = pos;
        while (pos < text.length() && text.charAt(pos) != ':') {
            pos++;
        }
        if (pos >= text.length()) {
            throw new ParseException("expected ':' after field name starting at position " + start);
        }
        String key = text.substring(start, pos).trim();
        if (key.isEmpty()) {
            throw new ParseException("empty field name at position " + start);
        }
        return key;
    }

    private char peek() {
        return text.charAt(pos);
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw new ParseException("expected '" + c + "' at position " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    /** One element parsed out of a `[...]` list, paired with its exact source text. */
    private record ListElement(Object value, String sourceText) {}

    private static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    // ---- Map<String,Object> -> ZoneDefinition ----

    private static ZoneDefinition toZoneDefinition(Map<String, Object> fields, String raw) {
        ZoneDefinition def = new ZoneDefinition();

        for (Map.Entry<String, Object> fieldEntry : fields.entrySet()) {
            String key = fieldEntry.getKey();
            Object value = fieldEntry.getValue();
            switch (key) {
                case "id" -> def.id = asString(value, key, raw);
                case "radius" -> def.radius = asInt(value, key, raw, def.radius);
                case "count" -> def.count = asInt(value, key, raw, def.count);
                case "density" -> def.density = Math.max(MIN_ZONE_DENSITY, asFloat(value, key, raw, def.density));
                case "parentZone" -> def.parentZone = asString(value, key, raw);
                case "dimension" -> {
                    String d = asString(value, key, raw);
                    if (d != null)
                        def.dimension = d;
                }
                case "biomes" -> def.biomes = asBiomeList(value, key, raw);
                case "minDistanceFromOtherZones" -> def.minDistanceFromOtherZones =
                        asInt(value, key, raw, def.minDistanceFromOtherZones);
                case "minDistanceFromSpawn" -> def.minDistanceFromSpawn = asInt(value, key, raw , def.minDistanceFromSpawn);
                case "ensureBiomeForTheWholeZone" -> def.ensureBiomeForTheWholeZone =
                        asBoolean(value, key, raw, def.ensureBiomeForTheWholeZone);
                case "shouldFlattenTerrain" -> def.shouldFlattenTerrain =
                        asFlattenMode(value, key, raw, def.shouldFlattenTerrain);
                case "obeyParent" -> def.obeyParent = asBoolean(value, key, raw, def.obeyParent);
                case "structures" -> {
                    if (value instanceof List<?> list) {
                        def.structures = asStructureList(list);
                    } else {
                        ModLogger.warn("{} field 'structures' expected a list of "
                                + "{{id:...,density:...}} objects (ignored): --> {}", LOG_PREFIX, raw);
                    }
                }
                default -> ModLogger.warn("{} unknown field '{}' (ignored): --> {}", LOG_PREFIX, key, raw);
            }
        }

        if (def.id == null || def.id.isEmpty()) {
            ModLogger.warn("{} skipping entry with missing 'id': --> {}", LOG_PREFIX, raw);
            return null;
        }
        if (def.parentZone != null && !def.parentZone.isEmpty() && !def.biomes.isEmpty()) {
            ModLogger.warn("{} zone '{}' sets 'biomes' but has a parentZone! ignored! child zones always inherit "
                    + "their top-level zone's biomes: --> {}", LOG_PREFIX, def.id, raw);
        }

        return def;
    }

    private static String asString(Object value, String key, String raw) {
        if (value instanceof String s) {
            return s;
        }
        ModLogger.warn("{} field '{}' expected a plain value but was a list/object (ignored): --> {}", LOG_PREFIX, key, raw);
        return null;
    }

    private static int asInt(Object value, String key, String raw, int fallback) {
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                ModLogger.warn("{} field '{}' is not a whole number ('{}'); using default {}: --> {}", LOG_PREFIX, key, s, fallback, raw);
                return fallback;
            }
        }
        ModLogger.warn("{} field '{}' expected a number but was a list/object; using default {}: --> {}", LOG_PREFIX, key, fallback, raw);
        return fallback;
    }

    private static float asFloat(Object value, String key, String raw, float fallback) {
        if (value instanceof String s) {
            try {
                return Float.parseFloat(s);
            } catch (NumberFormatException e) {
                ModLogger.warn("{} field '{}' is not a number ('{}'); using default {}: --> {}", LOG_PREFIX, key, s, fallback, raw);
                return fallback;
            }
        }
        ModLogger.warn("{} field '{}' expected a number but was a list/object; using default {}: --> {}", LOG_PREFIX, key, fallback, raw);
        return fallback;
    }

    private static boolean asBoolean(Object value, String key, String raw, boolean fallback) {
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
            ModLogger.warn("{} field '{}' expected true/false but was '{}'; using default {}: --> {}", LOG_PREFIX, key, s, fallback, raw);
            return fallback;
        }
        ModLogger.warn("{} field '{}' expected true/false but was a list/object; using default {}: --> {}", LOG_PREFIX, key, fallback, raw);
        return fallback;
    }

    /** 'surface', 'underwater_surface', 'off'. Anything else warns. */
    private static FlattenMode asFlattenMode(Object value, String key, String raw, FlattenMode fallback) {
        if (value instanceof String s) {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "surface" -> {
                    return FlattenMode.SURFACE;
                }
                case "underwater_surface" -> {
                    return FlattenMode.UNDERWATER_SURFACE;
                }
                case "off" -> {
                    return FlattenMode.OFF;
                }
                default -> {
                }
            }
        }
        ModLogger.warn("{} field '{}' expected 'surface', 'underwater_surface' or true/false; using {}: --> {}",
                LOG_PREFIX, key, fallback, raw);
        return fallback;
    }

    private static List<BiomeMatcher> asBiomeList(Object value, String key, String raw) {
        List<BiomeMatcher> result = new ArrayList<>();
        if (!(value instanceof List<?> list)) {
            ModLogger.warn("{} field '{}' expected a list (e.g. [a,b,#tag]) but wasn't one (ignored): --> {}", LOG_PREFIX, key, raw);
            return result;
        }
        for (Object element : list) {
            ListElement le = (ListElement) element;
            if (!(le.value() instanceof String s)) {
                ModLogger.warn("{} field '{}' has a non-plain list entry (ignored): --> ...{}", LOG_PREFIX, key, le.sourceText());
                continue;
            }
            BiomeMatcher matcher = BiomeMatcher.parse(s);
            if (matcher == null) {
                ModLogger.warn("{} field '{}' has an invalid biome/tag id (ignored): --> ...{}", LOG_PREFIX, key, le.sourceText());
                continue;
            }
            result.add(matcher);
        }
        return result;
    }

    /** Strict true/false for a per-structure option. Absent -> false; anything else warns -> false. */
    private static boolean asEntryBoolean(Object value, String key, String structureId, String sourceText) {
        if (value == null) {
            return false;
        }
        if (value instanceof String v) {
            if (v.equalsIgnoreCase("true")) {
                return true;
            }
            if (v.equalsIgnoreCase("false")) {
                return false;
            }
        }
        ModLogger.warn("{} 'structures' entry '{}' field '{}' expected true/false; using false: --> ...{}",
                LOG_PREFIX, structureId, key, sourceText);
        return false;
    }

    private static List<ZoneDefinition.StructureEntry> asStructureList(List<?> list) {
        List<ZoneDefinition.StructureEntry> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ListElement le = (ListElement) list.get(i);
            if (!(le.value() instanceof Map<?, ?> objFields)) {
                ModLogger.warn("{} 'structures' entry #{} should be a {{id:...,density:...}} object (ignored): --> ...{}",
                        LOG_PREFIX, i, le.sourceText());
                continue;
            }

            Object idValue = objFields.get("id");
            if (!(idValue instanceof String s) || s.isEmpty()) {
                ModLogger.warn("{} 'structures' entry #{} is missing a required 'id' field and will be ignored: --> ...{}", LOG_PREFIX, i, le.sourceText());
                continue;
            }

            ZoneDefinition.StructureEntry se = new ZoneDefinition.StructureEntry();
            se.id = s;

            Object densityValue = objFields.get("density");
            if (densityValue instanceof String ds) {
                try {
                    se.density = Float.parseFloat(ds);
                } catch (NumberFormatException e) {
                    ModLogger.warn("{} 'structures' entry '{}' has a non-numeric density ('{}'); using default 1.0: --> ...{}", LOG_PREFIX, s, ds, le.sourceText());
                }
            }
            if (se.density <= 0f) {
                se.density = 1.0f;
            }

            if (objFields.get("guaranteePlacement") instanceof String gps) {
                se.guaranteePlacement = Boolean.parseBoolean(gps);
            }
            if (objFields.get("maxCount") instanceof String mcs) {
                try {
                    se.maxCount = Math.max(0, Integer.parseInt(mcs));
                } catch (NumberFormatException e) {
                    ModLogger.warn("{} 'structures' entry '{}' has a non-numeric maxCount ('{}'); ignoring it: --> ...{}", LOG_PREFIX, s, mcs, le.sourceText());
                }
            }
            if (objFields.get("spaceAround") instanceof String sas) {
                try {
                    se.spaceAround = Math.max(0, Integer.parseInt(sas));
                } catch (NumberFormatException e) {
                    ModLogger.warn("{} 'structures' entry '{}' has a non-numeric spaceAround ('{}'); ignoring it: --> ...{}", LOG_PREFIX, s, sas, le.sourceText());
                }
            }
            if (objFields.get("spreadDistance") instanceof String sds) {
                try {
                    se.spreadDistance = Integer.parseInt(sds);
                } catch (NumberFormatException e) {
                    ModLogger.warn("{} 'structures' entry '{}' has a non-numeric spreadDistance ('{}'); using default: --> ...{}", LOG_PREFIX, s, sds, le.sourceText());
                }
            }

            se.blockLeakingOutsideZone = asEntryBoolean(objFields.get("blockLeakingOutsideZone"),
                    "blockLeakingOutsideZone", s, le.sourceText());
            se.blockLeakingIntoNestedZones = asEntryBoolean(objFields.get("blockLeakingIntoNestedZones"),
                    "blockLeakingIntoNestedZones", s, le.sourceText());

            result.add(se);
        }
        return result;
    }
}
