package com.kalpesh.mutualfund.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Scheme {

    private long code;
    private String name;
    private String fundHouse;
    private String schemeType;
    private String category;
    private String subCategory;
    private double nav;
    private String date;
    private Double cagr1Y;
}
