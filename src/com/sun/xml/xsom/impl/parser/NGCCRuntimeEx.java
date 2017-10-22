/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.xml.xsom.impl.parser;

import com.sun.xml.xsom.XSDeclaration;
import com.sun.xml.xsom.XmlString;
import com.sun.xml.xsom.XSSimpleType;
import com.sun.xml.xsom.impl.ForeignAttributesImpl;
import com.sun.xml.xsom.impl.SchemaImpl;
import com.sun.xml.xsom.impl.UName;
import com.sun.xml.xsom.impl.Const;
import com.sun.xml.xsom.impl.parser.state.NGCCRuntime;
import com.sun.xml.xsom.impl.parser.state.Schema;
import com.sun.xml.xsom.impl.util.Uri;
import com.sun.xml.xsom.parser.AnnotationParser;
import org.relaxng.datatype.ValidationContext;
import org.xml.sax.Attributes;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.LocatorImpl;

import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.logging.Level;

/**
 * NGCCRuntime extended with various utility methods for
 * parsing XML Schema.
 *
 * @author Kohsuke Kawaguchi (kohsuke.kawaguchi@sun.com)
 */
public class NGCCRuntimeEx extends NGCCRuntime implements PatcherManager {

    public static final String XMLSchemaNSURI = "http://www.w3.org/2001/XMLSchema";
    private static final Map<ParserContext, Set<String>> parserToImportedSchemaLocations = new HashMap<ParserContext, Set<String>>();
    /** coordinator. */
    public final ParserContext parser;
    /**
     * Keep the local name of elements encountered so far.
     * This information is passed to AnnotationParser as
     * context information
     */
    private final Stack<String> elementNames = new Stack<String>();
    /**
     * Points to the schema document (the parser of it) that included/imported
     * this schema.
     */
    private final NGCCRuntimeEx referer;
    /** The schema currently being parsed. */
    public SchemaImpl currentSchema;
    /** The @finalDefault value of the current schema. */
    public int finalDefault = 0;
    /** The @blockDefault value of the current schema. */
    public int blockDefault = 0;
    /**
     * The @elementFormDefault value of the current schema.
     * True if local elements are qualified by default.
     */
    public boolean elementFormDefault = false;
    /**
     * The @attributeFormDefault value of the current schema.
     * True if local attributes are qualified by default.
     */
    public boolean attributeFormDefault = false;
    /**
     * True if the current schema is in a chameleon mode.
     * This changes the way QNames are interpreted.
     * <p>
     * Life is very miserable with XML Schema, as you see.
     */
    public boolean chameleonMode = false;
    /**
     * Points to the {@link SchemaDocumentImpl} that represents the
     * schema document being parsed.
     */
    public SchemaDocumentImpl document;
    /**
     * The document InputSource that us processed for the schema document.
     */
    private InputSource documentSource;
    private Context currentContext = null;

    public NGCCRuntimeEx(ParserContext _parser) {
        this(_parser, false, null);
    }


    private NGCCRuntimeEx(ParserContext _parser, boolean chameleonMode, NGCCRuntimeEx referer) {
        this.parser = _parser;
        this.chameleonMode = chameleonMode;
        this.referer = referer;

        // set up the default namespace binding
        currentContext = new Context("", "", null);
        currentContext = new Context("xml", "http://www.w3.org/XML/1998/namespace", currentContext);
    }

    public static boolean ignorableDuplicateComponent(XSDeclaration c) {
        if (c.getTargetNamespace().equals(Const.schemaNamespace)) {
            if (c instanceof XSSimpleType)
                // hide artificial "double definitions" on simple types
                return true;
            if (c.isGlobal() && c.getName().equals("anyType"))
                return true; // ditto for anyType
        }
        return false;
    }

    public void checkDoubleDefError(XSDeclaration c) throws SAXException {
        if (c == null || ignorableDuplicateComponent(c)) return;

        reportError(Messages.format(Messages.ERR_DOUBLE_DEFINITION, c.getName()));
        reportError(Messages.format(Messages.ERR_DOUBLE_DEFINITION_ORIGINAL), c.getLocator());
    }

    /* registers a patcher that will run after all the parsing has finished. */
    public void addPatcher(Patch patcher) {
        parser.patcherManager.addPatcher(patcher);
    }

    public void addErrorChecker(Patch patcher) {
        parser.patcherManager.addErrorChecker(patcher);
    }

    public void reportError(String msg, Locator loc) throws SAXException {
        parser.patcherManager.reportError(msg, loc);
    }

    public void reportError(String msg) throws SAXException {
        reportError(msg, getLocator());
    }

    // ****************** Import testing *******************************

    /**
     * Resolves relative URI found in the document.
     *
     * @param namespaceURI The Namespace fragment of the xsd:import statement or the current schema targetNamespace if xsd:include
     * @param relativeURI  value of the schemaLocation attribute, can be null if it is a publicId lookup for xsd:import only
     *
     * @return non-null if {@link EntityResolver} returned an {@link InputSource},
     * or if the relativeUri parameter seems to be pointing to something.
     * Otherwise it returns null, in which case import/include should be abandoned.
     */
    private InputSource resolveRelativeURL(String namespaceURI, String relativeURI) throws SAXException {

        try {

            // if it is a xsd:import with namespace attribute only, it is a publicId lookup
            if (namespaceURI != null && relativeURI == null) {
                InputSource source = tryResolveResource(namespaceURI);
                if (source != null) {
                    source.setPublicId(namespaceURI);
                    return source;
                }
            }

            // try relativeURI as a publicId first, we trust the entity resolver to do its work !!!
            InputSource source = tryResolveResource(relativeURI);
            if (source != null) {
                source.setPublicId(relativeURI);
                return source;
            }

            // now we continue as normal
            source = tryResolveResource(namespaceURI, relativeURI);
            if (source != null) {
                return source;
            }

            // do the original dark magic
            // still no luck, lets see if the systemId is a URI
            if (relativeURI != null) {

                String resolvingSystemId = getLocator().getSystemId();

                if (resolvingSystemId == null)
                    // if the base URI is not available, the document systemId is better than nothing.
                    resolvingSystemId = documentSource.getSystemId();

                String systemId = Uri.resolve(resolvingSystemId, relativeURI);
                String normalizedSystemId = URI.create(systemId).normalize().toASCIIString();
                source = tryResolveResource(namespaceURI, normalizedSystemId);

                if (source != null) {
                    return source;
                } else {
                    return new InputSource(systemId);
                }
            }

            return null;

        } catch (IOException e) {
            SAXParseException se = new SAXParseException(e.getMessage(), getLocator(), e);
            parser.errorHandler.error(se);
            return null;
        }

    }

    private InputSource tryResolveResource(String publicId) throws SAXException {
        return tryResolveResource(publicId, null);
    }

    private InputSource tryResolveResource(String publicId, String systemId) throws SAXException {
        try {
            if (parser.getEntityResolver() != null) {
                return parser.getEntityResolver().resolveEntity(publicId, systemId);
            }
        } catch (IOException e) {
            SAXParseException se = new SAXParseException(e.getMessage(), getLocator(), e);
            parser.errorHandler.warning(se);
        }
        return null;
    }

    private String traceIncludeMessage(String schemaLocation) {
        return new StringBuilder("<xsd:include")
                .append(" schemaLocation=\"")
                .append(schemaLocation)
                .append("\" />")
                .toString();
    }

    /**
     * Builds a log message we can use to output information about what happened.
     * TODO check if we can get this info from the locator
     *
     * @param namespace      The optional namespace if provided
     * @param schemaLocation The location of the schema
     *
     * @return What the import most likely looked like
     */
    private String traceImportMessage(String namespace, String schemaLocation) {

        StringBuilder logBuilder = new StringBuilder("<xsd:import");

        if (namespace != null)
            logBuilder.append(" namespace=\"")
                    .append(namespace)
                    .append("\"");

        logBuilder.append(" schemaLocation=\"")
                .append(schemaLocation)
                .append("\" />");

        return logBuilder.toString();

    }

    /** Includes the specified schema. */
    public void includeSchema(String schemaLocation) throws SAXException {

        InputSource sourceToBeIncluded = resolveRelativeURL(null, schemaLocation);

        // if source == null, we can't locate this document. Let's just hope that we already
        // have the schema components for this schema or we will receive them in the future.
        if (sourceToBeIncluded == null) {
            getErrorHandler().warning(new SAXParseException("Unable to include schemaLocation " + schemaLocation, getLocator()));
            return;
        }

//        if (wasLoaded(sourceToBeIncluded)) {
//            log.info("Skipping " + traceIncludeMessage(schemaLocation));
//            return;
//        }

        NGCCRuntimeEx runtime = new NGCCRuntimeEx(parser, chameleonMode, this);
        runtime.currentSchema = this.currentSchema;
        runtime.blockDefault = this.blockDefault;
        runtime.finalDefault = this.finalDefault;

        if (schemaLocation == null) {
            SAXParseException e = new SAXParseException(
                    Messages.format(Messages.ERR_MISSING_SCHEMALOCATION), getLocator());
            parser.errorHandler.fatalError(e);
            throw e;
        }

        // we pass the new schema to include and also the namespace we expect
        runtime.parseEntity(sourceToBeIncluded, true, currentSchema.getTargetNamespace(), getLocator());

        log.info("Loaded " + traceIncludeMessage(schemaLocation));

    }

//    private void markSchemaLocationProcessed(InputSource source) {
//
//        StringBuilder sb = new StringBuilder("Mark loaded\n");
//        if (source.getPublicId() != null) {
//            sb.append("  > publicId ").append(source.getPublicId()).append("\n");
//        }
//        if (source.getSystemId() != null) {
//            sb.append("  > systemId ").append(source.getSystemId()).append("\n");
//        }
//        log.info(sb.toString());
//
//        if (source.getPublicId() != null) {
//            getLoadedSchemaLocations().add(source.getPublicId());
//        }
//        getLoadedSchemaLocations().add(source.getSystemId());
//    }
//
//    private boolean wasLoaded(InputSource source) {
//
//        Set<String> locations = getLoadedSchemaLocations();
//
//        // we may have tracked a publicId of a source that might match
//        // there are some entity resolvers out there that return a wrapped InputSource
//        // which unfortunately produces a valid stream but not the underlying location
//        // of the resource
//        return source.getPublicId() != null && locations.contains(source.getPublicId()) || getLoadedSchemaLocations().contains(source.getSystemId());
//
//    }
//
//    private Set<String> getLoadedSchemaLocations() {
//        // TODO review this looks weird
//        Set<String> result = parserToImportedSchemaLocations.get(parser);
//        if (result == null) {
//            result = new HashSet<String>();
//            parserToImportedSchemaLocations.put(parser, result);
//        }
//
//        return result;
//
//    }

    /** Imports the specified schema. */
    public void importSchema(String namespaceURI, String schemaLocation) throws SAXException {

        InputSource sourceToBeImported = resolveRelativeURL(namespaceURI, schemaLocation);

        // if source == null, we can't locate this document. Let's just hope that we already
        // have the schema components for this schema or we will receive them in the future.
        if (sourceToBeImported == null) {
            getErrorHandler().warning(new SAXParseException("Unable to import schemaLocation " + schemaLocation, getLocator()));
            return;
        }

//        if (wasLoaded(sourceToBeImported)) {
//            log.info("Skipping " + traceImportMessage(namespaceURI, schemaLocation));
//            return;
//        }

        NGCCRuntimeEx newRuntime = new NGCCRuntimeEx(parser, false, this);
        newRuntime.parseEntity(sourceToBeImported, false, namespaceURI, getLocator());
        log.info("Loaded " + traceImportMessage(namespaceURI, schemaLocation));

    }

    /**
     * Called when a new document is being parsed and checks
     * if the document has already been parsed before.
     * <p>
     * <p>
     * Used to avoid recursive inclusion. Note that the same
     * document will be parsed multiple times if they are for different
     * target namespaces.
     * <p>
     * <h2>Document Graph Model</h2>
     * <p>
     * The challenge we are facing here is that you have a graph of
     * documents that reference each other. Each document has an unique
     * URI to identify themselves, and references are done by using those.
     * The graph may contain cycles.
     * <p>
     * <p>
     * Our goal here is to parse all the documents in the graph, without
     * parsing the same document twice. This method implements this check.
     * <p>
     * <p>
     * One complication is the chameleon schema; a document can be parsed
     * multiple times if they are under different target namespaces.
     * <p>
     * <p>
     * Also, note that when you resolve relative URIs in the @schemaLocation,
     * their base URI is *NOT* the URI of the document.
     *
     * @return true if the document has already been processed and thus
     * needs to be skipped.
     */
    public boolean hasAlreadyBeenRead() {
        // FIXME this needs to be retrofitted with the new schema skipping capability #wasLoaded and #markSchemaLocationProcessed
        log.info("Legacy test schema read " + documentSource.getSystemId() != null ? documentSource.getSystemId() : "no systemId");

        assert document == null : "schema document has already been set, internal implementation issue";

        String id = null;
        if (documentSource.getSystemId() != null) {
            id = documentSource.getSystemId();
        } else if (documentSource.getPublicId() != null) {
            id = documentSource.getPublicId();
        } else {
            // FIXME we really should be blowing up here due to the use of some random InputSource that can't be bothered to supply a document id
        }

        // FIXME this should be the referrer namespace not the current
        String namespace = currentSchema.getTargetNamespace();
        // this.chameleonMode this.referrer this.currentSchema
        boolean alreadyLoaded = parser.hasAlreadyBeenRead(namespace, id);
        if (alreadyLoaded) {
            document = parser.getSchemaDocument(namespace, id);
        } else {
            document = new SchemaDocumentImpl(currentSchema, id);
            parser.addSchemaDocument(namespace, document);
        }

        assert document != null : "schema document was not created";

        if (referer != null) {
            assert referer.document != null : "referer " + referer.documentSource.getSystemId() + " has docIdentity==null";
            referer.document.references.add(this.document);
            this.document.referers.add(referer.document);
        }

        return alreadyLoaded;
    }

    /**
     * Parses the specified entity.
     *
     * @param importLocation The source location of the import/include statement.
     *                       Used for reporting errors.
     */
    public void parseEntity(InputSource source, boolean includeMode, String expectedNamespace, Locator importLocation)
            throws SAXException {

        documentSource = source;

        if (log.isLoggable(Level.FINER)) {
            StringBuilder sb = new StringBuilder("Parse \n");

            if (importLocation.getPublicId() != null) {
                sb.append("  > locator publicId ").append(importLocation.getPublicId()).append("\n");
            }

            if (importLocation.getSystemId() != null) {
                sb.append("  > locator systemId ").append(importLocation.getSystemId()).append("\n");
            }

            if (source.getPublicId() != null) {
                sb.append("  > publicId ").append(source.getPublicId()).append("\n");
            }
            if (source.getSystemId() != null) {
                sb.append("  > systemId ").append(source.getSystemId()).append("\n");
            }
            sb.append("  > context\n");
            for (String loc : parser.getSchemaDocuments().keySet()) {
                sb.append("      -->  ").append(loc).append("\n");
            }
            log.finer(sb.toString());
        }

//        markSchemaLocationProcessed(source);

        try {
            Schema s = new Schema(this, includeMode, expectedNamespace);
            setRootHandler(s);
            try {
                parser.parser.parse(source, this, getErrorHandler(), parser.getEntityResolver());
            } catch (IOException fnfe) {
                SAXParseException se = new SAXParseException(fnfe.toString(), importLocation, fnfe);
                parser.errorHandler.warning(se);
            }
        } catch (SAXException e) {
            parser.setErrorFlag();
            throw e;
        }
    }

    /**
     * Creates a new instance of annotation parser.
     */
    public AnnotationParser createAnnotationParser() {
        if (parser.getAnnotationParserFactory() == null)
            return DefaultAnnotationParser.theInstance;
        else
            return parser.getAnnotationParserFactory().create();
    }

    /**
     * Gets the element name that contains the annotation element.
     * This method works correctly only when called by the annotation handler.
     */
    public String getAnnotationContextElementName() {
        return elementNames.get(elementNames.size() - 2);
    }

    /** Creates a copy of the current locator object. */
    public Locator copyLocator() {
        return new LocatorImpl(getLocator());
    }

    public ErrorHandler getErrorHandler() {
        return parser.errorHandler;
    }

    @Override
    public void onEnterElementConsumed(String uri, String localName, String qname, Attributes atts)
            throws SAXException {
        super.onEnterElementConsumed(uri, localName, qname, atts);
        elementNames.push(localName);
    }

    @Override
    public void onLeaveElementConsumed(String uri, String localName, String qname) throws SAXException {
        super.onLeaveElementConsumed(uri, localName, qname);
        elementNames.pop();
    }

    /** Returns an immutable snapshot of the current context. */
    public ValidationContext createValidationContext() {
        return currentContext;
    }

    public XmlString createXmlString(String value) {
        if (value == null) return null;
        else return new XmlString(value, createValidationContext());
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        super.startPrefixMapping(prefix, uri);
        currentContext = new Context(prefix, uri, currentContext);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        super.endPrefixMapping(prefix);
        currentContext = currentContext.previous;
    }


//
//
// Utility functions
//
//


    /** Parses UName under the given context. */
    public UName parseUName(String qname) throws SAXException {
        int idx = qname.indexOf(':');
        if (idx < 0) {
            String uri = resolveNamespacePrefix("");

            // chamelon behavior. ugly...
            if (uri.equals("") && chameleonMode)
                uri = currentSchema.getTargetNamespace();

            // this is guaranteed to resolve
            return new UName(uri, qname, qname);
        } else {
            String prefix = qname.substring(0, idx);
            String uri = currentContext.resolveNamespacePrefix(prefix);
            if (uri == null) {
                // prefix failed to resolve.
                reportError(Messages.format(
                        Messages.ERR_UNDEFINED_PREFIX, prefix));
                uri = "undefined"; // replace with a dummy
            }
            return new UName(uri, qname.substring(idx + 1), qname);
        }
    }

    public boolean parseBoolean(String v) {
        if (v == null) return false;
        v = v.trim();
        return v.equals("true") || v.equals("1");
    }


    @Override
    protected void unexpectedX(String token) throws SAXException {
        SAXParseException e = new SAXParseException(MessageFormat.format(
                "Unexpected {0} appears at line {1} column {2}",
                token,
                getLocator().getLineNumber(),
                getLocator().getColumnNumber()),
                getLocator());

        parser.errorHandler.fatalError(e);
        throw e;    // we will abort anyway
    }

    public ForeignAttributesImpl parseForeignAttributes(ForeignAttributesImpl next) {
        ForeignAttributesImpl impl = new ForeignAttributesImpl(createValidationContext(), copyLocator(), next);

        Attributes atts = getCurrentAttributes();
        for (int i = 0; i < atts.getLength(); i++) {
            if (atts.getURI(i).length() > 0) {
                impl.addAttribute(
                        atts.getURI(i),
                        atts.getLocalName(i),
                        atts.getQName(i),
                        atts.getType(i),
                        atts.getValue(i)
                );
            }
        }

        return impl;
    }

    //
//
// ValidationContext implementation
//
//
    // this object lives longer than the parser itself,
    // so it's important for this object not to have any reference
    // to the parser.
    private static class Context implements ValidationContext {

        private final String prefix;
        private final String uri;
        private final Context previous;
        Context(String _prefix, String _uri, Context _context) {
            this.previous = _context;
            this.prefix = _prefix;
            this.uri = _uri;
        }

        public String resolveNamespacePrefix(String p) {
            if (p.equals(prefix)) return uri;
            if (previous == null) return null;
            else return previous.resolveNamespacePrefix(p);
        }

        // XSDLib don't use those methods, so we cut a corner here.
        public String getBaseUri() {
            return null;
        }

        public boolean isNotation(String arg0) {
            return false;
        }

        public boolean isUnparsedEntity(String arg0) {
            return false;
        }
    }

    public static class PatchIndicator {

    }
}
