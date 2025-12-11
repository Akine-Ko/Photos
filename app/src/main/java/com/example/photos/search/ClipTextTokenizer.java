package com.example.photos.search;

import android.content.Context;
import android.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * OpenCLIP simple tokenizer (context length 77) for MobileCLIP2.
 * <p>
 * Ported from OpenAI CLIP simple_tokenizer.py and OpenCLIP. Uses merges from
 * assets/models/clip/bpe_simple_vocab_16e6.txt.gz.
 */
final class ClipTextTokenizer {

    private static final int CONTEXT_LENGTH = 77;
    private static final String BPE_ASSET_GZ = "models/clip/bpe_simple_vocab_16e6.txt.gz";
    private static final String BPE_ASSET_TXT = "models/clip/bpe_simple_vocab_16e6.txt";
    private static final String SOT_TOKEN = "<|startoftext|>";
    private static final String EOT_TOKEN = "<|endoftext|>";

    private final Map<String, Integer> encoder;
    private final Map<Pair<String, String>, Integer> bpeRanks;
    // Java 正则默认就是 Unicode 感知，去掉 UNICODE_CHARACTER_CLASS 以兼容部分设备上的实现。
    private final Pattern pat = Pattern.compile("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+");

    ClipTextTokenizer(Context context) throws IOException {
        List<String> merges = loadMerges(context);
        Map<Integer, String> byteEncoder = bytesToUnicode();
        Map<String, Integer> byteDecoder = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : byteEncoder.entrySet()) {
            byteDecoder.put(e.getValue(), e.getKey());
        }
        List<String> vocab = new ArrayList<>(byteEncoder.values());
        List<Pair<String, String>> mergesPairs = new ArrayList<>();
        for (String merge : merges) {
            String[] parts = merge.split(" ");
            if (parts.length == 2) {
                mergesPairs.add(new Pair<>(parts[0], parts[1]));
            }
        }
        for (Pair<String, String> p : mergesPairs) {
            vocab.add(p.first + p.second);
        }
        vocab.add(SOT_TOKEN);
        vocab.add(EOT_TOKEN);
        encoder = new LinkedHashMap<>();
        for (int i = 0; i < vocab.size(); i++) {
            encoder.put(vocab.get(i), i);
        }
        bpeRanks = new LinkedHashMap<>();
        for (int i = 0; i < mergesPairs.size(); i++) {
            bpeRanks.put(mergesPairs.get(i), i);
        }
    }

    int[] tokenize(String text) {
        if (text == null) text = "";
        List<Integer> tokens = new ArrayList<>();
        tokens.add(encoder.get(SOT_TOKEN));
        Matcher m = pat.matcher(text);
        while (m.find()) {
            String tok = m.group();
            byte[] utf8 = tok.getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            for (byte b : utf8) {
                sb.append(bytesToUnicode().get(b & 0xFF));
            }
            List<String> bpeTokens = bpe(sb.toString());
            for (String bpeTok : bpeTokens) {
                Integer id = encoder.get(bpeTok);
                if (id != null) tokens.add(id);
            }
            if (tokens.size() >= CONTEXT_LENGTH - 1) break;
        }
        tokens.add(encoder.get(EOT_TOKEN));
        int[] out = new int[CONTEXT_LENGTH];
        Arrays.fill(out, encoder.get(EOT_TOKEN));
        for (int i = 0; i < Math.min(out.length, tokens.size()); i++) {
            out[i] = tokens.get(i);
        }
        return out;
    }

    private List<String> bpe(String token) {
        if (token.length() == 1) {
            return Arrays.asList(token);
        }
        List<String> word = new ArrayList<>(Arrays.asList(token.split("")));
        Set<Pair<String, String>> pairs = getPairs(word);
        if (pairs.isEmpty()) {
            return Arrays.asList(token);
        }
        while (true) {
            Pair<String, String> bigram = null;
            int bestRank = Integer.MAX_VALUE;
            for (Pair<String, String> pair : pairs) {
                Integer rank = bpeRanks.get(pair);
                if (rank != null && rank < bestRank) {
                    bestRank = rank;
                    bigram = pair;
                }
            }
            if (bigram == null) {
                break;
            }
            List<String> newWord = new ArrayList<>();
            int i = 0;
            while (i < word.size()) {
                int j = indexOfPair(word, bigram, i);
                if (j == -1) {
                    newWord.addAll(word.subList(i, word.size()));
                    break;
                }
                newWord.addAll(word.subList(i, j));
                newWord.add(bigram.first + bigram.second);
                i = j + 2;
            }
            word = newWord;
            if (word.size() == 1) {
                break;
            }
            pairs = getPairs(word);
        }
        return word;
    }

    private int indexOfPair(List<String> word, Pair<String, String> pair, int start) {
        for (int i = start; i < word.size() - 1; i++) {
            if (word.get(i).equals(pair.first) && word.get(i + 1).equals(pair.second)) {
                return i;
            }
        }
        return -1;
    }

    private Set<Pair<String, String>> getPairs(List<String> word) {
        Set<Pair<String, String>> pairs = new LinkedHashSet<>();
        for (int i = 0; i < word.size() - 1; i++) {
            pairs.add(new Pair<>(word.get(i), word.get(i + 1)));
        }
        return pairs;
    }

    private List<String> loadMerges(Context context) throws IOException {
        List<String> merges = new ArrayList<>();
        InputStream raw = null;
        boolean gzip = false;
        try {
            raw = context.getAssets().open(BPE_ASSET_GZ);
            gzip = true;
        } catch (IOException ignore) {
            raw = context.getAssets().open(BPE_ASSET_TXT);
            gzip = false;
        }

        try (InputStream is = raw;
             InputStream wrapped = gzip ? new GZIPInputStream(is) : is;
             BufferedReader reader = new BufferedReader(new InputStreamReader(wrapped, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { // skip version line
                    first = false;
                    continue;
                }
                line = line.trim();
                if (!line.isEmpty()) {
                    merges.add(line);
                }
            }
        }
        return merges;
    }

    private Map<Integer, String> bytesToUnicode() {
        Map<Integer, String> map = new LinkedHashMap<>();
        List<Integer> bs = new ArrayList<>();
        for (int i = '!'; i <= '~'; i++) bs.add(i);
        for (int i = 0xA1; i <= 0xAC; i++) bs.add(i);
        for (int i = 0xAE; i <= 0xFF; i++) bs.add(i);
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
}
