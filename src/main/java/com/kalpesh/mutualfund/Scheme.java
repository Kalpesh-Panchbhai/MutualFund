package com.kalpesh.mutualfund;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString
public class Scheme {

    long code;

    String name;

    double pastValue;

    double currentValue;

    double totalRateOfInterest;
}
