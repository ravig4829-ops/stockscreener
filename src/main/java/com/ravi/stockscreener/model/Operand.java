package com.ravi.stockscreener.model;

import com.ravi.stockscreener.util.Operator;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
public abstract class Operand {
    private String timeframe;
    private Operator operator;
    // common getters/setters
}


