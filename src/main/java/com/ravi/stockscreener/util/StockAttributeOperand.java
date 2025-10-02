package com.ravi.stockscreener.util;

import com.ravi.stockscreener.model.Operand;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.ToString;

@ToString(callSuper = true)
@NoArgsConstructor(force = true)
@AllArgsConstructor
public class StockAttributeOperand extends Operand {

    public StockAttributeOperand(StockAttribute stockAttribute, String timeframe, ComparisonOperator operator) {
        this.stockAttribute = stockAttribute;
        setTimeframe(timeframe);
        if (operator != null) {
            setOperator(operator);
        }
    }

    public enum StockAttribute {
        OPEN, LOW, CLOSE, HIGH, VOLUME, CHANGE, CHANGEPERCENT
    }


    public StockAttribute stockAttribute;

}

