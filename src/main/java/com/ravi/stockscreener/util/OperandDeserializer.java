package com.ravi.stockscreener.util;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.ravi.stockscreener.model.Operand;
import com.ravi.stockscreener.model.Param;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/*
public class OperandDeserializer implements JsonDeserializer<Operand> {

    @Override
    public Operand deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json == null || json.isJsonNull()) return null;
        JsonObject obj = json.getAsJsonObject();

        // common fields
        String timeframe = obj.has("timeframe") ? obj.get("timeframe").getAsString() : null;

        // operator
        Operator operator = null;
        if (obj.has("operator") && !obj.get("operator").isJsonNull()) {
            String opText = obj.get("operator").getAsString();
            operator = parseOperator(opText);
        }

        // IndicatorOperand
        if (obj.has("indicatorType") && !obj.get("indicatorType").isJsonNull()) {
            String indicatorTypeStr = obj.get("indicatorType").getAsString();
            IndicatorType indicatorType;
            try {
                indicatorType = IndicatorType.valueOf(indicatorTypeStr);
            } catch (IllegalArgumentException ex) {
                throw new JsonParseException("Unknown indicatorType: " + indicatorTypeStr);
            }

            // params
            List<Param> params = new ArrayList<>();
            if (obj.has("params") && obj.get("params").isJsonArray()) {
                for (JsonElement paramEl : obj.getAsJsonArray("params")) {
                    JsonObject p = paramEl.getAsJsonObject();
                    String name = p.has("name") ? p.get("name").getAsString() : null;
                    String value = p.has("value") ? p.get("value").getAsString() : null;
                    params.add(new Param(name, value));
                }
            }

            // sources
            List<Source> sources = null;
            if (obj.has("source") && obj.get("source").isJsonArray()) {
                sources = new ArrayList<>();
                for (JsonElement sEl : obj.getAsJsonArray("source")) {
                    try {
                        sources.add(Source.valueOf(sEl.getAsString()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            return new IndicatorOperand(indicatorType, params, timeframe, operator, sources);
        }

        // InputsOperand
        if (obj.has("value") && !obj.get("value").isJsonNull()) {
            JsonElement valueEl = obj.get("value");
            Number val;
            if (valueEl.isJsonPrimitive() && valueEl.getAsJsonPrimitive().isNumber()) {
                val = valueEl.getAsNumber();
            } else {
                try {
                    val = Double.parseDouble(valueEl.getAsString());
                } catch (NumberFormatException ex) {
                    throw new JsonParseException("Invalid numeric value: " + valueEl.getAsString());
                }
            }
            return new InputsOperand(val, timeframe, operator);
        }

        // StockAttributeOperand
        if (obj.has("stockAttribute") && !obj.get("stockAttribute").isJsonNull()) {
            String attr = obj.get("stockAttribute").getAsString();
            StockAttributeOperand.StockAttribute sa;
            try {
                sa = StockAttributeOperand.StockAttribute.valueOf(attr);
            } catch (IllegalArgumentException ex) {
                throw new JsonParseException("Unknown stockAttribute: " + attr);
            }
            return new StockAttributeOperand(sa, timeframe, (ComparisonOperator) operator);
        }

        throw new JsonParseException("Cannot determine concrete Operand subtype from JSON: " + obj.toString());
    }

    private Operator parseOperator(String text) {
        if (text == null) return null;

        text = text.trim();

        ComparisonOperator comp = ComparisonOperator.fromDisplayName(text);
        if (comp != null) return comp;
        comp = ComparisonOperator.fromSymbol(text);
        if (comp != null) return comp;

        ArithmeticOperator ar = ArithmeticOperator.fromSymbol(text);
        if (ar != null) return ar;

        try {
            return ComparisonOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {}
        try {
            return ArithmeticOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {}

        return null;
    }
}
*/


public class OperandDeserializer extends StdDeserializer<Operand> {

    public OperandDeserializer() {
        super(Operand.class);
    }

    @Override
    public Operand deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectCodec codec = p.getCodec();
        JsonNode node = codec.readTree(p); // read the operand object

        if (node == null || node.isNull()) {
            return null;
        }

        // common fields
        String timeframe = null;
        if (node.has("timeframe") && !node.get("timeframe").isNull()) {
            timeframe = node.get("timeframe").asText();
        }

        // operator might be string or null
        Operator operator = null;
        if (node.has("operator") && !node.get("operator").isNull()) {
            String opText = node.get("operator").asText();
            operator = parseOperator(opText);
        }

        // indicatorType => IndicatorOperand
        if (node.has("indicatorType") && !node.get("indicatorType").isNull()) {
            String indicatorTypeStr = node.get("indicatorType").asText();
            IndicatorType indicatorType;
            try {
                indicatorType = IndicatorType.valueOf(indicatorTypeStr);
            } catch (IllegalArgumentException ex) {
                throw JsonMappingException.from(p, "Unknown indicatorType: " + indicatorTypeStr);
            }

            // params array -> List<Param>
            List<Param> params = new ArrayList<>();
            if (node.has("params") && node.get("params").isArray()) {
                for (JsonNode paramNode : node.get("params")) {
                    String name = paramNode.has("name") && !paramNode.get("name").isNull() ? paramNode.get("name").asText() : null;
                    String value = paramNode.has("value") && !paramNode.get("value").isNull() ? paramNode.get("value").asText() : null;
                    params.add(new Param(name, value));
                }
            }

            // source array -> List<Source>
            List<Source> sources = null;
            if (node.has("source") && node.get("source").isArray()) {
                sources = new ArrayList<>();
                for (JsonNode s : node.get("source")) {
                    String sText = s.asText();
                    try {
                        sources.add(Source.valueOf(sText));
                    } catch (IllegalArgumentException ex) {
                        // try match by symbol or displayName if needed
                        // fallback: ignore unknown source
                    }
                }
            }

            // construct IndicatorOperand using your constructor
            return new IndicatorOperand(indicatorType, params, timeframe, operator, sources);
        }

        // inputs operand: check for value
        if (node.has("value") && !node.get("value").isNull()) {
            JsonNode valueNode = node.get("value");
            Number val;
            if (valueNode.isNumber()) {
                if (valueNode.isInt()) val = valueNode.intValue();
                else if (valueNode.isLong()) val = valueNode.longValue();
                else val = valueNode.doubleValue();
            } else {
                // try parse as double
                try {
                    val = Double.parseDouble(valueNode.asText());
                } catch (NumberFormatException ex) {
                    throw JsonMappingException.from(p, "Invalid numeric value for InputsOperand: " + valueNode.asText());
                }
            }
            return new InputsOperand(val, timeframe, operator);
        }

        // stock attribute operand
        if (node.has("stockAttribute") && !node.get("stockAttribute").isNull()) {
            String attr = node.get("stockAttribute").asText();
            StockAttributeOperand.StockAttribute sa;
            try {
                sa = StockAttributeOperand.StockAttribute.valueOf(attr);
            } catch (IllegalArgumentException ex) {
                throw JsonMappingException.from(p, "Unknown stockAttribute: " + attr);
            }
            // operator should be ComparisonOperator (but we allow operator null)
            return new StockAttributeOperand(sa, timeframe, (ComparisonOperator) operator);
        }

        // If nothing matched, try fallback: treat as a plain Operand (non-instantiable) -> fail
        throw JsonMappingException.from(p, "Cannot determine concrete Operand subtype from JSON: " + node.toString());
    }

    // helper to parse operator string into appropriate Operator enum
    private Operator parseOperator(String text) {
        if (text == null) return null;
        text = text.trim();
        // try comparison
        ComparisonOperator comp = ComparisonOperator.fromDisplayName(text);
        if (comp != null) return comp;
        comp = ComparisonOperator.fromSymbol(text);
        if (comp != null) return comp;
        // try arithmetic
        ArithmeticOperator ar = ArithmeticOperator.fromSymbol(text);
        if (ar != null) return ar;
        // try direct name
        try {
            return ComparisonOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return ArithmeticOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {
        }
        return null; // unknown operator => caller will get null
    }
}



