package ph.coop.core.document;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The document rules that need no database (guide phase 16): what may be uploaded, how the file name is cleaned, whether the content really is what its extension says,
 * who may read a document of each classification, and when it may be removed. "Documents should not be freely downloadable by every authenticated user."
 */
final class DocumentRules {

    private DocumentRules() {}

    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final List<String> CLASSES = List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");

    /** The roles that look after each kind of record: they may upload to it and read its INTERNAL documents. */
    static final Map<String, Set<String>> OWNERS = Map.ofEntries(
        Map.entry("MEMBER", Set.of("MEMBERSHIP_OFFICER")),
        Map.entry("LOAN", Set.of()),
        Map.entry("SUPPLIER", Set.of("PURCHASER", "PURCHASE_APPROVER")),
        Map.entry("PURCHASE_ORDER", Set.of("PURCHASER", "PURCHASE_APPROVER", "RECEIVER")),
        Map.entry("ASSET", Set.of("ASSET_CUSTODIAN")),
        Map.entry("JOURNAL", Set.of()),
        Map.entry("PRODUCT", Set.of("STOREKEEPER", "STOCK_APPROVER")),
        Map.entry("OTHER", Set.of()));

    /** Shortest time a document of each kind of record is kept. The guide leaves the periods to the cooperative and its privacy review (phase 19); these are the starting defaults. */
    static final Map<String, Integer> MIN_RETENTION_YEARS = Map.of("MEMBER", 10, "LOAN", 10, "JOURNAL", 10, "ASSET", 10, "SUPPLIER", 5, "PURCHASE_ORDER", 5, "PRODUCT", 5, "OTHER", 5);

    /** File types accepted, by extension. */
    static final Map<String, String> MIME = Map.of("pdf", "application/pdf", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
        "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "csv", "text/csv", "txt", "text/plain");

    static boolean knownEntity(String type) {
        return OWNERS.containsKey(type);
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The file name as it will be kept and downloaded: no folders, only letters, digits, spaces, dots, dashes and underscores, at most 120 characters, the extension kept. Null when nothing usable is left. */
    static String cleanName(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("_{2,}", "_").replaceAll("\\.{2,}", ".");
        if (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 120) {
            String ext = extension(name);
            String base = name.substring(0, name.length() - (ext.isEmpty() ? 0 : ext.length() + 1));
            name = base.substring(0, Math.max(1, 119 - ext.length() - 1)) + (ext.isEmpty() ? "" : "." + ext);
        }
        return name.isBlank() ? null : name;
    }

    /** The problems with a file about to be stored, empty when it can be. {@code head} is the start of the content (at least the first few kilobytes). */
    static List<String> fileProblems(String cleanName, long size, byte[] head) {
        List<String> out = new ArrayList<>();
        if (cleanName == null) { out.add("The file has no usable name."); return out; }
        if (size <= 0) out.add("The file is empty.");
        if (size > MAX_BYTES) out.add("The file is larger than " + MAX_BYTES / (1024 * 1024) + " MB.");
        String ext = extension(cleanName);
        if (!MIME.containsKey(ext)) out.add("Only PDF, PNG, JPEG, Word (docx), Excel (xlsx), CSV and plain text files can be kept (not ." + ext + ").");
        else if (size > 0 && !signatureMatches(ext, head)) out.add("The content of this file is not what its extension (." + ext + ") says.");
        return out;
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[i] & 0xFF) != sig[i]) return false;
        return true;
    }

    /** Checks the first bytes against the file type, so a program renamed to .pdf is refused. Text files must hold no binary control characters. */
    static boolean signatureMatches(String ext, byte[] head) {
        return switch (ext) {
            case "pdf" -> starts(head, 0x25, 0x50, 0x44, 0x46, 0x2D);
            case "png" -> starts(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "jpg", "jpeg" -> starts(head, 0xFF, 0xD8, 0xFF);
            case "docx", "xlsx" -> starts(head, 0x50, 0x4B, 0x03, 0x04);
            case "csv", "txt" -> {
                for (byte x : head) {
                    int c = x & 0xFF;
                    if (c == 0 || (c < 32 && c != 9 && c != 10 && c != 13)) yield false;
                }
                yield true;
            }
            default -> false;
        };
    }

    /** Confidential for the records that hold personal or financial detail, internal for the rest. */
    static String defaultClassification(String entityType) {
        return switch (entityType) {
            case "MEMBER", "LOAN" -> "CONFIDENTIAL";
            default -> "INTERNAL";
        };
    }

    static LocalDate minimumRetention(String entityType, LocalDate uploaded) {
        return uploaded.plusYears(MIN_RETENTION_YEARS.getOrDefault(entityType, 5));
    }

    private static boolean any(Set<String> roles, String... names) {
        for (String n : names) if (roles.contains(n)) return true;
        return false;
    }

    private static boolean staffOf(String entityType, Set<String> roles) {
        for (String r : OWNERS.getOrDefault(entityType, Set.of())) if (roles.contains(r)) return true;
        return false;
    }

    /** Who may upload to a kind of record: administrators, document clerks, accountants and the roles that look after that record. */
    static boolean canUpload(String entityType, Set<String> roles) {
        return any(roles, "ADMIN", "DOCUMENT_CLERK", "ACCOUNTANT") || staffOf(entityType, roles);
    }

    /**
     * Who may read a document. PUBLIC: anyone signed in. INTERNAL: administrators, document clerks, accountants, the roles that look after that record, whoever uploaded it and
     * anyone granted it. CONFIDENTIAL: administrators, document clerks, whoever uploaded it and anyone granted it. RESTRICTED: administrators, whoever uploaded it and anyone granted it.
     */
    static boolean canRead(String classification, String entityType, Set<String> roles, boolean uploader, boolean granted) {
        if (uploader || granted) return true;
        return switch (classification) {
            case "PUBLIC" -> true;
            case "INTERNAL" -> any(roles, "ADMIN", "DOCUMENT_CLERK", "ACCOUNTANT") || staffOf(entityType, roles);
            case "CONFIDENTIAL" -> any(roles, "ADMIN", "DOCUMENT_CLERK");
            default -> roles.contains("ADMIN");
        };
    }

    /** Who may grant and take back access to a document, and remove it: administrators, document clerks and the person who uploaded it. */
    static boolean canManage(Set<String> roles, boolean uploader) {
        return uploader || any(roles, "ADMIN", "DOCUMENT_CLERK");
    }

    /** Why a document cannot be removed, or null: it must still be active and its retention date must have passed. */
    static String removeProblem(String status, LocalDate retentionDate, LocalDate today) {
        if (!status.equals("ACTIVE")) return "Only the current version of a document can be removed.";
        if (today.isBefore(retentionDate)) return "This document must be kept until " + retentionDate + ".";
        return null;
    }
}
