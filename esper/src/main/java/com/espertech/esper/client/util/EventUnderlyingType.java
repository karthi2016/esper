/*
 * *************************************************************************************
 *  Copyright (C) 2006-2015 EsperTech, Inc. All rights reserved.                       *
 *  http://www.espertech.com/esper                                                     *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.client.util;

import com.espertech.esper.event.avro.AvroConstantsNoDep;

import java.util.Map;

/**
 * Enumeration of event representation.
 */
public enum EventUnderlyingType
{
    /**
     * Event representation is object-array (Object[]).
     */
    OBJECTARRAY,

    /**
     * Event representation is Map (any java.util.Map interface implementation).
     */
    MAP,

    /**
     * Event representation is Avro (GenericData.Record).
     */
    AVRO;

    private final static String OA_TYPE_NAME = Object[].class.getName();
    private final static String MAP_TYPE_NAME = Map.class.getName();
    private final static String AVRO_TYPE_NAME = AvroConstantsNoDep.GENERIC_RECORD_CLASSNAME;

    static {
        OBJECTARRAY.underlyingClassName = OA_TYPE_NAME;
        MAP.underlyingClassName = MAP_TYPE_NAME;
        AVRO.underlyingClassName = AVRO_TYPE_NAME;
    }

    private String underlyingClassName;

    /**
     * Returns the default property resolution style.
     * @return is the case-sensitive resolution
     */
    public static EventUnderlyingType getDefault()
    {
        return MAP;
    }

    public String getUnderlyingClassName() {
        return underlyingClassName;
    }
}
