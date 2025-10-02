package com.ravi.stockscreener.util;

import com.ravi.stockscreener.model.Operand;
import com.ravi.stockscreener.model.Param;
import lombok.*;

import java.util.List;

@ToString(callSuper = true) // to include Operand fields in toString()
@EqualsAndHashCode(callSuper = true)
@Data
public class IndicatorOperand extends Operand {
    private final IndicatorType indicatorType;
    private final List<Param> params;
    private List<Source> source;

    public IndicatorOperand(IndicatorType indicatorType, List<Param> params, String timeframe, Operator operator, List<Source> source) {
        this.indicatorType = indicatorType;
        this.params = params;
        setTimeframe(timeframe);
        if (source != null) {
            this.source = source;
        }
        if (operator != null) {
            setOperator(operator);
        }
    }
}