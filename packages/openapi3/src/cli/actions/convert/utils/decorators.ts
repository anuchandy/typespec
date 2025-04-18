import { ExtensionKey } from "@typespec/openapi";
import { Extensions, OpenAPI3Parameter, OpenAPI3Schema, Refable } from "../../../../types.js";
import { TSValue, TypeSpecDecorator } from "../interfaces.js";

const validLocations = ["header", "query", "path"];
const extensionDecoratorName = "extension";

export function getExtensions(element: Extensions): TypeSpecDecorator[] {
  const decorators: TypeSpecDecorator[] = [];

  for (const key of Object.keys(element)) {
    if (isExtensionKey(key)) {
      decorators.push({
        name: extensionDecoratorName,
        args: [key, element[key]],
      });
    }
  }

  return decorators;
}

function isExtensionKey(key: string): key is ExtensionKey {
  return key.startsWith("x-");
}

export function getParameterDecorators(parameter: OpenAPI3Parameter) {
  const decorators: TypeSpecDecorator[] = [];

  decorators.push(...getExtensions(parameter));
  decorators.push(...getDecoratorsForSchema(parameter.schema));

  const locationDecorator = getLocationDecorator(parameter);
  if (locationDecorator) decorators.push(locationDecorator);

  return decorators;
}

function getLocationDecorator(parameter: OpenAPI3Parameter): TypeSpecDecorator | undefined {
  if (!validLocations.includes(parameter.in)) return;

  const decorator: TypeSpecDecorator = {
    name: parameter.in,
    args: [],
  };

  let decoratorArgs: TypeSpecDecorator["args"][0] | undefined;
  switch (parameter.in) {
    case "header":
      decoratorArgs = getHeaderArgs(parameter);
      break;
    case "query":
      decoratorArgs = getQueryArgs(parameter);
      break;
  }

  if (decoratorArgs) {
    decorator.args.push(decoratorArgs);
  }

  return decorator;
}

function getQueryArgs(parameter: OpenAPI3Parameter): TSValue | undefined {
  const queryOptions = getNormalizedQueryOptions(parameter);
  if (Object.keys(queryOptions).length) {
    return {
      __kind: "value",
      value: `#{${Object.entries(queryOptions)
        .map(([key, value]) => {
          return `${key}: ${JSON.stringify(value)}`;
        })
        .join(", ")}}`,
    };
  }

  return;
}

type QueryOptions = { explode?: boolean; format?: string };

function getNormalizedQueryOptions({
  explode,
  style = "",
}: {
  explode?: boolean;
  style?: string;
}): QueryOptions {
  const queryOptions: QueryOptions = {};
  // In OpenAPI 3, default style is 'form', and explode is true when 'form' is the style
  if (typeof explode !== "boolean") {
    if (style === "form" || !style) {
      explode = true;
    } else {
      explode = false;
    }
  }

  // Format only emits additional data if set to one of the following:
  // ssv (spaceDelimited), pipes (pipeDelimited)
  if (style === "spaceDelimited") {
    queryOptions.format = "ssv";
  } else if (style === "pipeDelimited") {
    queryOptions.format = "pipes";
  }

  // In TypeSpec, default explode is "false"
  if (!explode && queryOptions.format) {
    queryOptions.explode = false;
  } else if (explode) {
    queryOptions.explode = true;
  }

  return queryOptions;
}

function getHeaderArgs({ explode }: OpenAPI3Parameter): TSValue | undefined {
  if (explode === true) {
    return createTSValue(`#{ explode: true }`);
  }

  return;
}

export function getDecoratorsForSchema(schema: Refable<OpenAPI3Schema>): TypeSpecDecorator[] {
  const decorators: TypeSpecDecorator[] = [];

  if ("$ref" in schema) {
    return decorators;
  }

  decorators.push(...getExtensions(schema));

  switch (schema.type) {
    case "array":
      decorators.push(...getArraySchemaDecorators(schema));
      break;
    case "integer":
    case "number":
      decorators.push(...getNumberSchemaDecorators(schema));
      break;
    case "string":
      decorators.push(...getStringSchemaDecorators(schema));
      break;
    default:
      break;
  }

  if (schema.title) {
    decorators.push({ name: "summary", args: [schema.title] });
  }

  if (schema.discriminator) {
    if (schema.oneOf || schema.anyOf) {
      decorators.push({
        name: "discriminated",
        args: [
          createTSValue(
            `#{ envelope: "none", discriminatorPropertyName: ${JSON.stringify(schema.discriminator.propertyName)} }`,
          ),
        ],
      });
    } else {
      decorators.push({ name: "discriminator", args: [schema.discriminator.propertyName] });
    }
  }

  if (schema.oneOf) {
    decorators.push(...getOneOfSchemaDecorators(schema));
  }

  return decorators;
}

function createTSValue(value: string): TSValue {
  return { __kind: "value", value };
}

function getOneOfSchemaDecorators(schema: OpenAPI3Schema): TypeSpecDecorator[] {
  return [{ name: "oneOf", args: [] }];
}

function getArraySchemaDecorators(schema: OpenAPI3Schema) {
  const decorators: TypeSpecDecorator[] = [];

  if (typeof schema.minItems === "number") {
    decorators.push({ name: "minItems", args: [schema.minItems] });
  }

  if (typeof schema.maxItems === "number") {
    decorators.push({ name: "maxItems", args: [schema.maxItems] });
  }

  return decorators;
}

function getNumberSchemaDecorators(schema: OpenAPI3Schema) {
  const decorators: TypeSpecDecorator[] = [];

  if (typeof schema.minimum === "number") {
    if (schema.exclusiveMinimum) {
      decorators.push({ name: "minValueExclusive", args: [schema.minimum] });
    } else {
      decorators.push({ name: "minValue", args: [schema.minimum] });
    }
  }

  if (typeof schema.maximum === "number") {
    if (schema.exclusiveMaximum) {
      decorators.push({ name: "maxValueExclusive", args: [schema.maximum] });
    } else {
      decorators.push({ name: "maxValue", args: [schema.maximum] });
    }
  }

  return decorators;
}

const knownStringFormats = new Set([
  "binary",
  "byte",
  "date",
  "date-time",
  "time",
  "duration",
  "uri",
]);

function getStringSchemaDecorators(schema: OpenAPI3Schema) {
  const decorators: TypeSpecDecorator[] = [];

  if (typeof schema.minLength === "number") {
    decorators.push({ name: "minLength", args: [schema.minLength] });
  }

  if (typeof schema.maxLength === "number") {
    decorators.push({ name: "maxLength", args: [schema.maxLength] });
  }

  if (typeof schema.pattern === "string") {
    decorators.push({ name: "pattern", args: [escapeRegex(schema.pattern)] });
  }

  if (typeof schema.format === "string" && !knownStringFormats.has(schema.format)) {
    decorators.push({ name: "format", args: [schema.format] });
  }

  return decorators;
}

function escapeRegex(str: string) {
  return str.replace(/\\/g, "\\\\");
}
