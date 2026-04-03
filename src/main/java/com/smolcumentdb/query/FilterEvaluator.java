package com.smolcumentdb.query;

import org.bson.*;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Evaluates a MongoDB query filter document against a BSON document.
 *
 * <p>Supported operators:
 * <ul>
 *   <li>Comparison: $eq, $ne, $gt, $gte, $lt, $lte, $in, $nin</li>
 *   <li>Logical:    $and, $or, $nor, $not</li>
 *   <li>Element:    $exists, $type</li>
 *   <li>Evaluation: $regex</li>
 *   <li>Array:      $all, $elemMatch, $size</li>
 * </ul>
 *
 * <p>Supports dot-notation field paths (e.g., {@code "address.city"}).
 */
public class FilterEvaluator {

    public boolean matches(BsonDocument doc, BsonDocument filter) {
        if (filter == null || filter.isEmpty()) return true;
        for (String key : filter.keySet()) {
            BsonValue filterValue = filter.get(key);
            switch (key) {
                case "$and": {
                    for (BsonValue clause : filterValue.asArray()) {
                        if (!matches(doc, clause.asDocument())) return false;
                    }
                    break;
                }
                case "$or": {
                    boolean any = false;
                    for (BsonValue clause : filterValue.asArray()) {
                        if (matches(doc, clause.asDocument())) { any = true; break; }
                    }
                    if (!any) return false;
                    break;
                }
                case "$nor": {
                    for (BsonValue clause : filterValue.asArray()) {
                        if (matches(doc, clause.asDocument())) return false;
                    }
                    break;
                }
                default: {
                    BsonValue docValue = getNestedValue(doc, key);
                    if (!fieldMatches(docValue, filterValue)) return false;
                }
            }
        }
        return true;
    }

    /**
     * Evaluates a single field's value against its filter expression.
     * The filter value may be a plain value (implicit $eq) or an operator document.
     */
    private boolean fieldMatches(BsonValue docValue, BsonValue filterValue) {
        if (filterValue instanceof BsonDocument) {
            BsonDocument ops = (BsonDocument) filterValue;
            // Check if it contains any operator keys
            boolean hasOp = ops.keySet().stream().anyMatch(k -> k.startsWith("$"));
            if (hasOp) {
                return applyOperators(docValue, ops);
            }
        }
        // Implicit $eq
        return bsonEquals(docValue, filterValue);
    }

    private boolean applyOperators(BsonValue docValue, BsonDocument ops) {
        for (String op : ops.keySet()) {
            BsonValue param = ops.get(op);
            switch (op) {
                case "$eq":       if (!bsonEquals(docValue, param))       return false; break;
                case "$ne":       if ( bsonEquals(docValue, param))       return false; break;
                case "$gt":       if (!bsonGt(docValue, param))           return false; break;
                case "$gte":      if (!bsonGte(docValue, param))          return false; break;
                case "$lt":       if (!bsonLt(docValue, param))           return false; break;
                case "$lte":      if (!bsonLte(docValue, param))          return false; break;
                case "$in":       if (!bsonIn(docValue, param.asArray())) return false; break;
                case "$nin":      if ( bsonIn(docValue, param.asArray())) return false; break;
                case "$exists": {
                    boolean shouldExist = param.asBoolean().getValue();
                    boolean exists = docValue != null && !(docValue instanceof BsonNull);
                    if (shouldExist != exists) return false;
                    break;
                }
                case "$type": {
                    if (docValue == null) return false;
                    if (!typeMatches(docValue, param)) return false;
                    break;
                }
                case "$regex": {
                    if (docValue == null || !docValue.isString()) return false;
                    String options = ops.containsKey("$options") ? ops.getString("$options").getValue() : "";
                    Pattern pattern = compileRegex(param, options);
                    if (!pattern.matcher(docValue.asString().getValue()).find()) return false;
                    break;
                }
                case "$options": break; // handled inside $regex
                case "$not": {
                    BsonDocument notOps = param.asDocument();
                    if (applyOperators(docValue, notOps)) return false;
                    break;
                }
                case "$all": {
                    if (docValue == null || !docValue.isArray()) return false;
                    BsonArray docArr = docValue.asArray();
                    for (BsonValue required : param.asArray()) {
                        if (!docArr.contains(required)) return false;
                    }
                    break;
                }
                case "$elemMatch": {
                    if (docValue == null || !docValue.isArray()) return false;
                    boolean found = false;
                    for (BsonValue elem : docValue.asArray()) {
                        if (elem.isDocument() && matches(elem.asDocument(), param.asDocument())) {
                            found = true; break;
                        }
                    }
                    if (!found) return false;
                    break;
                }
                case "$size": {
                    if (docValue == null || !docValue.isArray()) return false;
                    int expected = param.asNumber().intValue();
                    if (docValue.asArray().size() != expected) return false;
                    break;
                }
                default:
                    // Unknown operator - ignore (permissive)
                    break;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Comparison helpers
    // -------------------------------------------------------------------------

    private boolean bsonEquals(BsonValue a, BsonValue b) {
        if (a == null || a instanceof BsonNull) return (b == null || b instanceof BsonNull);
        if (b == null || b instanceof BsonNull) return false;
        // Array: check if b is contained in a
        if (a.isArray() && !b.isArray()) {
            return a.asArray().contains(b);
        }
        return a.equals(b);
    }

    private boolean bsonGt(BsonValue a, BsonValue b) {
        return compare(a, b) > 0;
    }

    private boolean bsonGte(BsonValue a, BsonValue b) {
        return compare(a, b) >= 0;
    }

    private boolean bsonLt(BsonValue a, BsonValue b) {
        return compare(a, b) < 0;
    }

    private boolean bsonLte(BsonValue a, BsonValue b) {
        return compare(a, b) <= 0;
    }

    private boolean bsonIn(BsonValue docValue, BsonArray candidates) {
        for (BsonValue candidate : candidates) {
            if (bsonEquals(docValue, candidate)) return true;
        }
        return false;
    }

    private int compare(BsonValue a, BsonValue b) {
        if (a == null || a instanceof BsonNull) return -1;
        if (b == null || b instanceof BsonNull) return  1;
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.asNumber().doubleValue(), b.asNumber().doubleValue());
        }
        if (a.isString() && b.isString()) {
            return a.asString().getValue().compareTo(b.asString().getValue());
        }
        if (a.isDateTime() && b.isDateTime()) {
            return Long.compare(a.asDateTime().getValue(), b.asDateTime().getValue());
        }
        if (a instanceof BsonObjectId && b instanceof BsonObjectId) {
            return a.asObjectId().getValue().compareTo(b.asObjectId().getValue());
        }
        // Fallback: compare canonical JSON representations
        return a.toString().compareTo(b.toString());
    }

    private boolean typeMatches(BsonValue value, BsonValue typeParam) {
        int bsonType;
        if (typeParam.isNumber()) {
            bsonType = typeParam.asNumber().intValue();
        } else if (typeParam.isString()) {
            bsonType = bsonTypeAlias(typeParam.asString().getValue());
        } else {
            return false;
        }
        return value.getBsonType().getValue() == bsonType;
    }

    private int bsonTypeAlias(String alias) {
        switch (alias) {
            case "double":    return 1;
            case "string":    return 2;
            case "object":    return 3;
            case "array":     return 4;
            case "binData":   return 5;
            case "objectId":  return 7;
            case "bool":      return 8;
            case "date":      return 9;
            case "null":      return 10;
            case "regex":     return 11;
            case "int":       return 16;
            case "timestamp": return 17;
            case "long":      return 18;
            case "decimal":   return 19;
            default:          return -1;
        }
    }

    private Pattern compileRegex(BsonValue param, String options) {
        String pattern;
        if (param.isRegularExpression()) {
            pattern  = param.asRegularExpression().getPattern();
            if (options.isEmpty()) options = param.asRegularExpression().getOptions();
        } else {
            pattern = param.asString().getValue();
        }
        int flags = 0;
        if (options.contains("i")) flags |= Pattern.CASE_INSENSITIVE;
        if (options.contains("m")) flags |= Pattern.MULTILINE;
        if (options.contains("s")) flags |= Pattern.DOTALL;
        if (options.contains("x")) flags |= Pattern.COMMENTS;
        return Pattern.compile(pattern, flags);
    }

    // -------------------------------------------------------------------------
    // Dot-notation field access
    // -------------------------------------------------------------------------

    /**
     * Retrieves a value from a document using a dot-notation path (e.g., "a.b.c").
     * Returns null if any segment is missing.
     */
    public BsonValue getNestedValue(BsonDocument doc, String path) {
        String[] parts = path.split("\\.", 2);
        String key = parts[0];
        BsonValue value = doc.get(key);
        if (value == null) return null;
        if (parts.length == 1) return value;
        if (value.isDocument()) {
            return getNestedValue(value.asDocument(), parts[1]);
        }
        return null;
    }
}
