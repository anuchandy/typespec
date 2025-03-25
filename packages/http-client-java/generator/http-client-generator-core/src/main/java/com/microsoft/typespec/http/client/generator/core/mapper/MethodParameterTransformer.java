// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.typespec.http.client.generator.core.mapper;

import com.microsoft.typespec.http.client.generator.core.extension.model.codemodel.ConstantSchema;
import com.microsoft.typespec.http.client.generator.core.extension.model.codemodel.ObjectSchema;
import com.microsoft.typespec.http.client.generator.core.extension.model.codemodel.Parameter;
import com.microsoft.typespec.http.client.generator.core.extension.model.codemodel.Request;
import com.microsoft.typespec.http.client.generator.core.model.clientmodel.ClientMethodParameter;
import com.microsoft.typespec.http.client.generator.core.model.clientmodel.ClientModel;
import com.microsoft.typespec.http.client.generator.core.model.clientmodel.ClientModelProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class MethodParameterTransformer {
    private final boolean isProtocolMethod;
    private final TreeMap<ClientMethodParameter, Parameter> parameters = new TreeMap<>();

    MethodParameterTransformer(boolean isProtocolMethod) {
        this.isProtocolMethod = isProtocolMethod;
    }

    /**
     * Adds a parameter to be processed later by the {@link #process(Request)} method.
     *
     * @param clientMethodParameter the client method parameter.
     * @param parameter the source parameter from which {@code clientMethodParameter} was created.
     */
    void addParameter(ClientMethodParameter clientMethodParameter, Parameter parameter) {
        if (isProtocolMethod || parameter.getSchema() instanceof ConstantSchema) {
            return;
        }
        if (parameter.getGroupedBy() == null && parameter.getOriginalParameter() == null) {
            return;
        }
        this.parameters.put(clientMethodParameter, parameter);
    }

    List<ParameterTransformation> process(Request request) {
        final List<ParameterTransformation> transformations = new ArrayList<>(this.parameters.size());

        for (Map.Entry<ClientMethodParameter, Parameter> e : parameters.entrySet()) {
            final ClientMethodParameter clientMethodParameter = e.getKey();
            final Parameter parameter = e.getValue();

            final Mapping.Out out = processOriginalParameter(clientMethodParameter, parameter);
            final Mapping.In in = processGroupByParameter(clientMethodParameter, parameter);

            final ParameterTransformation transformation = getOrAddTransformation(transformations, out.getParameter());
            transformation.addParameterMapping(new Mapping(in, out));
        }

        for (Parameter parameter : flattenedParameters(request)) {
            final ClientMethodParameter outParameter = Mappers.getClientParameterMapper().map(parameter);
            addTransformation(transformations, outParameter);
        }

        return CollectionUtil.toImmutableList(transformations);
    }

    private static Mapping.In processGroupByParameter(ClientMethodParameter clientMethodParameter,
        Parameter parameter) {
        final ClientMethodParameter inParameter;
        final ClientModelProperty inParameterProperty;
        if (parameter.getGroupedBy() != null) {
            // Consider the following example spec:
            /*
             * parameters:
             * - name: pageNumber
             * in: query
             * schema:
             * type: integer
             * groupBy: pagination
             * - name: pageSize
             * in: query
             * schema:
             * type: integer
             * groupBy: pagination
             */
            // in this case, the service query parameters 'pageNumber' and 'pageSize' are grouped by 'pagination'.
            // The SDK Method takes an object argument with model type 'Pagination', composing two properties.
            //
            // public PagedIterable<User> list(Pagination p) { ... }
            //
            // The 'parameter' here is one of the parameters (e.g., 'pageNumber') belongs to such a group and
            // 'parameter.getGroupedBy()' provide access to the group (e.g., 'Pagination') it belongs to.
            //
            inParameter = Mappers.getClientParameterMapper().map(parameter.getGroupedBy(), false);
            final ObjectSchema groupBySchema = (ObjectSchema) parameter.getGroupedBy().getSchema();
            final ClientModel groupByModel = Mappers.getModelMapper().map(groupBySchema);
            //
            // Finds the property in the groupByModel corresponding to the 'parameter'.
            //
            // The property lookup happens using the parameter name and, as a fallback, the serialized-name. The reason
            // for using the serialized-name as a fallback is, for parameter of reserved name, on parameter, the name
            // would be renamed to "#Parameter", but on property it would be renamed to "#Property". Transformer.java
            // have handled above case, but we don't know if there is any other case.
            //
            final String name = parameter.getLanguage().getJava().getName();
            final Optional<ClientModelProperty> opt0 = findProperty(groupByModel, p -> name.equals(p.getName()));
            if (opt0.isPresent()) {
                inParameterProperty = opt0.get();
            } else {
                final String serializedName = parameter.getLanguage().getDefault().getSerializedName();
                final Optional<ClientModelProperty> opt1
                    = findProperty(groupByModel, p -> serializedName.equals(p.getSerializedName()));
                inParameterProperty = opt1.get();
            }
        } else {
            inParameter = clientMethodParameter;
            inParameterProperty = null;
        }
        return new Mapping.In(inParameter, inParameterProperty);
    }

    private static Mapping.Out processOriginalParameter(ClientMethodParameter clientMethodParameter,
        Parameter parameter) {
        final ClientMethodParameter outParameter;
        final ClientModelProperty outParameterProperty;
        final String outParameterPropertyName;
        if (parameter.getOriginalParameter() != null) {
            // Consider the following example spec:
            //
            // op add(...User): void
            // model User {
            // name: string;
            // age: uint8;
            // }
            //
            // The spread operator (...) says the SDK-Method should fatten 'User' model properties into input
            // parameters,
            //
            // public void add(String name, int age) { ... }
            //
            // while the service call requires the wire representation to be adhered to the original 'User' model.
            //
            // The 'parameter' is one of the SDK-Method parameters (e.g., 'name') that is derived from the original
            // parameter, and 'parameter.getOriginalParameter()' provides access to the original
            // out ( -> service-call) parameter, 'User'.
            //
            outParameter = Mappers.getClientParameterMapper().map(parameter.getOriginalParameter());
            outParameterProperty = Mappers.getModelPropertyMapper().map(parameter.getTargetProperty());
            outParameterPropertyName = parameter.getTargetProperty().getLanguage().getJava().getName();
        } else {
            outParameter = clientMethodParameter;
            outParameterProperty = null;
            outParameterPropertyName = null;
        }
        return new Mapping.Out(outParameter, outParameterProperty, outParameterPropertyName);
    }

    private List<Parameter> flattenedParameters(Request request) {
        // build a list of original-parameters those would have already been accounted for by process(..) when
        // processing 'this.parameters'.
        final List<Parameter> originalParameters = parameters.values()
            .stream()
            .map(Parameter::getOriginalParameter)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // build the list of flattened parameters in Request::parameters those were not processed by process(..).
        // These are the flattened model parameters with all its properties read-only.
        return request.getParameters()
            .stream()
            .filter(p -> p.isFlattened() && p.getProtocol() != null && p.getProtocol().getHttp() != null)
            .filter(p -> !originalParameters.contains(p))
            .collect(Collectors.toList());
    }

    /**
     * Inspects the list of transformations to see if a transformation already exists for the given outParameter,
     * if it does, return it, otherwise create a new one, add it to the list and return it.
     *
     * @param transformations the list of transformations.
     * @param outParameter the outParameter to get or add a transformation for.
     *
     * @return an existing or new object holding transformation for the outParameter.
     */
    private static ParameterTransformation getOrAddTransformation(List<ParameterTransformation> transformations,
        ClientMethodParameter outParameter) {
        final String name = outParameter.getName();
        return transformations.stream()
            .filter(t -> t.matchesName(name))
            .findAny()
            .orElseGet(() -> addTransformation(transformations, outParameter));
    }

    /**
     * Adds a transformation for the given outParameter to the list of transformations.
     *
     * @param transformations the list of transformations.
     * @param outParameter the outParameter to add a transformation for.
     *
     * @return a new object holding transformation for the outParameter.
     */
    private static ParameterTransformation addTransformation(List<ParameterTransformation> transformations,
        ClientMethodParameter outParameter) {
        final ParameterTransformation transformation = new ParameterTransformation(outParameter);
        transformations.add(transformation);
        return transformation;
    }

    /**
     * Finds a property in the model that matches the given predicate.
     *
     * @param model the model to search in.
     * @param predicate the predicate to match the property against.
     * @return an optional containing the property if found, or empty if not found.
     */
    private static Optional<ClientModelProperty> findProperty(ClientModel model,
        Predicate<ClientModelProperty> predicate) {
        return model.getProperties().stream().filter(predicate).findFirst();
    }

    public static final class ParameterTransformation {
        private final ClientMethodParameter outParameter;
        private final List<Mapping> parameterMappings = new ArrayList<>();

        private ParameterTransformation(ClientMethodParameter outParameter) {
            this.outParameter = outParameter;
        }

        private boolean matchesName(String name) {
            return outParameter.getName().equals(name);
        }

        private void addParameterMapping(Mapping mapping) {
            this.parameterMappings.add(mapping);
        }

        public ClientMethodParameter getOutParameter() {
            return outParameter;
        }

        public List<Mapping> getParameterMappings() {
            return CollectionUtil.toImmutableList(parameterMappings);
        }
    }

    public static final class Mapping {
        private final In in;
        private final Out out;

        private Mapping(In in, Out out) {
            this.in = in;
            this.out = out;
        }

        public ClientMethodParameter getInParameter() {
            return in.parameter;
        }

        public ClientModelProperty getInParameterProperty() {
            return in.parameterProperty;
        }

        public ClientModelProperty getOutParameterProperty() {
            return out.parameterProperty;
        }

        public String getOutParameterPropertyName() {
            return out.parameterPropertyName;
        }

        private static final class In {
            private final ClientMethodParameter parameter;
            private final ClientModelProperty parameterProperty;

            private In(ClientMethodParameter parameter, ClientModelProperty parameterProperty) {
                this.parameter = parameter;
                this.parameterProperty = parameterProperty;
            }
        }

        private static final class Out {
            private final ClientMethodParameter parameter;
            private final ClientModelProperty parameterProperty;
            private final String parameterPropertyName;

            private Out(ClientMethodParameter parameter, ClientModelProperty parameterProperty,
                String parameterPropertyName) {
                this.parameter = parameter;
                this.parameterProperty = parameterProperty;
                this.parameterPropertyName = parameterPropertyName;
            }

            private ClientMethodParameter getParameter() {
                return parameter;
            }
        }
    }
}
