package org.folio.factory.devfactory.profile;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/** XML parser with DTDs, external entities and external schemas disabled. */
final class SafePomReader {
  private SafePomReader() {
  }

  static Document parse(byte[] xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler());
      return builder.parse(new ByteArrayInputStream(xml));
    } catch (Exception e) {
      throw new IllegalArgumentException("pom.xml is not safe, well-formed XML: " + e.getMessage(), e);
    }
  }

  static Optional<String> firstText(Document document, String... names) {
    for (String name : names) {
      NodeList values = document.getElementsByTagName(name);
      if (values.getLength() > 0) {
        String value = values.item(0).getTextContent();
        if (value != null && !value.isBlank() && !value.contains("${")) {
          return Optional.of(value.trim());
        }
      }
    }
    return Optional.empty();
  }

  static boolean contains(Document document, String value) {
    return document.getDocumentElement().getTextContent().contains(value);
  }
}
