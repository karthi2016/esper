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

package com.espertech.esper.core.service;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.event.EventBeanUtility;

import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.IdentityHashMap;

public class ExpressionResultCacheForDeclaredExprLastCollImpl implements ExpressionResultCacheForDeclaredExprLastColl {

    private final IdentityHashMap<Object, SoftReference<ExpressionResultCacheEntry<EventBean[], Collection<EventBean>>>> exprDeclCacheCollection
                = new IdentityHashMap<Object, SoftReference<ExpressionResultCacheEntry<EventBean[], Collection<EventBean>>>>();

    public ExpressionResultCacheEntry<EventBean[], Collection<EventBean>> getDeclaredExpressionLastColl(Object node, EventBean[] eventsPerStream) {
        SoftReference<ExpressionResultCacheEntry<EventBean[], Collection<EventBean>>> cacheRef = exprDeclCacheCollection.get(node);
        if (cacheRef == null) {
            return null;
        }
        ExpressionResultCacheEntry<EventBean[], Collection<EventBean>> entry = cacheRef.get();
        if (entry == null) {
            return null;
        }
        return EventBeanUtility.compareEventReferences(entry.getReference(), eventsPerStream) ? entry : null;
    }

    public void saveDeclaredExpressionLastColl(Object node, EventBean[] eventsPerStream, Collection<EventBean> result) {
        EventBean[] copy = EventBeanUtility.copyArray(eventsPerStream);
        ExpressionResultCacheEntry<EventBean[], Collection<EventBean>> entry = new ExpressionResultCacheEntry<EventBean[], Collection<EventBean>>(copy, result);
        exprDeclCacheCollection.put(node, new SoftReference<ExpressionResultCacheEntry<EventBean[], Collection<EventBean>>>(entry));
    }
}
