xsom-patch
==========

A patched version of the Sun Microsystems XML Schema Object Parser (XSOM) which is found in the 2.2.x JAXB implementations.

Retro patch for https://github.com/javaee/jaxb-v2/pull/1151

JAXB XJC is incapable of dealing with multiple includes across episode compilations ducmented as bug https://java.net/jira/browse/JAXB-875

Problem resides in XSOM library (https://xsom.java.net/userguide.html). 


Step By Step Guide:

1) compile this patch
2) make sure you replace all `com.sun.xml.bind` JAXB dependencies of your module with `org.glassfish.jaxb` ones
3) override the xsom dependency

Example on how to use the patch on the `maven-jaxb2-plugin` plugin:

```xml
<plugin>
  <groupId>org.jvnet.jaxb2.maven2</groupId>
  <artifactId>maven-jaxb2-plugin</artifactId>
  <version>0.13.1</version>
  <dependencies>
    <dependency>
      <groupId>org.glassfish.jaxb</groupId>
      <artifactId>jaxb-xjc</artifactId>
      <version>${jaxb.version}</version>
      <exclusions>
        <exclusion>
          <groupId>com.sun.xsom</groupId>
          <artifactId>xsom</artifactId>
        </exclusion>
      </exclusions>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jaxb</groupId>
      <artifactId>xsom</artifactId>
      <version>2.3.1-patch</version>
    </dependency>
  </dependencies>
</plugin>
```