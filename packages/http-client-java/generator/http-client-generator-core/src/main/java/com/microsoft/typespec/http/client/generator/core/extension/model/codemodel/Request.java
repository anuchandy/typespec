// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.typespec.http.client.generator.core.extension.model.codemodel;

import com.azure.json.JsonReader;
import com.azure.json.JsonWriter;
import com.microsoft.typespec.http.client.generator.core.extension.base.util.JsonUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a request to an operation.
 */
public class Request extends Metadata {
    public static final String CONTENT_TYPE_APPLICATION_JSON = "application/json";
    public static final String CONTENT_TYPE_APPLICATION_JSON_PATCH = "application/json-patch+json";

    private List<Parameter> parameters = new ArrayList<>();
    private List<Parameter> signatureParameters = new ArrayList<>();

    /**
     * Creates a new instance of the Request class.
     */
    public Request() {
    }

    /**
     * Gets the parameter inputs to the operation.
     *
     * @return The parameter inputs to the operation.
     */
    public List<Parameter> getParameters() {
        return parameters;
    }

    /**
     * Sets the parameter inputs to the operation.
     *
     * @param parameters The parameter inputs to the operation.
     */
    public void setParameters(List<Parameter> parameters) {
        this.parameters = parameters;
    }

    /**
     * Gets the signature parameters.
     *
     * @return The signature parameters.
     */
    public List<Parameter> getSignatureParameters() {
        return signatureParameters;
    }

    /**
     * Sets the signature parameters.
     *
     * @param signatureParameters The signature parameters.
     */
    public void setSignatureParameters(List<Parameter> signatureParameters) {
        this.signatureParameters = signatureParameters;
    }

    /**
     * Gets the content type defined for the request.
     * <p>
     * the method check for mediaTypes first as that is more specific than the knownMediaType
     * if there are multiple, we'll use the generic type. if none found then uses "application/json"
     * as the default.
     * </p>
     *
     * @return the content type defined for the request.
     */
    public String getContentType() {
        final Protocols protocols = super.getProtocol();
        if (protocols != null && protocols.getHttp() != null) {
            final List<String> mediaTypes = protocols.getHttp().getMediaTypes();
            if (mediaTypes != null && mediaTypes.size() == 1) {
                return mediaTypes.get(0);
            }
            if (protocols.getHttp().getKnownMediaType() != null) {
                return protocols.getHttp().getKnownMediaType().getContentType();
            }
        }
        return CONTENT_TYPE_APPLICATION_JSON;
    }

    /**
     * Checks if the request has a specific content type.
     *
     * @param contentType the content type to check for.
     * @return true if the request has the specified content type, false otherwise.
     */
    public boolean hasContentType(String contentType) {
        final Protocols protocols = super.getProtocol();
        if (protocols == null || protocols.getHttp() == null) {
            return false;
        }
        return protocols.getHttp().getMediaTypes() != null && protocols.getHttp().getMediaTypes().contains(contentType);
    }

    @Override
    public JsonWriter toJson(JsonWriter jsonWriter) throws IOException {
        return super.writeParentProperties(jsonWriter.writeStartObject())
            .writeArrayField("parameters", parameters, JsonWriter::writeJson)
            .writeArrayField("signatureParameters", signatureParameters, JsonWriter::writeJson)
            .writeEndObject();
    }

    /**
     * Deserializes a Request instance from the JSON data.
     *
     * @param jsonReader The JSON reader to deserialize from.
     * @return A Request instance deserialized from the JSON data.
     * @throws IOException If an error occurs during deserialization.
     */
    public static Request fromJson(JsonReader jsonReader) throws IOException {
        return JsonUtils.readObject(jsonReader, Request::new, (request, fieldName, reader) -> {
            if (request.tryConsumeParentProperties(request, fieldName, reader)) {
                return;
            }

            if ("parameters".equals(fieldName)) {
                request.parameters = reader.readArray(Parameter::fromJson);
            } else if ("signatureParameters".equals(fieldName)) {
                request.signatureParameters = reader.readArray(Parameter::fromJson);
            } else {
                reader.skipChildren();
            }
        });
    }
}
