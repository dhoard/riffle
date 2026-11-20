package io.github.dhoard.riffle;

import java.util.ArrayList;
import java.util.List;

/**
 * Loading of SCHEMA_SPEC.md §9 and §11: the three phases, in order, each running only when the one
 * before it reported nothing.
 *
 * <p>The version of the schema document is checked alone, because a document of another version
 * says nothing trustworthy about its own structure. The structure phase then validates the document
 * as data against the meta-schema of §15, which catches every malformed node, unknown keyword and
 * wrongly typed keyword value and reports it as a problem of the data with the path and location of
 * the schema text. Only a structurally sound document reaches the semantic phase, which is why the
 * compiler can treat a structural surprise as a fault rather than as input.</p>
 */
final class SchemaLoader {

    /** The version keyword of §3. */
    static final String VERSION_KEYWORD = "riffleSchema";
    /** The documentation keyword of §3. */
    static final String DESCRIPTION_KEYWORD = "description";
    /** The keyword holding the node the document is validated against. */
    static final String ROOT_KEYWORD = "root";
    /** The keyword holding the shared definitions. */
    static final String DEFINITIONS_KEYWORD = "definitions";
    /** The keyword naming the type of a node. */
    static final String TYPE_KEYWORD = "type";

    static final String TYPE_STRING = "string";
    static final String TYPE_INTEGER = "integer";
    static final String TYPE_DECIMAL = "decimal";
    static final String TYPE_BOOLEAN = "boolean";
    static final String TYPE_MAP = "map";
    static final String TYPE_ARRAY = "array";
    static final String TYPE_ANY = "any";
    static final String TYPE_REF = "ref";
    static final String TYPE_TAGGED_UNION = "taggedUnion";
    static final String TYPE_KIND_UNION = "kindUnion";

    /** The only schema version this implementation loads. */
    static final String SUPPORTED_VERSION = "1";

    private SchemaLoader() {}

    /**
     * Loads a schema document through all three phases.
     *
     * @param document the parsed schema document
     * @return the loaded schema
     * @throws RiffleSchemaException with every problem the phases found
     */
    static RiffleSchema load(RiffleObject document) {
        return load(document, true);
    }

    /**
     * Loads a schema document, optionally skipping the structure phase.
     *
     * <p>The embedded meta-schema is loaded with the structure phase skipped, because the
     * structure phase of the meta-schema is the very thing self-validation asks it to check. The
     * other two phases run over it unchanged, so an embedded meta-schema that lost a definition or
     * grew a cycle fails to load.</p>
     *
     * @param document the parsed schema document
     * @param checkStructure whether the meta-schema phase runs
     * @return the loaded schema
     * @throws RiffleSchemaException with every problem the phases found
     */
    static RiffleSchema load(RiffleObject document, boolean checkStructure) {
        List<RiffleProblem> problems = new ArrayList<>();
        version(document, problems);
        if (problems.isEmpty() && checkStructure) {
            SchemaValidator.validate(MetaSchema.root(), document, problems);
        }
        SchemaNode root = null;
        if (problems.isEmpty()) {
            SchemaCompiler compiler = new SchemaCompiler(document);
            root = compiler.compile();
            problems.addAll(new SchemaAnalysis(
                            document, root, compiler.definitions(), compiler.definitionSites(), compiler.edges())
                    .analyse());
        }
        if (!problems.isEmpty()) {
            throw new RiffleSchemaException(problems);
        }
        return new RiffleSchema(root);
    }

    /** Load phase one: the version alone, and nothing else, decides for an unreadable version. */
    private static void version(RiffleObject document, List<RiffleProblem> problems) {
        RiffleValue version = document.find(VERSION_KEYWORD).orElse(null);
        if (version == null) {
            problems.add(new RiffleProblem(
                    RiffleValidationError.MISSING,
                    rootPath().key(VERSION_KEYWORD),
                    document.location().orElse(null),
                    "a `" + VERSION_KEYWORD + "` entry naming the schema version is required"));
            return;
        }
        if (!SUPPORTED_VERSION.equals(SchemaValues.text(version))) {
            problems.add(new RiffleProblem(
                    RiffleValidationError.UNSUPPORTED_VERSION,
                    rootPath().key(VERSION_KEYWORD),
                    version.location().orElse(null),
                    "this implementation loads schema version " + SUPPORTED_VERSION + ", the document declares "
                            + SchemaValues.text(version)));
        }
    }

    /** The path of a schema document itself, which every schema-side path is measured from. */
    static RifflePath rootPath() {
        return RifflePath.root();
    }

    /** The path of the {@code definitions} map inside a schema document. */
    static RifflePath definitionsPath() {
        return rootPath().key(DEFINITIONS_KEYWORD);
    }

    /** Returns the type name a kind was written as, so that a reason quotes the schema's own words. */
    static String typeName(SchemaNode.Kind kind) {
        return switch (kind) {
            case STRING -> TYPE_STRING;
            case INTEGER -> TYPE_INTEGER;
            case DECIMAL -> TYPE_DECIMAL;
            case BOOLEAN -> TYPE_BOOLEAN;
            case MAP -> TYPE_MAP;
            case ARRAY -> TYPE_ARRAY;
            case ANY -> TYPE_ANY;
            case REF -> TYPE_REF;
            case TAGGED_UNION -> TYPE_TAGGED_UNION;
            case KIND_UNION -> TYPE_KIND_UNION;
        };
    }
}
