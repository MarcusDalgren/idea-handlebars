package com.dmarcotte.handlebars.parsing;

import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

import static com.dmarcotte.handlebars.parsing.HbTokenTypes.*;

/**
 * The parser is based directly on Handlebars.yy
 * (taken from the following revision: https://github.com/wycats/handlebars.js/blob/2ea95ca08d47bb16ed79e8481c50a1c074dd676e/src/handlebars.yy)
 *
 * Methods mapping to expression in the grammar are commented with the part of the grammar they map to.
 *
 * Places where we've gone off book to make the live syntax detection a more pleasant experience are
 * marked HB_CUSTOMIZATION.  If we find bugs, or the grammar is ever updated, these are the first candidates to check.
 */
public class HbParser implements PsiParser {

    // the set of tokens which, if we encounter them while in a bad state, we'll try to
    // resume parsing from them
    private static final Set<IElementType> RECOVERY_SET;
    static {
        RECOVERY_SET = new HashSet<IElementType>();
        RECOVERY_SET.add(OPEN);
        RECOVERY_SET.add(OPEN_BLOCK);
        RECOVERY_SET.add(OPEN_ENDBLOCK);
        RECOVERY_SET.add(OPEN_INVERSE);
        RECOVERY_SET.add(OPEN_PARTIAL);
        RECOVERY_SET.add(OPEN_UNESCAPED);
        RECOVERY_SET.add(CONTENT);
    }

    @NotNull
    public ASTNode parse(IElementType root, PsiBuilder builder) {
        final PsiBuilder.Marker rootMarker = builder.mark();

        builder.setDebugMode(true);  // dm todo delete
        parseProgram(builder);

        if (!builder.eof()) {
            // jumped out of the parser prematurely... try and figure out what's tripping it up,
            // then jump back in
            // dm todo is this the right place to catch exceptions like this?

            // deal with some unexpected tokens
            IElementType tokenType = builder.getTokenType();
            int problemOffset = builder.getCurrentOffset();

            if (tokenType == OPEN_ENDBLOCK) {
                PsiBuilder.Marker badEndBlockMarker = builder.mark();
                parseCloseBlock(builder);
                badEndBlockMarker.error("No corresponding open block"); // dm todo message
            }

            if (builder.getCurrentOffset() == problemOffset) {
                // none of our error checks advanced the lexer, do it manually before we
                // try and resume parsing to avoid an infinite loop
                PsiBuilder.Marker problemMark = builder.mark();
                builder.advanceLexer();
                problemMark.error("Invalid token");
            }

            parseProgram(builder);
        }

        rootMarker.done(root);

        return builder.getTreeBuilt();
    }

    /**
     * program
     * : statements simpleInverse statements
     * | statements
     * | ""
     * ;
     */
    public static boolean parseProgram(PsiBuilder builder) {
        if (builder.eof()) {
            return true;
        }

        if (parseStatements(builder)) {
            if (parseSimpleInverse(builder)) {
                // if we have a simple inverse, must have more statements
                parseStatements(builder);
            }
        }

        return true;
    }

    /**
     * statements
     * : statement
     * | statements statement
     * ;
     */
    public static boolean parseStatements(PsiBuilder builder) {
        PsiBuilder.Marker statementsMarker = builder.mark();

        if (!parseStatement(builder)) {
            statementsMarker.error("Expected a statement"); // dm todo message
            return false;
        }

        statementsMarker.done(STATEMENTS);

        // parse any additional statements
        // dm todo this screws up on the last statement if it's busted by rolling back the check
        while (true) {
            PsiBuilder.Marker optionalStatementMarker = builder.mark();
            if (parseStatements(builder)) {
                optionalStatementMarker.drop();
            } else {
                optionalStatementMarker.rollbackTo();
                break;
            }
        }

        return true;
    }

    /**
     * statement
     * : openInverse program closeBlock
     * | openBlock program closeBlock
     * | mustache
     * | partial
     * | CONTENT
     * | COMMENT
     * ;
     */
    public static boolean parseStatement(PsiBuilder builder) {
        IElementType tokenType = builder.getTokenType();

        if (tokenType == OPEN_INVERSE) {
            PsiBuilder.Marker inverseBlockMarker = builder.mark();
            PsiBuilder.Marker lookAheadMarker = builder.mark();
            boolean isSimpleInverse = parseSimpleInverse(builder);
            lookAheadMarker.rollbackTo();

            if (isSimpleInverse) {
                /* HB_CUSTOMIZATION */
                // leave this to be caught be the simpleInverseParser
                inverseBlockMarker.rollbackTo();
                return false;
            }
            if (parseOpenInverse(builder)) {
                parseProgram(builder);
                parseCloseBlock(builder);
                inverseBlockMarker.drop();
            } else {
                inverseBlockMarker.error("Malformed inverse block");
                return false;
            }

            return true;
        }

        if (tokenType == OPEN_BLOCK) {
            PsiBuilder.Marker blockMarker = builder.mark();
            if (parseOpenBlock(builder)) {
                parseProgram(builder);
                parseCloseBlock(builder);
                blockMarker.drop();
            } else {
                blockMarker.error("Malformed block");
                return false;
            }

            return true;
        }

        if (tokenType == OPEN || tokenType == OPEN_UNESCAPED) {
            return parseMustache(builder);
        }

        if (tokenType == OPEN_PARTIAL) {
            return parsePartial(builder);
        }

        if (tokenType == CONTENT) {
            builder.advanceLexer(); // eat non-HB content
            return true;
        }

        if (tokenType == COMMENT) {
            PsiBuilder.Marker commentMark = builder.mark();
            builder.advanceLexer();
            commentMark.done(COMMENT);
            return true;
        }

        return false;
    }

    /**
     * openBlock
     * : OPEN_BLOCK inMustache CLOSE { $$ = new yy.MustacheNode($2[0], $2[1]); }
     * ;
     */
    public static boolean parseOpenBlock(PsiBuilder builder) {
        PsiBuilder.Marker openBlockMarker = builder.mark();

        if (!parseLeafToken(builder, OPEN_BLOCK)) {
            openBlockMarker.error("Expected open block");
            return false;
        }

        if (parseInMustache(builder)) {
            parseLeafTokenGreedy(builder, CLOSE);
        }

        openBlockMarker.done(BLOCK_STACHE);
        return true;
    }

    /**
     * openInverse
     * : OPEN_INVERSE inMustache CLOSE
     * ;
     */
    public static boolean parseOpenInverse(PsiBuilder builder) {
        PsiBuilder.Marker openInverseMarker = builder.mark();

        if (!parseLeafToken(builder, OPEN_INVERSE)) {
            openInverseMarker.error("Expected open inverse"); // dm todo message
            return false;
        }

        if(parseInMustache(builder)) {
            parseLeafTokenGreedy(builder, CLOSE);
        }

        openInverseMarker.done(INVERSE_STACHE);
        return true;
    }

    /**
     * closeBlock
     * : OPEN_ENDBLOCK path CLOSE { $$ = $2; }
     * ;
     */
    public static boolean parseCloseBlock(PsiBuilder builder) {
        PsiBuilder.Marker closeBlockMarker = builder.mark();

        if (!parseLeafToken(builder, OPEN_ENDBLOCK)) {
            closeBlockMarker.error("Expected close block"); // dm todo message
            return false;
        }

        if(parsePath(builder)) {
            parseLeafToken(builder, CLOSE);
        }

        closeBlockMarker.done(CLOSEBLOCK_STACHE);
        return true;
    }

    /**
     * mustache
     * : OPEN inMustache CLOSE { $$ = new yy.MustacheNode($2[0], $2[1]); }
     * | OPEN_UNESCAPED inMustache CLOSE { $$ = new yy.MustacheNode($2[0], $2[1], true); }
     * ;
     */
    public static boolean parseMustache(PsiBuilder builder) {
        PsiBuilder.Marker mustacheMarker = builder.mark();
        if (builder.getTokenType() == OPEN) {
            PsiBuilder.Marker mustacheOpenMarker = builder.mark();
            if (parseLeafToken(builder, OPEN)) {
                mustacheOpenMarker.drop();
            } else {
                mustacheMarker.rollbackTo();
            }
        } else if (builder.getTokenType() == OPEN_UNESCAPED) {
            PsiBuilder.Marker mustacheOpenMarker = builder.mark();
            if (parseLeafToken(builder, OPEN_UNESCAPED)) {
                mustacheOpenMarker.drop();
            } else {
                mustacheMarker.rollbackTo();
            }
        } else {
            mustacheMarker.error("Expected {{ or {{{"); // dm todo message
        }

        parseInMustache(builder);
        // whether our parseInMustache hit trouble or not, we absolutely must have
        // a CLOSE token, so let's find it
        parseLeafTokenGreedy(builder, CLOSE);

        mustacheMarker.done(MUSTACHE);
        return true;
    }

    /**
     * partial
     * : OPEN_PARTIAL path CLOSE { $$ = new yy.PartialNode($2); }
     * | OPEN_PARTIAL path path CLOSE { $$ = new yy.PartialNode($2, $3); }
     * ;
     */
    public static boolean parsePartial(PsiBuilder builder) {
        PsiBuilder.Marker partialMarker = builder.mark();

        if (!parseLeafToken(builder, OPEN_PARTIAL) || !parsePath(builder)) {
            partialMarker.error("Expected an ID"); // dm todo messsage
            return false;
        }

        PsiBuilder.Marker optionalPathMarker = builder.mark();
        if (parsePath(builder)) {
            optionalPathMarker.drop();
        } else {
            optionalPathMarker.rollbackTo();
        }

        parseLeafToken(builder, CLOSE);

        partialMarker.done(PARTIAL_STACHE);
        return true;
    }

    /**
     * simpleInverse
     * : OPEN_INVERSE CLOSE
     * ;
     */
    public static boolean parseSimpleInverse(PsiBuilder builder) {
        PsiBuilder.Marker simpleInverseMarker = builder.mark();

        boolean handled = parseLeafToken(builder, OPEN_INVERSE)
                && parseLeafToken(builder, CLOSE);

        if (!handled) {
            simpleInverseMarker.rollbackTo();
            return false;
        }

        simpleInverseMarker.done(SIMPLE_INVERSE);
        return true;
    }

    /**
     * inMustache
     * : path params hash { $$ = [[$1].concat($2), $3]; }
     * | path params { $$ = [[$1].concat($2), null]; }
     * | path hash { $$ = [[$1], $2]; }
     * | path { $$ = [[$1], null]; }
     * ;
     */
    public static boolean parseInMustache(PsiBuilder builder) {
        PsiBuilder.Marker inMustacheMarker = builder.mark();

        if (!parsePath(builder)) {
            inMustacheMarker.error("Expected a path");
            return false;
        }

        // try to extend the 'path' we found to 'path hash'
        PsiBuilder.Marker hashMarker = builder.mark();
        if (parseHash(builder)) {
            hashMarker.drop();
        } else {
            // not a hash... try for 'path params', followed by an attempt at 'path params hash'
            hashMarker.rollbackTo();
            PsiBuilder.Marker paramsMarker = builder.mark();
            if (parseParams(builder)) {
                PsiBuilder.Marker paramsHashMarker = builder.mark();
                int hashStartPos = builder.getCurrentOffset();
                if (parseHash(builder)) {
                    paramsHashMarker.drop();
                } else {
                    if (hashStartPos < builder.getCurrentOffset()) {
                        /* HB_CUSTOMIZATION */
                        // managed to partially parse the hash.  Don't rollback so that
                        // we can keep the errors
                        paramsHashMarker.drop();
                    } else {
                        paramsHashMarker.rollbackTo();
                    }
                }
                paramsMarker.drop();
            } else {
                paramsMarker.rollbackTo();
            }
        }

        inMustacheMarker.done(IN_MUSTACHE);
        return true;
    }

    /**
     * params
     * : params param
     * | param
     * ;
     */
    public static boolean parseParams(PsiBuilder builder) {
        PsiBuilder.Marker paramsMarker = builder.mark();

        if (!parseParam(builder)) {
            paramsMarker.error("Expected a parameter"); // dm todo message
            return false;
        }

        // parse any additional params
        while (true) {
            PsiBuilder.Marker optionalParamMarker = builder.mark();
            if (parseParam(builder)) {
                optionalParamMarker.drop();
            } else {
                optionalParamMarker.rollbackTo();
                break;
            }
        }

        paramsMarker.done(PARAMS);
        return true;
    }

    /**
     * param
     * : path
     * | STRING
     * | INTEGER
     * | BOOLEAN
     * ;
     */
    public static boolean parseParam(PsiBuilder builder) {
        PsiBuilder.Marker paramMarker = builder.mark();

        PsiBuilder.Marker pathMarker = builder.mark();
        if (parsePath(builder)) {
            pathMarker.drop();
            paramMarker.done(PARAM);
            return true;
        } else {
            pathMarker.rollbackTo();
        }

        PsiBuilder.Marker stringMarker = builder.mark();
        if (parseLeafToken(builder, STRING)) {
            stringMarker.drop();
            paramMarker.done(PARAM);
            return true;
        } else {
            stringMarker.rollbackTo();
        }

        PsiBuilder.Marker integerMarker = builder.mark();
        if (parseLeafToken(builder, INTEGER)) {
            integerMarker.drop();
            paramMarker.done(PARAM);
            return true;
        } else {
            integerMarker.rollbackTo();
        }

        PsiBuilder.Marker booleanMarker = builder.mark();
        if (parseLeafToken(builder, BOOLEAN)) {
            booleanMarker.drop();
            paramMarker.done(PARAM);
            return true;
        } else {
            booleanMarker.rollbackTo();
        }

        paramMarker.error("Expected a parameter"); // dm todo message
        return false;
    }

    /**
     * hash
     * : hashSegments { $$ = new yy.HashNode($1); }
     * ;
     */
    public static boolean parseHash(PsiBuilder builder) {
        return parseHashSegments(builder);
    }

    /**
     * hashSegments
     * : hashSegments hashSegment { $1.push($2); $$ = $1; }
     * | hashSegment { $$ = [$1]; }
     * ;
     */
    public static boolean parseHashSegments(PsiBuilder builder) {
        PsiBuilder.Marker hashSegmentsMarker = builder.mark();

        if (!parseHashSegment(builder)) {
            hashSegmentsMarker.error("Expected a hash");  // dm todo message
            return false;
        }

        // parse any additional hash segments
        while (true) {
            PsiBuilder.Marker optionalHashMarker = builder.mark();
            int hashStartPos = builder.getCurrentOffset();
            if (parseHashSegment(builder)) {
                optionalHashMarker.drop();
            } else {
                if (hashStartPos < builder.getCurrentOffset()) {
                    // HB_CUSTOMIZATION managed to partially parse this hash; don't roll back the errors
                    optionalHashMarker.drop();
                    hashSegmentsMarker.done(HASH_SEGMENTS);
                    return false;
                } else {
                    optionalHashMarker.rollbackTo();
                }
                break;
            }
        }

        hashSegmentsMarker.done(HASH_SEGMENTS);
        return true;
    }

    /**
     * hashSegment
     * : ID EQUALS path
     * | ID EQUALS STRING
     * | ID EQUALS INTEGER
     * | ID EQUALS BOOLEAN
     * ;
     *
     * Refactored to:
     * hashSegment
     * : ID EQUALS param
     */
    public static boolean parseHashSegment(PsiBuilder builder) {
        PsiBuilder.Marker hashSegmentMarker = builder.mark();
        if (!parseLeafToken(builder, ID)) {
            hashSegmentMarker.error("Expected an ID"); // dm todo message
            return false;
        }

        if (!parseLeafToken(builder, EQUALS)) {
            hashSegmentMarker.error("Expected ="); // dm todo message
            return false;
        }

        if (!parseParam(builder)) {
            hashSegmentMarker.error("Expected a parameter"); // dm todo message
            return false;
        }

        hashSegmentMarker.done(HASH_SEGMENT);
        return true;
    }

    /**
     * path
     * : pathSegments { $$ = new yy.IdNode($1); }
     * ;
     */
    public static boolean parsePath(PsiBuilder builder) {
        return parsePathSegments(builder);
    }

    /**
     * pathSegments
     * : pathSegments SEP ID { $1.push($3); $$ = $1; }
     * | ID { $$ = [$1]; }
     * ;
     *
     * Refactored to eliminate left recursion:
     *
     * pathSegments
     * : ID pathSegments'
     *
     * pathSegements'
     * : <epsilon>
     * | SEP ID pathSegments'
     */
    public static boolean parsePathSegments(PsiBuilder builder) {
        PsiBuilder.Marker pathSegmentsMarker = builder.mark();

        /* HB_CUSTOMIZATION*/
        if (isHashNextLookAhead(builder)) {
            pathSegmentsMarker.rollbackTo();
            return false;
        }

        if (!parseLeafToken(builder, ID)) {
            pathSegmentsMarker.error("Expected ID"); // dm todo message
            return false;
        }

        parsePathSegmentsPrime(builder);

        pathSegmentsMarker.done(PATH_SEGMENTS);
        return true;
    }

    public static boolean parsePathSegmentsPrime(PsiBuilder builder) {
        PsiBuilder.Marker pathSegmentsPrimeMarker = builder.mark();

        if (!parseLeafToken(builder, SEP)) {
            // the epsilon case
            pathSegmentsPrimeMarker.rollbackTo();
            return false;
        }

        /* HB_CUSTOMIZATION*/
        if (isHashNextLookAhead(builder)) {
            pathSegmentsPrimeMarker.rollbackTo();
            return false;
        }

        if (parseLeafToken(builder, ID)) {
            parsePathSegmentsPrime(builder);
        }

        pathSegmentsPrimeMarker.drop();
        return true;
    }

    /**
     *  HB_CUSTOMIZATION: the beginnings of a 'hash' have a bad habit of looking like params
     *  (i.e. test="what" parses as if "test" was a param, and then the builder is left pointing
     *  at "=" which matches no rules).
     *
     *  We check this in a couple of places to determine whether something should be parsed as
     *  a param, or left alone to grabbed by the hash parser later
     */
    private static boolean isHashNextLookAhead(PsiBuilder builder) {
        // dm todo is this the right place for this hack?
        PsiBuilder.Marker hashLookAheadMarker = builder.mark();
        boolean isHashUpcoming = parseHashSegment(builder);
        hashLookAheadMarker.rollbackTo();
        return isHashUpcoming;
    }

    private static boolean parseLeafToken(PsiBuilder builder, IElementType leafTokenType) {
        PsiBuilder.Marker leafTokenMark = builder.mark();
        if (builder.getTokenType() == leafTokenType) {
            builder.advanceLexer();
            leafTokenMark.done(leafTokenType);
            return true;
        } else if (builder.getTokenType() == INVALID) {
            while (!builder.eof() && builder.getTokenType() == INVALID) {
                builder.advanceLexer();
            }
            leafTokenMark.error("Expected " + leafTokenType);
            return false;
        } else {
            leafTokenMark.error("Expected " + leafTokenType); // TODO pretty up these message and put in the resource bundle
            return false;
        }
    }

    /**
     * HB_CUSTOMIZATION
     *
     * Eats tokens until it finds the expected token, marking errors along the way.
     *
     * Will also stop if it encounters a {@link #RECOVERY_SET} token
     */
    private static void parseLeafTokenGreedy(PsiBuilder builder, IElementType expectedToken) {
        // try to parse the token we're expecting
        if (parseLeafToken(builder, expectedToken)) {
            return;
        }

        // failed to parse expected token... chew up tokens marking this error until we encounter
        // a token which give the parser a good shot at resuming
        if (builder.getTokenType() != expectedToken) {
            builder.error("Expected " + expectedToken);
            PsiBuilder.Marker unexpectedTokensMarker = builder.mark();
            while (!builder.eof()
                    && builder.getTokenType() != expectedToken
                    && !RECOVERY_SET.contains(builder.getTokenType())) {
                builder.advanceLexer();
            }
            unexpectedTokensMarker.error("Expected " + expectedToken);
        }

        parseLeafToken(builder, expectedToken);
    }
}
