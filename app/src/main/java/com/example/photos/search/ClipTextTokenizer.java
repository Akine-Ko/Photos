package com.example.photos.search;

import android.content.Context;
import androidx.core.text.HtmlCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Minimal port of OpenCLIP SimpleTokenizer so we can encode arbitrary text on-device.
 */
final class ClipTextTokenizer {

    private static final int CONTEXT_LENGTH = 77;
    private static final String VOCAB_ASSET_GZ = "models/clip/bpe_simple_vocab_16e6.txt.gz";
    private static final String VOCAB_ASSET_TXT = "models/clip/bpe_simple_vocab_16e6.txt";

    private final Map<Integer, String> byteEncoder;
    private final Map<String, Integer> encoder;
    private final Map<String, Integer> bpeRanks;
    private final Map<String, String> cache = new HashMap<>();
    private final Pattern tokenPattern;
    private final int sotTokenId;
    private final int eotTokenId;

    ClipTextTokenizer(Context context) throws IOException {
        this.byteEncoder = bytesToUnicode();
        Map<String, Integer> byteDecoder = new HashMap<>();
        for (Map.Entry<Integer, String> entry : byteEncoder.entrySet()) {
            byteDecoder.put(entry.getValue(), entry.getKey());
        }
        List<String[]> merges = loadMerges(context);
        List<String> vocab = new ArrayList<>(byteEncoder.values());
        List<String> suffixes = new ArrayList<>(vocab.size());
        for (String v : vocab) {
            suffixes.add(v + "</w>");
        }
        vocab.addAll(suffixes);
        for (String[] merge : merges) {
            vocab.add(merge[0] + merge[1]);
        }
        List<String> specialTokens = Arrays.asList("<start_of_text>", "<end_of_text>");
        vocab.addAll(specialTokens);
        this.encoder = new HashMap<>();
        for (int i = 0; i < vocab.size(); i++) {
            encoder.put(vocab.get(i), i);
        }
        this.bpeRanks = new HashMap<>();
        for (int i = 0; i < merges.size(); i++) {
            String[] merge = merges.get(i);
            bpeRanks.put(merge[0] + "\u0000" + merge[1], i);
        }
        this.sotTokenId = encoder.get("<start_of_text>");
        this.eotTokenId = encoder.get("<end_of_text>");
        StringBuilder specialPattern = new StringBuilder();
        for (int i = 0; i < specialTokens.size(); i++) {
            if (i > 0) specialPattern.append("|");
            specialPattern.append(Pattern.quote(specialTokens.get(i)));
        }
        String pat = specialPattern + "|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+";
        this.tokenPattern = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
    }

    int[] tokenize(String text) {
        String cleaned = clean(text);
        List<Integer> tokens = new ArrayList<>();
        tokens.add(sotTokenId);
        Matcher matcher = tokenPattern.matcher(cleaned);
        while (matcher.find()) {
            String token = matcher.group();
            String[] bpeTokens = encodeToken(token);
            for (String bpeToken : bpeTokens) {
                Integer id = encoder.get(bpeToken);
                if (id != null) {
                    tokens.add(id);
                }
            }
        }
        tokens.add(eotTokenId);
        int[] out = new int[CONTEXT_LENGTH];
        Arrays.fill(out, 0);
        for (int i = 0; i < Math.min(out.length, tokens.size()); i++) {
            out[i] = tokens.get(i);
        }
        return out;
    }

    private String[] encodeToken(String token) {
        if (cache.containsKey(token)) {
            return cache.get(token).split(" ");
        }
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        StringBuilder transformed = new StringBuilder();
        for (byte b : tokenBytes) {
            String mapped = byteEncoder.get((int) (b & 0xFF));
            transformed.append(mapped);
        }
        String bpeTokens = bpe(transformed.toString());
        cache.put(token, bpeTokens);
        return bpeTokens.split(" ");
    }

    private String bpe(String token) {
        List<String> word = new ArrayList<>();
        for (int i = 0; i < token.length(); i++) {
            word.add(String.valueOf(token.charAt(i)));
        }
        if (!word.isEmpty()) {
            String last = word.remove(word.size() - 1) + "</w>";
            word.add(last);
        }
        Set<String> pairs = getPairs(word);
        if (pairs.isEmpty()) {
            return token + "</w>";
        }
        while (true) {
            String bigram = null;
            int bestRank = Integer.MAX_VALUE;
            for (String pairKey : pairs) {
                Integer rank = bpeRanks.get(pairKey);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bigram = pairKey;
                }
            }
            if (bigram == null) {
                break;
            }
            String[] parts = bigram.split("\u0000");
            String first = parts[0];
            String second = parts[1];
            List<String> newWord = new ArrayList<>();
            int i = 0;
            while (i < word.size()) {
                int j = word.subList(i, word.size()).indexOf(first);
                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size()));
                    break;
                }
                j += i;
                newWord.addAll(word.subList(i, j));
                if (j < word.size() - 1 && word.get(j + 1).equals(second)) {
                    newWord.add(first + second);
                    i = j + 2;
                } else {
                    newWord.add(word.get(j));
                    i = j + 1;
                }
            }
            word = newWord;
            pairs = getPairs(word);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < word.size(); i++) {
            if (i > 0) result.append(" ");
            result.append(word.get(i));
        }
        return result.toString();
    }

    private Set<String> getPairs(List<String> word) {
        Set<String> pairs = new HashSet<>();
        String prev = word.get(0);
        for (int i = 1; i < word.size(); i++) {
            String current = word.get(i);
            pairs.add(prev + "\u0000" + current);
            prev = current;
        }
        return pairs;
    }

    private List<String[]> loadMerges(Context context) throws IOException {
        List<String[]> merges = new ArrayList<>();
        try (InputStream raw = openVocab(context);
             BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            // Stop early so tokenizer vocab aligns with model embedding rows (49408).
            int target = 48894;
            while ((line = reader.readLine()) != null && merges.size() < target) {
                if (firstLine) {
                    firstLine = false;
                    continue;
                }
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length == 2) {
                    merges.add(parts);
                }
            }
        }
        return merges;
    }

    private InputStream openVocab(Context context) throws IOException {
        // Some build pipelines unpack the .gz into a plain .txt. Try gz first, then fallback.
        try {
            return new GZIPInputStream(context.getAssets().open(VOCAB_ASSET_GZ));
        } catch (IOException notGz) {
            return context.getAssets().open(VOCAB_ASSET_TXT);
        }
    }

    private Map<Integer, String> bytesToUnicode() {
        Map<Integer, String> map = new HashMap<>();
        List<Integer> bs = new ArrayList<>();
        for (int i = (int) '!'; i <= '~'; i++) bs.add(i);
        for (int i = 161; i <= 172; i++) bs.add(i);
        for (int i = 174; i <= 255; i++) bs.add(i);
        List<Integer> cs = new ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        for (int i = 0; i < bs.size(); i++) {
            map.put(bs.get(i), new String(new int[]{cs.get(i)}, 0, 1));
        }
        return map;
    }

    private String clean(String text) {
        if (text == null) return "";
        CharSequence unescaped = HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY);
        String basic = unescaped.toString().trim();
        String collapsed = basic.replaceAll("\\s+", " ");
        return collapsed.toLowerCase(Locale.getDefault());
    }
}
