package com.veeva.vault.custom.claims;

import com.veeva.vault.sdk.api.core.UserDefinedClassInfo;

/**
 * Claim text clean-up applied before a record is created: trailing reference
 * markers that text extraction glues onto the last word of a claim
 * ("...than chemotherapy1,2", "...months.3", "...neutropenia*", "...AEs†",
 * "...care¹") are removed, because they are footnote pointers on the page,
 * not part of the claim, and would otherwise land in match_text__sys.
 *
 * Rules, applied repeatedly until nothing changes:
 *   - trailing footnote symbols (* † ‡ § ¶ ‖) and Unicode superscript
 *     characters (¹ ² ³, U+2070-207F, U+1D2C-1D6B; subscripts such as CO₂ stay) are dropped;
 *   - a trailing group of small numbers with list punctuation ("1", "1,2",
 *     "1-3", "2;4") is dropped only when it is glued to the preceding text:
 *     directly after a word of at least three letters ("chemotherapy1"), or
 *     after closing punctuation ) ] % " ” ' or a full stop that does not
 *     end a decimal ("months.1" yes, "0.001" no). After a space only a
 *     comma list is dropped ("chemotherapy 1,2"); a single number or range
 *     after a space is kept ("Phase 3", "over 2 years", "weeks 1-3"),
 *     as are short alphanumerics such as "CD4", "D3", "Q4", "COVID-19".
 *   Text extraction does not mark superscripts, so this is a heuristic;
 *   the action instructions ask the model to drop citation markers using
 *   the document's reference list as context, this is the safety net.
 *   - the same is tried in front of a final full stop ("chemotherapy1." ->
 *     "chemotherapy.").
 * Trademark symbols (® ™) are never removed: they belong to the name and the
 * Claims Agent needs them for its verbatim match.
 *
 * Plain char scanning, no regex, no String.split (sandbox rules).
 */
@UserDefinedClassInfo
public class ClaimText {

    private static final String FOOTNOTE_SYMBOLS = "*†‡§¶‖";
    private static final String LIST_PUNCTUATION = ",;-–—";
    private static final String CLOSERS = ")]%\"”'’";

    private ClaimText() {
    }

    public static String stripTrailingReferenceMarkers(String text) {
        if (text == null) {
            return "";
        }
        String current = text.trim();
        while (true) {
            String next = stripOnce(current);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        // "...chemotherapy1." -- marker before the final full stop.
        if (current.endsWith(".") && current.length() > 1) {
            String body = current.substring(0, current.length() - 1).trim();
            String stripped = stripTrailingReferenceMarkers(body);
            if (!stripped.equals(body)) {
                current = stripped + ".";
            }
        }
        return current;
    }

    private static String stripOnce(String text) {
        String s = trimEnd(text);
        if (s.length() == 0) {
            return s;
        }
        char last = s.charAt(s.length() - 1);
        if (FOOTNOTE_SYMBOLS.indexOf(last) >= 0 || isSuperscript(last)) {
            return trimEnd(s.substring(0, s.length() - 1));
        }
        // Dangling list punctuation after a stripped symbol ("therapy1,*" -> "therapy1,").
        if (LIST_PUNCTUATION.indexOf(last) >= 0 && s.length() > 1 && isDigit(s.charAt(s.length() - 2))) {
            return s.substring(0, s.length() - 1);
        }
        int start = s.length();
        while (start > 0 && (isDigit(s.charAt(start - 1)) || LIST_PUNCTUATION.indexOf(s.charAt(start - 1)) >= 0)) {
            start--;
        }
        if (start == s.length() || start == 0) {
            return s;
        }
        String group = s.substring(start);
        if (!isReferenceGroup(group)) {
            return s;
        }
        char before = s.charAt(start - 1);
        if (before == ' ' || before == ' ') {
            // "chemotherapy 1,2": a comma list is never a claim value.
            // "Phase 3", "over 2 years", "weeks 1-3": a single number or a
            // range after a space stays (ranges are real values too often).
            boolean isList = false;
            for (int i = 0; i < group.length(); i++) {
                if (group.charAt(i) == ',' || group.charAt(i) == ';') {
                    isList = true;
                }
            }
            if (isList && start >= 2 && (letterRunBefore(s, start - 1) >= 3 || CLOSERS.indexOf(s.charAt(start - 2)) >= 0)) {
                return trimEnd(s.substring(0, start - 1));
            }
            return s;
        }
        if (before == '.') {
            // "months.1" yes; "0.001" is a decimal, keep.
            if (start >= 2 && isDigit(s.charAt(start - 2))) {
                return s;
            }
            return s.substring(0, start);
        }
        if (CLOSERS.indexOf(before) >= 0 || FOOTNOTE_SYMBOLS.indexOf(before) >= 0 || isSuperscript(before)) {
            return s.substring(0, start);
        }
        if (isLetter(before) && letterRunBefore(s, start) >= 3) {
            return s.substring(0, start);
        }
        return s;
    }

    /** "1", "1,2", "1-3", "2;4-6": starts and ends with a digit, digit runs of at most two. */
    private static boolean isReferenceGroup(String group) {
        if (!isDigit(group.charAt(0)) || !isDigit(group.charAt(group.length() - 1))) {
            return false;
        }
        int run = 0;
        for (int i = 0; i < group.length(); i++) {
            if (isDigit(group.charAt(i))) {
                run++;
                if (run > 2) {
                    return false;
                }
            } else {
                run = 0;
            }
        }
        return true;
    }

    private static int letterRunBefore(String s, int end) {
        int count = 0;
        int i = end - 1;
        while (i >= 0 && isLetter(s.charAt(i))) {
            count++;
            i--;
        }
        return count;
    }

    private static boolean isSuperscript(char c) {
        return (c >= '⁰' && c <= 'ⁿ') || (c >= 'ᴬ' && c <= 'ᵫ')
            || c == '¹' || c == '²' || c == '³';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 'À' && c <= 'ɏ' && c != '×' && c != '÷');
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) <= ' ' || s.charAt(end - 1) == '\u00A0')) {
            end--;
        }
        return s.substring(0, end);
    }
}
