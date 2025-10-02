package com.ravi.stockscreener.util;

import com.ravi.stockscreener.model.Operand;
import lombok.*;

@Data
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public final class InputsOperand extends Operand {

    private final Number value; // or structured input

    public InputsOperand(Number value, String timeframe, Operator operator) {
        this.value = value;
        setTimeframe(timeframe);
        if (operator != null) {
            setOperator(operator);
        }
    }


}
