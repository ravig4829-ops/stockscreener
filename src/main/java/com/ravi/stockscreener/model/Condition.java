package com.ravi.stockscreener.model;

import lombok.Data;

import java.util.List;

@Data
public class Condition {
    private Operand mainOperand;
    private List<Operand> othersOperand;
    public Condition(Operand mainOperand) {
        this.mainOperand = mainOperand;
    }
}