xsom-patch
==========

A patched version of the Sun Microsystems XML Schema Object Parser (XSOM) which is found in the 2.2.x JAXB implementations.

Original Source Location
https://svn.java.net/svn/xsom~sources/tags/xsom-20130531

It is the schema object parser used in the JAXB implementation 2.2.4 to 2.2.7.

JAXB XJC is incapable of dealing with multiple includes across episode compilations ducmented as bug https://java.net/jira/browse/JAXB-875

Problem is actually in XSOM library (https://xsom.java.net/userguide.html). 

This sources are patched with the proposed patch from the JIRA ticket. 

Unfortunately the JAXB library is quite a piece of work when it comes to packaging. The XSOM JAR is sctually shaded into the JAXB-RI release. So to fix the problem one will need to compile this patched version and also re-release the JAXB-RI library.

Step By Step Guide:

1) patch xsom library
2) compile your JAXB version of choice (XSOM gets shaded into the JAXB version)
3) Optional: patch the Maven XJC compiler plugin to use that version of JAXB you just compiled