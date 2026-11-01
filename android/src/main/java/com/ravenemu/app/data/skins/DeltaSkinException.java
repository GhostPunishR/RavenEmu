package com.ravenemu.app.data.skins;

/** Why a Delta skin is refused, after the beta's DeltaSkinErrorCode: the
 * code says what to tell the player, the message is for the log. */
public final class DeltaSkinException extends Exception {
    public enum Code {
        INVALID_EXTENSION,
        ARCHIVE_TOO_LARGE,
        NOT_A_ZIP,
        INVALID_ARCHIVE,
        TOO_MANY_ENTRIES,
        DECOMPRESSED_TOO_LARGE,
        ENTRY_TOO_LARGE,
        MANIFEST_MISSING,
        MANIFEST_DUPLICATE,
        MANIFEST_TOO_LARGE,
        MALFORMED_JSON,
        MISSING_REQUIRED_PROPERTY,
        UNSUPPORTED_CONSOLE,
        PORTRAIT_REPRESENTATION_MISSING,
        RESIZABLE_ASSET_MISSING,
        INVALID_MAPPING_SIZE,
        INVALID_FRAME,
        INVALID_NUMBER,
        UNSAFE_ARCHIVE_PATH,
        SYMBOLIC_LINK,
        NESTED_ARCHIVE,
        PDF_INVALID,
        PDF_MULTIPLE_PAGES,
        IDENTIFIER_ALREADY_INSTALLED,
        STORAGE_INSUFFICIENT,
        STORAGE_ERROR,
    }

    public final Code code;

    public DeltaSkinException(Code code, String message) {
        this(code, message, null);
    }

    public DeltaSkinException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** What the settings show, in the beta's words. */
    public static String userMessage(Code code) {
        switch (code) {
            case INVALID_EXTENSION: return "Le fichier doit porter l'extension .deltaskin";
            case MANIFEST_MISSING: return "Le fichier info.json est absent";
            case MANIFEST_DUPLICATE: return "L'archive contient plusieurs info.json";
            case UNSUPPORTED_CONSOLE: return "Cette console n'est pas prise en charge";
            case PORTRAIT_REPRESENTATION_MISSING: return "Aucune représentation iPhone portrait compatible";
            case RESIZABLE_ASSET_MISSING: return "L'image PDF portrait est absente";
            case PDF_INVALID:
            case PDF_MULTIPLE_PAGES: return "Le PDF du skin est illisible ou contient plusieurs pages";
            case ARCHIVE_TOO_LARGE:
            case DECOMPRESSED_TOO_LARGE:
            case ENTRY_TOO_LARGE:
            case TOO_MANY_ENTRIES:
            case MANIFEST_TOO_LARGE: return "Le skin est trop volumineux";
            case IDENTIFIER_ALREADY_INSTALLED: return "Un skin portant cet identifiant existe déjà";
            case STORAGE_INSUFFICIENT:
            case STORAGE_ERROR: return "Le stockage disponible est insuffisant ou inaccessible";
            default: return "Archive .deltaskin invalide";
        }
    }
}
