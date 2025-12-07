package com.example.photos.search;

import android.content.Context;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal BERT WordPiece tokenizer for Chinese-CLIP (context length 52).
 *
 * The vocab is loaded from assets/models/clip/vocab.txt and we follow the same steps as
 * cn_clip.clip.utils.tokenize: [CLS] + wordpiece tokens + [SEP], padded with 0.
 */
final class ClipTextTokenizer {

    private static final int CONTEXT_LENGTH = 52;
    private static final String VOCAB_ASSET = "models/clip/vocab.txt";
    private static final String UNK = "[UNK]";
    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";
    private static final int MAX_CHARS_PER_WORD = 200;

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int unkId;
    private final BasicTokenizer basicTokenizer = new BasicTokenizer(true);

    ClipTextTokenizer(Context context) throws IOException {
        this.vocab = loadVocab(context);
        clsId = idFor(CLS);
        sepId = idFor(SEP);
        unkId = idFor(UNK);
    }

    int[] tokenize(String text) {
        String safe = text == null ? "" : text;
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        List<String> basic = basicTokenizer.tokenize(safe);
        for (String token : basic) {
            for (String piece : wordpiece(token)) {
                ids.add(idFor(piece));
            }
        }
        if (ids.size() > CONTEXT_LENGTH - 1) {
            ids = ids.subList(0, CONTEXT_LENGTH - 1);
        }
        ids.add(sepId);
        int[] out = new int[CONTEXT_LENGTH];
        Arrays.fill(out, 0);
        for (int i = 0; i < Math.min(out.length, ids.size()); i++) {
            out[i] = ids.get(i);
        }
        return out;
    }

    private List<String> wordpiece(String token) {
        List<String> output = new ArrayList<>();
        if (token.length() > MAX_CHARS_PER_WORD) {
            output.add(UNK);
            return output;
        }
        int start = 0;
        char[] chars = token.toCharArray();
        while (start < chars.length) {
            int end = chars.length;
            String cur = null;
            while (start < end) {
                String substr = new String(chars, start, end - start);
                if (start > 0) {
                    substr = "##" + substr;
                }
                if (vocab.containsKey(substr)) {
                    cur = substr;
                    break;
                }
                end -= 1;
            }
            if (cur == null) {
                output.add(UNK);
                return output;
            }
            output.add(cur);
            start = end;
        }
        return output;
    }

    private Map<String, Integer> loadVocab(Context context) throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (InputStream is = context.getAssets().open(VOCAB_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    map.put(token, idx++);
                }
            }
        }
        return map;
    }

    private int idFor(String token) {
        Integer id = vocab.get(token);
        return id != null ? id : 0;
    }

    /**
     * Basic tokenizer: lower-case + strip accents + split punctuation and add spaces around CJK.
     */
    private static final class BasicTokenizer {
        private final boolean doLowerCase;

        BasicTokenizer(boolean doLowerCase) {
            this.doLowerCase = doLowerCase;
        }

        List<String> tokenize(String text) {
            String cleaned = cleanText(text);
            String cjkSpaced = tokenizeChineseChars(cleaned);
            String[] rawTokens = cjkSpaced.trim().isEmpty()
                    ? new String[0]
                    : cjkSpaced.trim().split("\\s+");
            List<String> split = new ArrayList<>();
            for (String token : rawTokens) {
                String t = doLowerCase ? stripAccents(token.toLowerCase(Locale.getDefault())) : token;
                split.addAll(splitOnPunc(t));
            }
            List<String> out = new ArrayList<>();
            for (String s : split) {
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }

        private String cleanText(String text) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int cp = c;
                if (cp == 0 || cp == 0xfffd || isControl(c)) {
                    continue;
                }
                if (isWhitespace(c)) {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private String tokenizeChineseChars(String text) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int cp = c;
                if (isChineseChar(cp)) {
                    sb.append(' ').append(c).append(' ');
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private String stripAccents(String text) {
            String norm = Normalizer.normalize(text, Normalizer.Form.NFD);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < norm.length(); i++) {
                char c = norm.charAt(i);
                if (Character.getType(c) != Character.NON_SPACING_MARK) {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private List<String> splitOnPunc(String text) {
            List<String> output = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isPunctuation(c)) {
                    if (current.length() > 0) {
                        output.add(current.toString());
                        current.setLength(0);
                    }
                    output.add(String.valueOf(c));
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                output.add(current.toString());
            }
            return output;
        }

        private boolean isWhitespace(char c) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return true;
            int t = Character.getType(c);
            return t == Character.SPACE_SEPARATOR;
        }

        private boolean isControl(char c) {
            if (c == '\t' || c == '\n' || c == '\r') return false;
            int t = Character.getType(c);
            return t == Character.CONTROL || t == Character.FORMAT;
        }

        private boolean isPunctuation(char c) {
            int cp = c;
            if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
                return true;
            }
            int t = Character.getType(c);
            return t == Character.CONNECTOR_PUNCTUATION
                    || t == Character.DASH_PUNCTUATION
                    || t == Character.END_PUNCTUATION
                    || t == Character.FINAL_QUOTE_PUNCTUATION
                    || t == Character.INITIAL_QUOTE_PUNCTUATION
                    || t == Character.OTHER_PUNCTUATION
                    || t == Character.START_PUNCTUATION;
        }

        private boolean isChineseChar(int cp) {
            return (cp >= 0x4E00 && cp <= 0x9FFF) ||
                    (cp >= 0x3400 && cp <= 0x4DBF) ||
                    (cp >= 0x20000 && cp <= 0x2A6DF) ||
                    (cp >= 0x2A700 && cp <= 0x2B73F) ||
                    (cp >= 0x2B740 && cp <= 0x2B81F) ||
                    (cp >= 0x2B820 && cp <= 0x2CEAF) ||
                    (cp >= 0xF900 && cp <= 0xFAFF) ||
                    (cp >= 0x2F800 && cp <= 0x2FA1F);
        }
    }
}
