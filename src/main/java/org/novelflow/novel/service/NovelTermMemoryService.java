package org.novelflow.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NovelTermMemoryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NovelProjectService novelProjectService;
    private final NovelTranslationTextPostProcessor textPostProcessor;
    private final ObjectMapper objectMapper;

    public NovelTermMemoryService(
            NovelProjectService novelProjectService,
            NovelTranslationTextPostProcessor textPostProcessor,
            ObjectMapper objectMapper) {
        this.novelProjectService = novelProjectService;
        this.textPostProcessor = textPostProcessor;
        this.objectMapper = objectMapper;
    }

    public synchronized TermMemory load(String projectId) {
        Path projectDir = novelProjectService.resolveProjectDir(projectId);
        Path file = memoryFile(projectDir);
        if (Files.isRegularFile(file)) {
            try {
                TermMemory memory = objectMapper.readValue(file.toFile(), TermMemory.class);
                syncStaticTerms(projectId, memory, 0, 0);
                memory.setMemoryPath(file.toAbsolutePath().normalize().toString());
                write(projectDir, memory);
                return memory;
            } catch (IOException e) {
                throw new IllegalStateException("读取术语记忆失败: " + e.getMessage(), e);
            }
        }
        TermMemory memory = new TermMemory();
        memory.setProjectId(projectId);
        memory.setMemoryPath(file.toAbsolutePath().normalize().toString());
        memory.setCreatedAt(now());
        memory.setUpdatedAt(now());
        memory.setTerms(new ArrayList<>());
        syncStaticTerms(projectId, memory, 0, 0);
        write(projectDir, memory);
        return memory;
    }

    public synchronized void recordChunkTerms(String projectId, int chapterIndex, int chunkIndex, String sourceText, String translatedText) {
        TermMemory memory = load(projectId);
        if (syncStaticTerms(projectId, memory, chapterIndex, chunkIndex)) {
            Map<String, String> glossaryInfo = textPostProcessor.glossaryInfo();
            for (Map.Entry<String, String> entry : textPostProcessor.glossary().entrySet()) {
                String source = entry.getKey();
                String target = entry.getValue();
                if (contains(sourceText, source) || contains(translatedText, target)) {
                    upsert(memory, source, target, "STATIC_GLOSSARY", 1.0, chapterIndex, chunkIndex,
                            glossaryInfo.getOrDefault(source, "Matched in translated chunk"));
                }
            }
        }
        memory.setUpdatedAt(now());
        write(novelProjectService.resolveProjectDir(projectId), memory);
    }

    public String promptFor(String projectId, String sourceText, String translatedText) {
        TermMemory memory = load(projectId);
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (TermEntry term : memory.getTerms()) {
            if (term.getSource() == null || term.getTarget() == null) {
                continue;
            }
            boolean relevant = contains(sourceText, term.getSource()) || contains(translatedText, term.getTarget());
            if (!relevant && count >= 12) {
                continue;
            }
            builder.append(term.getSource())
                    .append("->")
                    .append(term.getTarget());
            if (term.getNotes() != null && !term.getNotes().isBlank()) {
                builder.append(" #").append(term.getNotes());
            }
            builder.append('\n');
            count++;
        }
        return builder.toString().strip();
    }

    public synchronized Map<String, String> lockedTerms(String projectId) {
        TermMemory memory = load(projectId);
        Map<String, String> terms = new LinkedHashMap<>();
        for (TermEntry term : memory.getTerms()) {
            if (term.getSource() == null || term.getSource().isBlank()
                    || term.getTarget() == null || term.getTarget().isBlank()) {
                continue;
            }
            terms.put(term.getSource(), term.getTarget());
        }
        return terms;
    }

    public synchronized TermMemory upsertManualTerm(String projectId, String source, String target, String notes) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("术语原文不能为空");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("术语译文不能为空");
        }
        TermMemory memory = load(projectId);
        upsert(memory, source.strip(), target.strip(), "MANUAL", 1.0, 0, 0,
                notes == null || notes.isBlank() ? "Manual term from NovelFlow Agent" : notes.strip());
        memory.setUpdatedAt(now());
        write(novelProjectService.resolveProjectDir(projectId), memory);
        return memory;
    }

    public synchronized List<TermViolation> validateLockedTerms(String projectId, String sourceText, String translatedText) {
        TermMemory memory = load(projectId);
        List<TermEntry> terms = memory.getTerms() == null ? List.of() : memory.getTerms();
        List<TermViolation> violations = new ArrayList<>();
        String safeSource = sourceText == null ? "" : sourceText;
        String safeTranslated = translatedText == null ? "" : translatedText;

        for (TermEntry activeTerm : terms) {
            String source = activeTerm.getSource();
            String target = activeTerm.getTarget();
            if (!contains(safeSource, source)) {
                continue;
            }
            if (!hasRequiredTargetOccurrences(activeTerm, safeSource, safeTranslated)) {
                violations.add(new TermViolation(
                        "CANONICAL_TERM_MISSING",
                        source,
                        target,
                        "",
                        "源文包含锁定术语 `" + source + "`，译文必须使用 `" + target + "`"));
            }
            if (!isCharacterNameTerm(activeTerm)) {
                continue;
            }
            for (TermEntry otherTerm : terms) {
                String otherSource = otherTerm.getSource();
                String otherTarget = otherTerm.getTarget();
                if (source.equals(otherSource) || target.equals(otherTarget)
                        || !isCharacterNameTerm(otherTerm)
                        || !isStrongTarget(otherTarget)
                        || contains(safeSource, otherSource)
                        || !contains(safeTranslated, otherTarget)) {
                    continue;
                }
                violations.add(new TermViolation(
                        "CANONICAL_TERM_CONFLICT",
                        source,
                        target,
                        otherTarget,
                        "源文包含 `" + source + "`，但译文出现了其他角色译名 `" + otherTarget
                                + "`；锁定译名应为 `" + target + "`"));
            }
        }
        return violations;
    }

    public String memoryPath(String projectId) {
        return memoryFile(novelProjectService.resolveProjectDir(projectId)).toAbsolutePath().normalize().toString();
    }

    private boolean syncStaticTerms(String projectId, TermMemory memory, int chapterIndex, int chunkIndex) {
        if (memory.getTerms() == null) {
            memory.setTerms(new ArrayList<>());
        }
        Map<String, String> glossary = textPostProcessor.glossary();
        if (!isStaticGlossaryRelevant(projectId, glossary)) {
            memory.getTerms().removeIf(term -> "STATIC_GLOSSARY".equals(term.getType())
                    && glossary.containsKey(term.getSource()));
            return false;
        }
        Map<String, String> glossaryInfo = textPostProcessor.glossaryInfo();
        for (Map.Entry<String, String> entry : glossary.entrySet()) {
            upsert(memory, entry.getKey(), entry.getValue(), "STATIC_GLOSSARY", 1.0, chapterIndex, chunkIndex,
                    glossaryInfo.getOrDefault(entry.getKey(), "File glossary"));
        }
        return true;
    }

    private boolean isStaticGlossaryRelevant(String projectId, Map<String, String> glossary) {
        if (glossary == null || glossary.isEmpty()) {
            return false;
        }
        Map<String, String> glossaryInfo = textPostProcessor.glossaryInfo();
        List<String> titleTerms = glossary.entrySet().stream()
                .filter(entry -> glossaryInfo.getOrDefault(entry.getKey(), "").contains("作品标题"))
                .flatMap(entry -> List.of(entry.getKey(), entry.getValue()).stream())
                .map(this::searchKey)
                .filter(value -> !value.isBlank())
                .toList();
        if (titleTerms.isEmpty()) {
            return true;
        }
        String projectText = projectSearchText(projectId);
        return titleTerms.stream().anyMatch(projectText::contains);
    }

    private String projectSearchText(String projectId) {
        try {
            Map<String, Object> summary = novelProjectService.getProjectSummary(projectId);
            StringBuilder builder = new StringBuilder();
            builder.append(stringValue(summary.get("projectName"))).append('\n');
            builder.append(stringValue(summary.get("sourceFileName"))).append('\n');
            builder.append(stringValue(summary.get("manifestSourceFileName"))).append('\n');
            Object names = summary.get("sourceFileNames");
            if (names instanceof List<?> list) {
                for (Object name : list) {
                    builder.append(stringValue(name)).append('\n');
                }
            }
            return searchKey(builder.toString());
        } catch (Exception e) {
            return searchKey(projectId);
        }
    }

    private boolean hasRequiredTargetOccurrences(TermEntry term, String sourceText, String translatedText) {
        String source = term.getSource();
        String target = term.getTarget();
        if (!contains(translatedText, target)) {
            return false;
        }
        if (!isCharacterNameTerm(term) || target == null || target.strip().length() > 1) {
            return true;
        }
        int sourceCount = countOccurrences(sourceText, source);
        int targetCount = countOccurrences(translatedText, target);
        int required = Math.min(Math.max(sourceCount - 1, 1), 3);
        return targetCount >= required;
    }

    private void upsert(
            TermMemory memory,
            String source,
            String target,
            String type,
            double confidence,
            int chapterIndex,
            int chunkIndex,
            String notes) {
        TermEntry existing = memory.getTerms().stream()
                .filter(term -> source.equals(term.getSource()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            TermEntry entry = new TermEntry();
            entry.setSource(source);
            entry.setTarget(target);
            entry.setType(type);
            entry.setConfidence(confidence);
            entry.setFirstSeenChapter(chapterIndex);
            entry.setFirstSeenChunk(chunkIndex);
            entry.setNotes(notes);
            memory.getTerms().add(entry);
            return;
        }
        if (existing.getTarget() == null || existing.getTarget().isBlank()) {
            existing.setTarget(target);
        }
        existing.setType(existing.getType() == null ? type : existing.getType());
        existing.setConfidence(Math.max(existing.getConfidence(), confidence));
        if (notes != null && !notes.isBlank()
                && (existing.getNotes() == null || existing.getNotes().isBlank()
                || "Built-in glossary".equals(existing.getNotes()))) {
            existing.setNotes(notes);
        }
    }

    private boolean contains(String value, String term) {
        return value != null && term != null && !term.isBlank() && value.contains(term);
    }

    private int countOccurrences(String value, String term) {
        if (value == null || term == null || term.isBlank()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String searchKey(String value) {
        String normalized = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC);
        return normalized.toLowerCase()
                .replaceAll("[\\s\\p{Punct}《》「」『』（）【】\\[\\]・·]+", "")
                .trim();
    }

    private boolean isNameLikeSource(String source) {
        return source != null && source.matches("[\\u3040-\\u30ffー]+");
    }

    private boolean isCharacterNameTerm(TermEntry term) {
        if (term == null || !isNameLikeSource(term.getSource())) {
            return false;
        }
        String notes = term.getNotes() == null ? "" : term.getNotes();
        return notes.contains("角色名") || notes.contains("主角名");
    }

    private boolean isStrongTarget(String target) {
        return target != null && target.strip().length() >= 2;
    }

    private Path memoryFile(Path projectDir) {
        return projectDir.resolve("config").resolve("term-memory.json").normalize();
    }

    private void write(Path projectDir, TermMemory memory) {
        try {
            Path file = memoryFile(projectDir);
            Files.createDirectories(file.getParent());
            memory.setMemoryPath(file.toAbsolutePath().normalize().toString());
            memory.setUpdatedAt(now());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), memory);
        } catch (IOException e) {
            throw new IllegalStateException("写入术语记忆失败: " + e.getMessage(), e);
        }
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FORMATTER);
    }

    public static class TermMemory {
        private String projectId;
        private String memoryPath;
        private String createdAt;
        private String updatedAt;
        private List<TermEntry> terms = new ArrayList<>();

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getMemoryPath() {
            return memoryPath;
        }

        public void setMemoryPath(String memoryPath) {
            this.memoryPath = memoryPath;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public List<TermEntry> getTerms() {
            return terms;
        }

        public void setTerms(List<TermEntry> terms) {
            this.terms = terms;
        }
    }

    public static class TermEntry {
        private String source;
        private String target;
        private String type;
        private double confidence;
        private int firstSeenChapter;
        private int firstSeenChunk;
        private String notes;

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public int getFirstSeenChapter() {
            return firstSeenChapter;
        }

        public void setFirstSeenChapter(int firstSeenChapter) {
            this.firstSeenChapter = firstSeenChapter;
        }

        public int getFirstSeenChunk() {
            return firstSeenChunk;
        }

        public void setFirstSeenChunk(int firstSeenChunk) {
            this.firstSeenChunk = firstSeenChunk;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    public record TermViolation(String code, String source, String expectedTarget, String actualTarget, String message) {
    }
}

