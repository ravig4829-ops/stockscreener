package com.ravi.stockscreener.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ravi.stockscreener.util.OperandDeserializer;
import com.ravi.stockscreener.util.Operator;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonDeserialize(using = OperandDeserializer.class)
@Data
@NoArgsConstructor
public abstract class Operand {
    private String timeframe;
    private Operator operator;
    // common getters/setters
}


