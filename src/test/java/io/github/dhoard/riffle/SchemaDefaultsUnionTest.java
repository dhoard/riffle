package io.github.dhoard.riffle;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Defaults of SCHEMA_SPEC.md §13 are filled through references and unions: the fill walks down the
 * node that actually describes the value, and an inserted value carries the text the schema wrote
 * with the path of the place it lands at.
 */
class SchemaDefaultsUnionTest {

    @Test
    void aDefaultInsideAUnionOfKindsIsFilled() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : kindUnion
                            string : {
                                type : string
                            }
                            map : {
                                type : map
                                keys : {
                                    a : {
                                        type : string
                                        optional : true
                                        default : set
                                    }
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(schema.applyDefaults(Riffle.parse("value : {}\n"))).isEqualTo(Riffle.parse("""
                        value : {
                            a : set
                        }
                        """));
        // The string alternative has no member to fill, so the document is handed back unchanged.
        assertThat(schema.applyDefaults(Riffle.parse("value : plain\n"))).isEqualTo(Riffle.parse("value : plain\n"));
    }

    @Test
    void aDefaultInsideATaggedUnionCaseIsFilled() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        value : {
                            type : taggedUnion
                            discriminator : kind
                            cases : {
                                inner : {
                                    type : map
                                    keys : {
                                        kind : {
                                            type : string
                                            values : [
                                                inner
                                            ]
                                        }
                                        opt : {
                                            type : string
                                            optional : true
                                            default : filled
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """);

        assertThat(schema.applyDefaults(Riffle.parse("value : {\n    kind : inner\n}\n")))
                .isEqualTo(Riffle.parse("""
                        value : {
                            kind : inner
                            opt : filled
                        }
                        """));
    }

    @Test
    void aDefaultThatIsAMapOrAnArrayKeepsItsShapeAndTakesThePathItLandsAt() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        present : {
                            type : string
                        }
                        list : {
                            type : array
                            items : {
                                type : string
                            }
                            optional : true
                            default : [
                                first
                                second
                            ]
                        }
                        nested : {
                            type : map
                            keys : {
                                inner : {
                                    type : map
                                    keys : {
                                        leaf : {
                                            type : string
                                        }
                                    }
                                }
                            }
                            optional : true
                            default : {
                                inner : {
                                    leaf : deep
                                }
                            }
                        }
                    }
                }
                """);

        RiffleObject filled = schema.applyDefaults(Riffle.parse("present : 1\n"));

        assertThat(filled).isEqualTo(Riffle.parse("""
                present : 1
                list : [
                    first
                    second
                ]
                nested : {
                    inner : {
                        leaf : deep
                    }
                }
                """));
        // An inserted value has no location of its own, but it does know where it was inserted.
        assertThat(filled.get("list").path()).contains(RifflePath.root().key("list"));
        assertThat(((RiffleArray) filled.get("list")).get(0).path())
                .contains(RifflePath.root().key("list").index(0));
        assertThat(filled.getObject("nested").getObject("inner").location()).isEmpty();
    }

    @Test
    void aDefaultReachedThroughADefinitionIsFilled() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                definitions : {
                    settings : {
                        type : map
                        keys : {
                            mode : {
                                type : string
                                optional : true
                                default : prod
                            }
                        }
                    }
                }
                root : {
                    type : map
                    keys : {
                        value : {
                            type : ref
                            definition : settings
                        }
                    }
                }
                """);

        assertThat(schema.applyDefaults(Riffle.parse("value : {}\n"))).isEqualTo(Riffle.parse("""
                        value : {
                            mode : prod
                        }
                        """));
    }

    @Test
    void anEmptyArrayNeedsNoFilling() {
        RiffleSchema schema = RiffleSchema.parse("""
                riffleSchema : 1
                root : {
                    type : map
                    keys : {
                        list : {
                            type : array
                            items : {
                                type : map
                                keys : {
                                    a : {
                                        type : string
                                        optional : true
                                        default : set
                                    }
                                }
                            }
                        }
                    }
                }
                """);
        RiffleObject document = Riffle.parse("list : [\n]\n");

        assertThat(schema.applyDefaults(document)).isEqualTo(document);
    }
}
