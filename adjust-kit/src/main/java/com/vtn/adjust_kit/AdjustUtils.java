//
//  AdjustUtils.java
//  Adjust SDK
//
//  Created by Srdjan Tubin (@2beens) on 5th May 2018.
//  Copyright (c) 2018-Present Adjust GmbH. All rights reserved.
//

package com.vtn.adjust_kit;

import java.text.NumberFormat;
import java.text.ParsePosition;

public class AdjustUtils {
    private static final NumberFormat numberFormat = NumberFormat.getInstance();

    public static boolean isNumber(String numberString) {
        if (numberString == null) {
            return false;
        }
        if (numberString.isEmpty()) {
            return false;
        }

        ParsePosition position = new ParsePosition(0);
        numberFormat.parse(numberString, position);
        return numberString.length() == position.getIndex();
    }
}
