/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.core;

import com.espertech.esper.client.ConfigurationDataCache;
import com.espertech.esper.client.ConfigurationMethodRef;
import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.core.context.util.EPStatementAgentInstanceHandle;
import com.espertech.esper.core.service.StatementContext;
import com.espertech.esper.core.start.EPStatementStartMethod;
import com.espertech.esper.epl.db.DataCache;
import com.espertech.esper.epl.db.DataCacheFactory;
import com.espertech.esper.epl.expression.core.ExprEvaluatorContext;
import com.espertech.esper.epl.expression.core.ExprValidationException;
import com.espertech.esper.epl.spec.MethodStreamSpec;
import com.espertech.esper.epl.variable.VariableMetaData;
import com.espertech.esper.epl.variable.VariableReader;
import com.espertech.esper.epl.variable.VariableService;
import com.espertech.esper.event.EventAdapterService;
import com.espertech.esper.schedule.ScheduleBucket;
import com.espertech.esper.schedule.SchedulingService;
import com.espertech.esper.util.JavaClassHelper;
import com.espertech.esper.view.HistoricalEventViewable;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Factory for method-invocation data provider streams.
 */
public class MethodPollingViewableFactory
{
    /**
     * Creates a method-invocation polling view for use as a stream that calls a method, or pulls results from cache.
     * @param streamNumber the stream number
     * @param methodStreamSpec defines the class and method to call
     * @param eventAdapterService for creating event types and events
     * @param epStatementAgentInstanceHandle for time-based callbacks
     * @param engineImportService for resolving configurations
     * @param schedulingService for scheduling callbacks in expiry-time based caches
     * @param scheduleBucket for schedules within the statement
     * @param exprEvaluatorContext expression evaluation context
     * @param variableService variable service
     * @param statementContext statement context
     * @param contextName context name
     * @param dataCacheFactory factory for cache
     * @return pollable view
     * @throws ExprValidationException if the expressions cannot be validated or the method descriptor
     * has incorrect class and method names, or parameter number and types don't match
     */
    public static HistoricalEventViewable createPollMethodView(int streamNumber,
                                                               MethodStreamSpec methodStreamSpec,
                                                               EventAdapterService eventAdapterService,
                                                               EPStatementAgentInstanceHandle epStatementAgentInstanceHandle,
                                                               EngineImportService engineImportService,
                                                               SchedulingService schedulingService,
                                                               ScheduleBucket scheduleBucket,
                                                               ExprEvaluatorContext exprEvaluatorContext,
                                                               VariableService variableService,
                                                               String contextName,
                                                               DataCacheFactory dataCacheFactory,
                                                               StatementContext statementContext)
            throws ExprValidationException
    {
        VariableMetaData variableMetaData = variableService.getVariableMetaData(methodStreamSpec.getClassName());
        MethodPollingExecStrategyEnum strategy;
        VariableReader variableReader;
        String variableName;

        // Try to resolve one of the possibly-overloaded methods (the first, any), checking that all methods by the same name return the same-type value
        Method methodReflection;
        Class declaringClass;
        Object invocationTarget;
        try
		{
            if (variableMetaData != null) {
                variableName = variableMetaData.getVariableName();
                if (variableMetaData.getContextPartitionName() != null) {
                    if (contextName == null || !contextName.equals(variableMetaData.getContextPartitionName())) {
                        throw new ExprValidationException("Variable by name '" + variableMetaData.getVariableName() + "' has been declared for context '" + variableMetaData.getContextPartitionName() + "' and can only be used within the same context");
                    }
                    strategy = MethodPollingExecStrategyEnum.TARGET_VAR_CONTEXT;
                    variableReader = null;
                    invocationTarget = null;
                }
                else {
                    variableReader = variableService.getReader(methodStreamSpec.getClassName(), EPStatementStartMethod.DEFAULT_AGENT_INSTANCE_ID);
                    if (variableMetaData.isConstant()) {
                        invocationTarget = variableReader.getValue();
                        if (invocationTarget instanceof EventBean) {
                            invocationTarget = ((EventBean) invocationTarget).getUnderlying();
                        }
                        strategy = MethodPollingExecStrategyEnum.TARGET_CONST;
                    }
                    else {
                        invocationTarget = null;
                        strategy = MethodPollingExecStrategyEnum.TARGET_VAR;
                    }
                }
                methodReflection = engineImportService.resolveNonStaticMethodOverloadChecked(variableMetaData.getType(), methodStreamSpec.getMethodName());
            }
            else {
                methodReflection = engineImportService.resolveMethodOverloadChecked(methodStreamSpec.getClassName(), methodStreamSpec.getMethodName());
                invocationTarget = null;
                variableReader = null;
                variableName = null;
                strategy = MethodPollingExecStrategyEnum.TARGET_CONST;
            }
            declaringClass = methodReflection.getDeclaringClass();
		}
        catch(ExprValidationException e)
        {
            throw e;
        }
		catch(Exception e)
		{
			throw new ExprValidationException(e.getMessage(), e);
		}

        // Determine object type returned by method
        Class beanClass = methodReflection.getReturnType();
        if ((beanClass == void.class) || (beanClass == Void.class) || (JavaClassHelper.isJavaBuiltinDataType(beanClass)))
        {
            throw new ExprValidationException("Invalid return type for static method '" + methodReflection.getName() + "' of class '" + methodStreamSpec.getClassName() + "', expecting a Java class");
        }
        if (methodReflection.getReturnType().isArray())
        {
            beanClass = methodReflection.getReturnType().getComponentType();
        }

        boolean isCollection = JavaClassHelper.isImplementsInterface(beanClass, Collection.class);
        Class collectionClass = null;
        if (isCollection) {
            collectionClass = JavaClassHelper.getGenericReturnType(methodReflection, true);
            beanClass = collectionClass;
        }

        boolean isIterator = JavaClassHelper.isImplementsInterface(beanClass, Iterator.class);
        Class iteratorClass = null;
        if (isIterator) {
            iteratorClass = JavaClassHelper.getGenericReturnType(methodReflection, true);
            beanClass = iteratorClass;
        }

        // If the method returns a Map, look up the map type
        Map<String, Object> mapType = null;
        String mapTypeName = null;
        if ( (JavaClassHelper.isImplementsInterface(methodReflection.getReturnType(), Map.class)) ||
             (methodReflection.getReturnType().isArray() && JavaClassHelper.isImplementsInterface(methodReflection.getReturnType().getComponentType(), Map.class)) ||
              (isCollection && JavaClassHelper.isImplementsInterface(collectionClass, Map.class)) ||
              (isIterator && JavaClassHelper.isImplementsInterface(iteratorClass, Map.class)))
        {
            MethodMetadataDesc metadata;
            if (variableMetaData != null) {
                metadata = getCheckMetadataVariable(methodStreamSpec.getMethodName(), variableMetaData, variableReader, engineImportService, Map.class);
            }
            else {
                metadata = getCheckMetadataNonVariable(methodStreamSpec.getMethodName(), methodStreamSpec.getClassName(), engineImportService, Map.class);
            }
            mapTypeName = metadata.getTypeName();
            mapType = (Map<String, Object>) metadata.getTypeMetadata();
        }

        // If the method returns an Object[] or Object[][], look up the type information
        LinkedHashMap<String, Object> oaType = null;
        String oaTypeName = null;
        if (methodReflection.getReturnType() == Object[].class ||
                methodReflection.getReturnType() == Object[][].class ||
            (isCollection && collectionClass == Object[].class) ||
            (isIterator && iteratorClass == Object[].class))
        {
            MethodMetadataDesc metadata;
            if (variableMetaData != null) {
                metadata = getCheckMetadataVariable(methodStreamSpec.getMethodName(), variableMetaData, variableReader, engineImportService, LinkedHashMap.class);
            }
            else {
                metadata = getCheckMetadataNonVariable(methodStreamSpec.getMethodName(), methodStreamSpec.getClassName(), engineImportService, LinkedHashMap.class);
            }
            oaTypeName = metadata.getTypeName();
            oaType = (LinkedHashMap<String, Object>) metadata.getTypeMetadata();
        }

        // Determine event type from class and method name
        EventType eventType;
        if (mapType != null) {
            eventType = eventAdapterService.addNestableMapType(mapTypeName, mapType, null, false, true, true, false, false);
        }
        else if (oaType != null) {
            eventType = eventAdapterService.addNestableObjectArrayType(oaTypeName, oaType, null, false, true, true, false, false, false, null);
        }
        else {
            eventType = eventAdapterService.addBeanType(beanClass.getName(), beanClass, false, true, true);
        }

        ConfigurationMethodRef configCache = engineImportService.getConfigurationMethodRef(declaringClass.getName());
        if (configCache == null) {
            configCache = engineImportService.getConfigurationMethodRef(declaringClass.getSimpleName());
        }
        ConfigurationDataCache dataCacheDesc = (configCache != null) ? configCache.getDataCacheDesc() : null;
        DataCache dataCache = dataCacheFactory.getDataCache(dataCacheDesc, statementContext, epStatementAgentInstanceHandle, schedulingService, scheduleBucket, streamNumber);

        MethodPollingViewableMeta meta = new MethodPollingViewableMeta(declaringClass, mapType, oaType, invocationTarget, strategy, isCollection, isIterator, variableReader, variableName);

        return new MethodPollingViewable(variableMetaData == null, methodReflection.getDeclaringClass(), methodStreamSpec, methodStreamSpec.getExpressions(), dataCache, eventType, exprEvaluatorContext, meta);
    }

    private static MethodMetadataDesc getCheckMetadataVariable(String methodName, VariableMetaData variableMetaData, VariableReader variableReader, EngineImportService engineImportService, Class metadataClass)
            throws ExprValidationException
    {
        Method typeGetterMethod = getRequiredTypeGetterMethodCanNonStatic(methodName, null, variableMetaData.getType(), engineImportService, metadataClass);

        if (Modifier.isStatic(typeGetterMethod.getModifiers())) {
            return invokeMetadataMethod(null, variableMetaData.getClass().getSimpleName(), typeGetterMethod);
        }

        // if the metadata is not a static method and we don't have an instance this is a problem
        String messagePrefix = "Failed to access variable method invocation metadata: ";
        if (variableReader == null) {
            throw new ExprValidationException(messagePrefix + "The metadata method is an instance method however the variable is contextual, please declare the metadata method as static or remove the context declaration for the variable");
        }

        Object value = variableReader.getValue();
        if (value == null) {
            throw new ExprValidationException(messagePrefix + "The variable value is null and the metadata method is an instance method");
        }

        if (value instanceof EventBean) {
            value = ((EventBean) value).getUnderlying();
        }
        return invokeMetadataMethod(value, variableMetaData.getClass().getSimpleName(), typeGetterMethod);
    }


    private static MethodMetadataDesc getCheckMetadataNonVariable(String methodName, String className, EngineImportService engineImportService, Class metadataClass) throws ExprValidationException {
        Method typeGetterMethod = getRequiredTypeGetterMethodCanNonStatic(methodName, className, null, engineImportService, metadataClass);
        return invokeMetadataMethod(null, className, typeGetterMethod);
    }

    private static Method getRequiredTypeGetterMethodCanNonStatic(String methodName, String classNameWhenNoClass, Class clazzWhenAvailable, EngineImportService engineImportService, Class metadataClass)
            throws ExprValidationException
    {
        Method typeGetterMethod;
        String getterMethodName = methodName + "Metadata";
        try {
            if (clazzWhenAvailable != null) {
                typeGetterMethod = engineImportService.resolveMethod(clazzWhenAvailable, getterMethodName, new Class[0], new boolean[0], new boolean[0]);
            }
            else {
                typeGetterMethod = engineImportService.resolveMethodOverloadChecked(classNameWhenNoClass, getterMethodName, new Class[0], new boolean[0], new boolean[0]);
            }
        }
        catch(Exception e) {
            throw new ExprValidationException("Could not find getter method for method invocation, expected a method by name '" + getterMethodName + "' accepting no parameters");
        }

        boolean fail;
        if (metadataClass.isInterface()) {
            fail = !JavaClassHelper.isImplementsInterface(typeGetterMethod.getReturnType(), metadataClass);
        }
        else {
            fail = typeGetterMethod.getReturnType() != metadataClass;
        }
        if (fail) {
            throw new ExprValidationException("Getter method '" + typeGetterMethod.getName() + "' does not return " + JavaClassHelper.getClassNameFullyQualPretty(metadataClass));
        }

        return typeGetterMethod;
    }

    private static MethodMetadataDesc invokeMetadataMethod(Object target, String className, Method typeGetterMethod)
            throws ExprValidationException
    {
        Object resultType;
        try {
            resultType = typeGetterMethod.invoke(target);
        }
        catch (Exception e) {
            throw new ExprValidationException("Error invoking metadata getter method for method invocation, for method by name '" + typeGetterMethod.getName() + "' accepting no parameters: " + e.getMessage(), e);
        }
        if (resultType == null) {
            throw new ExprValidationException("Error invoking metadata getter method for method invocation, method returned a null value");
        }

        return new MethodMetadataDesc(className + "." + typeGetterMethod.getName(), resultType);
    }

    public static class MethodMetadataDesc {
        private final String typeName;
        private final Object typeMetadata;

        public MethodMetadataDesc(String typeName, Object typeMetadata) {
            this.typeName = typeName;
            this.typeMetadata = typeMetadata;
        }

        public String getTypeName() {
            return typeName;
        }

        public Object getTypeMetadata() {
            return typeMetadata;
        }
    }
}
