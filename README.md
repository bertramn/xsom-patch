xsom-patch
==========

A patched version of the Sun Microsystems XML Schema Object Parser (XSOM) which is found in the 2.2.x JAXB implementations.

JAXB XJC is incapable of dealing with multiple of the same includes across episode compilations. This problem is documented as bug https://java.net/jira/browse/JAXB-875. The JIRA also suggests a patch but not exactly how to apply the patch.

Original XSOM project documentation can be found at https://xsom.java.net/userguide.html
Original Source Location is https://svn.java.net/svn/xsom~sources/tags/xsom-20130531

This XSOM version is the schema object parser used in the JAXB implementation 2.2.4 to 2.2.7.

Unfortunately the JAXB library is quite a piece of work when it comes to packaging. The XSOM JAR is actually shaded into the JAXB-RI release. So to fix the problem one will need to compile this patched version and also re-release the JAXB-RI library.

To make sure the right version is patched one can add this to the JAXB build parent pom:
```
#!xml
<dependency>
  <groupId>com.sun.xsom</groupId>
  <artifactId>xsom</artifactId>
  <version>20130531</version>
  <classifier>patched</classifier>
</dependency>
```

Step By Step Guide:

1) patch xsom library
2) compile your JAXB version of choice (XSOM gets shaded into the JAXB version)
3) Optional: patch the Maven XJC compiler plugin to use that version of JAXB you just compiled


## Dev Notes

### Tracking already imported or included schemata
 
The parser.parsedDocuments must track (key) documents by namespace + systemId instead of SchemaDocumentImpl. This would allocate chameleon schemata correctly and reparse them if they are included into other namespaces. But for this to work the including schema would also have to pass the namespace to the function that adds to the parsedDocuments. The tightly coupled relationship between `NGCCRuntimeEx`, `XSOMParser`, `ParserContext`,  and `Schema` is mind-numbingly complex.
 
 
1. Schemata are tracked in `com.sun.xml.xsom.impl.parser.ParserContext.parsedDocuments`

2. Made available through `com.sun.xml.xsom.parser.XSOMParser.getDocuments()`

3. Directly accessed in `com.sun.xml.xsom.impl.parser.NGCCRuntimeExL460` 
 
4. Directly written to in `com.sun.xml.xsom.impl.parser.NGCCRuntimeExL462`


### Complicated call trees:

All doing the same thing:

* `com.sun.xml.xsom.parser.XSOMParser.parse(org.xml.sax.InputSource)`
* `com.sun.xml.xsom.impl.parser.ParserContext.parse`