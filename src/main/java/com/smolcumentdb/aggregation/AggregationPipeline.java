package com.smolcumentdb.aggregation;

import com.smolcumentdb.query.FilterEvaluator;
import org.bson.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * In-memory implementation of a MongoDB aggregation pipeline.
 *
 * <p>Supported stages: $match, $sort, $limit, $skip, $project, $group, $count, $unwind, $addFields.
 */
public class AggregationPipeline {

    private final FilterEvaluator filterEvaluator = new FilterEvaluator();

    public List<BsonDocument> execute(List<BsonDocument> input, BsonArray pipeline) {
        List<BsonDocument> docs = new ArrayList<>(input);
        for (BsonValue stageVal : pipeline) {
            BsonDocument stage = stageVal.asDocument();
            String stageName = stage.keySet().iterator().next();
            BsonValue stageArg = stage.get(stageName);
            switch (stageName) {
                case "$match":   docs = stageMatch(docs, stageArg.asDocument()); break;
                case "$sort":    docs = stageSort(docs, stageArg.asDocument());  break;
                case "$limit":   docs = stageLimit(docs, stageArg.asNumber().intValue()); break;
                case "$skip":    docs = stageSkip(docs, stageArg.asNumber().intValue());  break;
                case "$project": docs = stageProject(docs, stageArg.asDocument()); break;
                case "$group":   docs = stageGroup(docs, stageArg.asDocument());   break;
                case "$count":   docs = stageCount(docs, stageArg.asString().getValue()); break;
                case "$unwind":  docs = stageUnwind(docs, stageArg); break;
                case "$addFields": docs = stageAddFields(docs, stageArg.asDocument()); break;
                default: // unknown stage — pass through
                    break;
            }
        }
        return docs;
    }

    // -------------------------------------------------------------------------
    // Stages
    // -------------------------------------------------------------------------

    private List<BsonDocument> stageMatch(List<BsonDocument> docs, BsonDocument filter) {
        return docs.stream()
                .filter(doc -> filterEvaluator.matches(doc, filter))
                .collect(Collectors.toList());
    }

    private List<BsonDocument> stageSort(List<BsonDocument> docs, BsonDocument sortSpec) {
        List<BsonDocument> sorted = new ArrayList<>(docs);
        sorted.sort((a, b) -> {
            for (Map.Entry<String, BsonValue> entry : sortSpec.entrySet()) {
                String field = entry.getKey();
                int direction = entry.getValue().asNumber().intValue(); // 1 asc, -1 desc
                BsonValue av = filterEvaluator.getNestedValue(a, field);
                BsonValue bv = filterEvaluator.getNestedValue(b, field);
                int cmp = compareValues(av, bv);
                if (cmp != 0) return direction * cmp;
            }
            return 0;
        });
        return sorted;
    }

    private List<BsonDocument> stageLimit(List<BsonDocument> docs, int n) {
        return docs.stream().limit(n).collect(Collectors.toList());
    }

    private List<BsonDocument> stageSkip(List<BsonDocument> docs, int n) {
        return docs.stream().skip(n).collect(Collectors.toList());
    }

    private List<BsonDocument> stageProject(List<BsonDocument> docs, BsonDocument projection) {
        // Determine inclusion vs exclusion mode
        boolean inclusionMode = projection.entrySet().stream()
                .filter(e -> !e.getKey().equals("_id"))
                .anyMatch(e -> e.getValue().asNumber().intValue() == 1);

        return docs.stream().map(doc -> {
            BsonDocument out = new BsonDocument();
            if (inclusionMode) {
                // Include specified fields
                for (Map.Entry<String, BsonValue> entry : projection.entrySet()) {
                    String key = entry.getKey();
                    int val = entry.getValue().asNumber().intValue();
                    if (val == 1) {
                        BsonValue v = filterEvaluator.getNestedValue(doc, key);
                        if (v != null) out.put(key, v);
                    } else if (key.equals("_id") && val == 0) {
                        // suppress _id
                    }
                }
                // Include _id unless explicitly suppressed
                if (!projection.containsKey("_id") && doc.containsKey("_id")) {
                    out.put("_id", doc.get("_id"));
                }
            } else {
                // Exclusion mode
                out = doc.clone();
                for (Map.Entry<String, BsonValue> entry : projection.entrySet()) {
                    if (entry.getValue().asNumber().intValue() == 0) {
                        out.remove(entry.getKey());
                    }
                }
            }
            return out;
        }).collect(Collectors.toList());
    }

    private List<BsonDocument> stageGroup(List<BsonDocument> docs, BsonDocument groupSpec) {
        BsonValue idExpr = groupSpec.get("_id");

        // Group docs by computed _id key
        Map<String, List<BsonDocument>> groups = new LinkedHashMap<>();
        Map<String, BsonValue> groupKeys = new LinkedHashMap<>();

        for (BsonDocument doc : docs) {
            BsonValue key = evaluateExpression(idExpr, doc);
            String keyStr = key == null ? "null" : key.toString();
            groups.computeIfAbsent(keyStr, k -> new ArrayList<>()).add(doc);
            groupKeys.putIfAbsent(keyStr, key);
        }

        List<BsonDocument> result = new ArrayList<>();
        for (Map.Entry<String, List<BsonDocument>> entry : groups.entrySet()) {
            String keyStr = entry.getKey();
            List<BsonDocument> group = entry.getValue();
            BsonDocument out = new BsonDocument("_id", groupKeys.getOrDefault(keyStr, BsonNull.VALUE));

            for (Map.Entry<String, BsonValue> acc : groupSpec.entrySet()) {
                if (acc.getKey().equals("_id")) continue;
                out.put(acc.getKey(), applyAccumulator(acc.getValue().asDocument(), group));
            }
            result.add(out);
        }
        return result;
    }

    private List<BsonDocument> stageCount(List<BsonDocument> docs, String fieldName) {
        BsonDocument out = new BsonDocument(fieldName, new BsonInt64(docs.size()));
        return Collections.singletonList(out);
    }

    private List<BsonDocument> stageUnwind(List<BsonDocument> docs, BsonValue arg) {
        String fieldPath;
        boolean preserveNull = false;
        if (arg.isString()) {
            fieldPath = arg.asString().getValue();
            if (fieldPath.startsWith("$")) fieldPath = fieldPath.substring(1);
        } else {
            BsonDocument opts = arg.asDocument();
            fieldPath = opts.getString("path").getValue();
            if (fieldPath.startsWith("$")) fieldPath = fieldPath.substring(1);
            preserveNull = opts.getBoolean("preserveNullAndEmptyArrays",
                    new BsonBoolean(false)).getValue();
        }

        List<BsonDocument> result = new ArrayList<>();
        final String field = fieldPath;
        for (BsonDocument doc : docs) {
            BsonValue val = doc.get(field);
            if (val == null || val instanceof BsonNull) {
                if (preserveNull) result.add(doc.clone());
            } else if (val.isArray()) {
                BsonArray arr = val.asArray();
                if (arr.isEmpty() && preserveNull) {
                    result.add(doc.clone());
                }
                for (BsonValue elem : arr) {
                    BsonDocument unwound = doc.clone();
                    unwound.put(field, elem);
                    result.add(unwound);
                }
            } else {
                result.add(doc.clone());
            }
        }
        return result;
    }

    private List<BsonDocument> stageAddFields(List<BsonDocument> docs, BsonDocument fields) {
        return docs.stream().map(doc -> {
            BsonDocument out = doc.clone();
            fields.forEach((key, expr) -> out.put(key, evaluateExpression(expr, out)));
            return out;
        }).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Expression evaluation
    // -------------------------------------------------------------------------

    private BsonValue evaluateExpression(BsonValue expr, BsonDocument doc) {
        if (expr == null || expr instanceof BsonNull) return BsonNull.VALUE;
        if (expr.isString()) {
            String s = expr.asString().getValue();
            if (s.startsWith("$")) {
                return filterEvaluator.getNestedValue(doc, s.substring(1));
            }
            return expr;
        }
        if (expr.isDocument()) {
            BsonDocument exprDoc = expr.asDocument();
            if (exprDoc.isEmpty()) return BsonNull.VALUE;
            String op = exprDoc.keySet().iterator().next();
            BsonValue opArg = exprDoc.get(op);
            switch (op) {
                case "$sum": {
                    if (opArg.isArray()) {
                        double total = 0;
                        for (BsonValue v : opArg.asArray()) {
                            BsonValue ev = evaluateExpression(v, doc);
                            if (ev != null && ev.isNumber()) total += ev.asNumber().doubleValue();
                        }
                        return new BsonDouble(total);
                    }
                    BsonValue val = evaluateExpression(opArg, doc);
                    return val != null && val.isNumber() ? new BsonDouble(val.asNumber().doubleValue()) : new BsonInt32(0);
                }
                case "$concat": {
                    StringBuilder sb = new StringBuilder();
                    for (BsonValue v : opArg.asArray()) {
                        BsonValue ev = evaluateExpression(v, doc);
                        if (ev == null || ev instanceof BsonNull) return BsonNull.VALUE;
                        sb.append(ev.asString().getValue());
                    }
                    return new BsonString(sb.toString());
                }
                case "$toUpper": {
                    BsonValue val = evaluateExpression(opArg, doc);
                    return val != null && val.isString() ? new BsonString(val.asString().getValue().toUpperCase()) : BsonNull.VALUE;
                }
                case "$toLower": {
                    BsonValue val = evaluateExpression(opArg, doc);
                    return val != null && val.isString() ? new BsonString(val.asString().getValue().toLowerCase()) : BsonNull.VALUE;
                }
                default:
                    return BsonNull.VALUE;
            }
        }
        return expr;
    }

    // -------------------------------------------------------------------------
    // Accumulator application (used in $group)
    // -------------------------------------------------------------------------

    private BsonValue applyAccumulator(BsonDocument accDoc, List<BsonDocument> group) {
        String op = accDoc.keySet().iterator().next();
        BsonValue fieldExpr = accDoc.get(op);
        switch (op) {
            case "$sum": {
                if (fieldExpr.isNumber()) {
                    // constant — multiply by group size
                    return new BsonInt64((long) fieldExpr.asNumber().intValue() * group.size());
                }
                double total = 0;
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    if (val != null && val.isNumber()) total += val.asNumber().doubleValue();
                }
                return new BsonDouble(total);
            }
            case "$avg": {
                if (group.isEmpty()) return BsonNull.VALUE;
                double total = 0; int count = 0;
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    if (val != null && val.isNumber()) { total += val.asNumber().doubleValue(); count++; }
                }
                return count == 0 ? BsonNull.VALUE : new BsonDouble(total / count);
            }
            case "$min": {
                BsonValue min = null;
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    if (min == null || compareValues(val, min) < 0) min = val;
                }
                return min == null ? BsonNull.VALUE : min;
            }
            case "$max": {
                BsonValue max = null;
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    if (max == null || compareValues(val, max) > 0) max = val;
                }
                return max == null ? BsonNull.VALUE : max;
            }
            case "$first": {
                if (group.isEmpty()) return BsonNull.VALUE;
                BsonValue val = evaluateExpression(fieldExpr, group.get(0));
                return val == null ? BsonNull.VALUE : val;
            }
            case "$last": {
                if (group.isEmpty()) return BsonNull.VALUE;
                BsonValue val = evaluateExpression(fieldExpr, group.get(group.size() - 1));
                return val == null ? BsonNull.VALUE : val;
            }
            case "$push": {
                BsonArray arr = new BsonArray();
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    arr.add(val == null ? BsonNull.VALUE : val);
                }
                return arr;
            }
            case "$addToSet": {
                BsonArray arr = new BsonArray();
                for (BsonDocument doc : group) {
                    BsonValue val = evaluateExpression(fieldExpr, doc);
                    if (val == null) val = BsonNull.VALUE;
                    if (!arr.contains(val)) arr.add(val);
                }
                return arr;
            }
            case "$count":
                return new BsonInt64(group.size());
            default:
                return BsonNull.VALUE;
        }
    }

    private int compareValues(BsonValue a, BsonValue b) {
        if (a == null || a instanceof BsonNull) return (b == null || b instanceof BsonNull) ? 0 : -1;
        if (b == null || b instanceof BsonNull) return 1;
        if (a.isNumber() && b.isNumber())
            return Double.compare(a.asNumber().doubleValue(), b.asNumber().doubleValue());
        if (a.isString() && b.isString())
            return a.asString().getValue().compareTo(b.asString().getValue());
        if (a.isDateTime() && b.isDateTime())
            return Long.compare(a.asDateTime().getValue(), b.asDateTime().getValue());
        return a.toString().compareTo(b.toString());
    }
}
