package org.novelflow.novel.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class EpubTextExtractor {

    private static final int MIN_TOC_CHAPTER_TEXT_LENGTH = 300;
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern RUBY_ANNOTATION = Pattern.compile("(?is)<(?:[\\w.-]+:)?(?:rt|rp|rtc)\\b[^>]*>.*?</(?:[\\w.-]+:)?(?:rt|rp|rtc)>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(h[1-6]|p|div|section|article|li|tr|blockquote)>");
    private static final Pattern BREAK_TAG = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?[0-9a-fA-F]+);");

    public String extract(Path epubPath) {
        List<EpubChapter> chapters = extractChapters(epubPath);
        StringBuilder builder = new StringBuilder();
        for (EpubChapter chapter : chapters) {
            builder.append(chapter.title()).append(System.lineSeparator());
            builder.append(chapter.text().strip()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return builder.toString().strip();
    }

    public List<EpubChapter> extractChapters(Path epubPath) {
        try (ZipFile zipFile = new ZipFile(epubPath.toFile(), StandardCharsets.UTF_8)) {
            String opfPath = findOpfPath(zipFile);
            EpubStructure structure = opfPath == null
                    ? EpubStructure.fallback(fallbackHtmlEntries(zipFile))
                    : resolveStructure(zipFile, opfPath);

            if (structure.spineEntries().isEmpty()) {
                throw new IllegalArgumentException("EPUB 中没有可读取的 XHTML/HTML 正文");
            }

            List<EpubChapter> tocChapters = extractTocChapters(zipFile, structure);
            if (!tocChapters.isEmpty()) {
                return tocChapters;
            }

            return extractSubstantiveSpineChapters(zipFile, structure.spineEntries());
        } catch (IOException e) {
            throw new IllegalStateException("读取 EPUB 失败: " + e.getMessage(), e);
        }
    }

    private String findOpfPath(ZipFile zipFile) {
        ZipEntry container = zipFile.getEntry("META-INF/container.xml");
        if (container == null) {
            return null;
        }
        try {
            Document document = parseXml(zipFile.getInputStream(container).readAllBytes());
            for (Element rootFile : elementsByLocalName(document, "rootfile")) {
                String fullPath = rootFile.getAttribute("full-path");
                if (!fullPath.isBlank()) {
                    return normalizeEntryName(fullPath);
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("解析 EPUB container.xml 失败: " + e.getMessage(), e);
        }
    }

    private EpubStructure resolveStructure(ZipFile zipFile, String opfPath) {
        ZipEntry opfEntry = getEntry(zipFile, opfPath);
        if (opfEntry == null) {
            return EpubStructure.fallback(fallbackHtmlEntries(zipFile));
        }

        try {
            Document document = parseXml(zipFile.getInputStream(opfEntry).readAllBytes());
            Map<String, String> manifest = new HashMap<>();
            String tocPath = null;
            for (Element item : elementsByLocalName(document, "item")) {
                String id = item.getAttribute("id");
                String href = item.getAttribute("href");
                String mediaType = item.getAttribute("media-type").toLowerCase(Locale.ROOT);
                if (!id.isBlank() && !href.isBlank() && isHtmlMediaType(mediaType, href)) {
                    manifest.put(id, resolveRelativeEntry(opfPath, href));
                }
                if (!href.isBlank() && (mediaType.contains("dtbncx") || href.toLowerCase(Locale.ROOT).endsWith(".ncx"))) {
                    tocPath = resolveRelativeEntry(opfPath, href);
                }
            }

            Set<String> spineEntries = new LinkedHashSet<>();
            for (Element itemRef : elementsByLocalName(document, "itemref")) {
                String idRef = itemRef.getAttribute("idref");
                String href = manifest.get(idRef);
                if (href != null) {
                    spineEntries.add(href);
                }
            }

            if (spineEntries.isEmpty()) {
                return EpubStructure.fallback(fallbackHtmlEntries(zipFile));
            }
            return new EpubStructure(new ArrayList<>(spineEntries), tocPath);
        } catch (Exception e) {
            throw new IllegalStateException("解析 EPUB OPF/spine 失败: " + e.getMessage(), e);
        }
    }

    private List<EpubChapter> extractTocChapters(ZipFile zipFile, EpubStructure structure) {
        if (structure.tocPath() == null || structure.tocPath().isBlank()) {
            return List.of();
        }
        ZipEntry tocEntry = getEntry(zipFile, structure.tocPath());
        if (tocEntry == null) {
            return List.of();
        }

        try {
            Document document = parseXml(zipFile.getInputStream(tocEntry).readAllBytes());
            List<EpubChapter> chapters = new ArrayList<>();
            Set<String> spineSet = new LinkedHashSet<>(structure.spineEntries());
            for (Element navPoint : elementsByLocalName(document, "navPoint")) {
                String title = textContentByLocalName(navPoint, "text");
                String src = contentSrc(navPoint);
                if (title.isBlank() || src.isBlank() || isNonBodyTitle(title)) {
                    continue;
                }
                String entryName = resolveRelativeEntry(structure.tocPath(), stripFragment(src));
                if (!spineSet.contains(entryName)) {
                    continue;
                }
                String text = readHtmlText(zipFile, entryName);
                if (text.length() < MIN_TOC_CHAPTER_TEXT_LENGTH) {
                    continue;
                }
                chapters.add(new EpubChapter(title, text, entryName));
            }
            return chapters;
        } catch (Exception e) {
            throw new IllegalStateException("解析 EPUB 目录失败: " + e.getMessage(), e);
        }
    }

    private List<EpubChapter> extractSubstantiveSpineChapters(ZipFile zipFile, List<String> spineEntries) {
        List<EpubChapter> chapters = new ArrayList<>();
        for (String entryName : spineEntries) {
            String text = readHtmlText(zipFile, entryName);
            if (text.length() < MIN_TOC_CHAPTER_TEXT_LENGTH) {
                continue;
            }
            chapters.add(new EpubChapter(guessTitle(entryName, text), text, entryName));
        }
        return chapters;
    }

    private String readHtmlText(ZipFile zipFile, String entryName) {
        try {
            ZipEntry entry = getEntry(zipFile, entryName);
            if (entry == null) {
                return "";
            }
            String html = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            return htmlToText(html);
        } catch (IOException e) {
            throw new IllegalStateException("读取 EPUB 正文失败: " + entryName, e);
        }
    }

    private List<String> fallbackHtmlEntries(ZipFile zipFile) {
        return zipFile.stream()
                .map(ZipEntry::getName)
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm");
                })
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private List<Element> elementsByLocalName(Document document, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = document.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                String candidate = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
                if (localName.equals(candidate)) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private List<Element> elementsByLocalName(Element root, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                String candidate = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
                if (localName.equals(candidate)) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    private String textContentByLocalName(Element root, String localName) {
        List<Element> elements = elementsByLocalName(root, localName);
        if (elements.isEmpty()) {
            return "";
        }
        return decodeHtmlEntities(elements.get(0).getTextContent()).trim();
    }

    private String contentSrc(Element navPoint) {
        List<Element> elements = elementsByLocalName(navPoint, "content");
        if (elements.isEmpty()) {
            return "";
        }
        return elements.get(0).getAttribute("src").trim();
    }

    private String resolveRelativeEntry(String opfPath, String href) {
        String base = "";
        int slash = opfPath.lastIndexOf('/');
        if (slash >= 0) {
            base = opfPath.substring(0, slash + 1);
        }
        return normalizeEntryName(base + href);
    }

    private String stripFragment(String href) {
        int hash = href.indexOf('#');
        return hash >= 0 ? href.substring(0, hash) : href;
    }

    private ZipEntry getEntry(ZipFile zipFile, String entryName) {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry != null) {
            return entry;
        }
        String decoded = URLDecoder.decode(entryName, StandardCharsets.UTF_8);
        return zipFile.getEntry(decoded);
    }

    private String normalizeEntryName(String value) {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }

    private boolean isHtmlMediaType(String mediaType, String href) {
        String lowerHref = href.toLowerCase(Locale.ROOT);
        return mediaType.contains("xhtml")
                || mediaType.contains("html")
                || lowerHref.endsWith(".xhtml")
                || lowerHref.endsWith(".html")
                || lowerHref.endsWith(".htm");
    }

    private boolean isNonBodyTitle(String title) {
        String normalized = title.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("contents")
                || normalized.equals("content")
                || normalized.equals("cover")
                || normalized.equals("titlepage")
                || normalized.equals("title page")
                || normalized.equals("copyright")
                || normalized.equals("toc")
                || normalized.equals("目次")
                || normalized.equals("表紙")
                || normalized.equals("奥付");
    }

    private String htmlToText(String html) {
        String text = SCRIPT_STYLE.matcher(html).replaceAll("");
        text = RUBY_ANNOTATION.matcher(text).replaceAll("");
        text = BREAK_TAG.matcher(text).replaceAll("\n");
        text = BLOCK_END.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = decodeHtmlEntities(text);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[ \\t\\x0B\\f]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    private String guessTitle(String entryName, String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isBlank() && trimmed.length() <= 40) {
                return trimmed.replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        }
        int slash = entryName.lastIndexOf('/');
        String name = slash >= 0 ? entryName.substring(slash + 1) : entryName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String decodeHtmlEntities(String text) {
        String decoded = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'");

        Matcher matcher = NUMERIC_ENTITY.matcher(decoded);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            int codePoint = raw.startsWith("x") || raw.startsWith("X")
                    ? Integer.parseInt(raw.substring(1), 16)
                    : Integer.parseInt(raw);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public record EpubChapter(String title, String text, String entryName) {
    }

    private record EpubStructure(List<String> spineEntries, String tocPath) {

        private static EpubStructure fallback(List<String> spineEntries) {
            return new EpubStructure(spineEntries, null);
        }
    }
}

