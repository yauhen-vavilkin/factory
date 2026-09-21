package org.folio.factory.devfactory.verification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Small Maven/Surefire proof: only fresh TEST-*.xml reports count as executed tests. */
record SurefireEvidence(int reportCount, int testCount, int failureCount, int errorCount) {
    private static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;

    static SurefireEvidence inspect(Path workspace, Instant verificationStarted) {
        int reports = 0;
        int tests = 0;
        int failures = 0;
        int errors = 0;
        Instant oldestAccepted = verificationStarted.minusSeconds(2);
        try (var paths = Files.walk(workspace)) {
            for (Path report : paths.toList()) {
                Path relative = workspace.relativize(report);
                if (!isSurefireReport(relative)) continue;
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("Unsafe Surefire report file");
                }
                if (Files.getLastModifiedTime(report, LinkOption.NOFOLLOW_LINKS).toInstant()
                        .isBefore(oldestAccepted)) continue;
                if (Files.size(report) > MAX_REPORT_BYTES) {
                    throw new IllegalStateException("Surefire report exceeds evidence limit");
                }
                byte[] xml;
                try (var input = Files.newInputStream(report, LinkOption.NOFOLLOW_LINKS)) {
                    xml = input.readNBytes(MAX_REPORT_BYTES + 1);
                }
                if (xml.length > MAX_REPORT_BYTES) throw new IllegalStateException("Surefire report exceeds evidence limit");
                Element suite = parse(xml);
                int suiteTests = attribute(suite, "tests");
                int suiteSkipped = attribute(suite, "skipped");
                int suiteFailures = attribute(suite, "failures");
                int suiteErrors = attribute(suite, "errors");
                if ((long) suiteSkipped + suiteFailures + suiteErrors > suiteTests) {
                    throw new IllegalStateException("Invalid Surefire counters");
                }
                reports++;
                tests = Math.addExact(tests, suiteTests - suiteSkipped);
                failures = Math.addExact(failures, suiteFailures);
                errors = Math.addExact(errors, suiteErrors);
            }
            return new SurefireEvidence(reports, tests, failures, errors);
        } catch (IOException | UncheckedIOException | ArithmeticException e) {
            throw new IllegalStateException("Cannot inspect fresh Surefire evidence", e);
        }
    }

    private static Element parse(byte[] xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entities forbidden"); });
            var root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if (!"testsuite".equals(root.getTagName()) || root.getElementsByTagName("testsuite").getLength() != 0) {
                throw new IllegalStateException("Invalid Surefire report root");
            }
            return root;
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Invalid or unsafe Surefire XML", e);
        }
    }

    private static int attribute(Element suite, String name) {
        String value = suite.getAttribute(name);
        if (!value.matches("[0-9]+")) throw new IllegalStateException("Missing or invalid Surefire counter: " + name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid Surefire counter: " + name, e);
        }
    }

    private static boolean isSurefireReport(Path relative) {
        String name = relative.getFileName().toString();
        if (!name.startsWith("TEST-") || !name.endsWith(".xml")) return false;
        for (int i = 0; i + 1 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("target")
                    && relative.getName(i + 1).toString().equals("surefire-reports")) return true;
        }
        return false;
    }
}
