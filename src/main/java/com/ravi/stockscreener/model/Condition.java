package com.ravi.stockscreener.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class Condition {
    private Operand mainOperand;
    private List<Operand> othersOperand;

    public Condition(Operand mainOperand) {
        this.mainOperand = mainOperand;
    }
}