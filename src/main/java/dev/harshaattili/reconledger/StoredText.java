package dev.harshaattili.reconledger;

/** Losslessly representable text shared by request and verified-identity boundaries. */
final class StoredText {
    private StoredText() {}

    static boolean valid(String value) {
        // Requiredness and length belong to the calling boundary.
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == 0 || Character.isLowSurrogate(current)) return false;
            if (Character.isHighSurrogate(current)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            }
        }
        return true;
    }
}
