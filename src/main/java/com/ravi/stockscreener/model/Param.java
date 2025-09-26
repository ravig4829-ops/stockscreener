package com.ravi.stockscreener.model;

import java.io.Serial;
import java.io.Serializable;


public record Param(String name, String value) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
