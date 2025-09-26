package com.ravi.stockscreener.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

public class OperatorDeserializer extends StdDeserializer<Operator> {

    public OperatorDeserializer() {
        super(Operator.class);
    }

    @Override
    public Operator deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.getCurrentToken() == null) return null;

        String text = p.getValueAsString();
        if (text == null) return null;
        text = text.trim();

        // Try ComparisonOperator by displayName, name or symbol
        ComparisonOperator comp = ComparisonOperator.fromDisplayName(text);
        if (comp != null) return comp;
        comp = ComparisonOperator.fromSymbol(text);
        if (comp != null) return comp;

        // Try ArithmeticOperator
        ArithmeticOperator ar = ArithmeticOperator.fromSymbol(text);
        if (ar != null) return ar;

        // Try direct enum name match for safety
        try {
            return ComparisonOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            return ArithmeticOperator.valueOf(text);
        } catch (IllegalArgumentException ignored) {
        }

        throw JsonMappingException.from(p, "Unknown operator value: " + text);
    }
}


